package ssbudget.backend.mcp

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import org.slf4j.LoggerFactory

/** One MCP tool. `run` gets the `arguments` object of the call; `Left` is an error that the model can read and correct.
  *
  * @param readOnly
  *   the tool writes nothing. Clients use this (and `destructive`) to decide when to ask the user for permission
  * @param destructive
  *   the tool removes something that a second call cannot bring back
  */
final case class McpTool(
    name: String,
    description: String,
    inputSchema: Json,
    readOnly: Boolean,
    destructive: Boolean = false,
    run: JsonObject => IO[Either[String, Json]],
)

/** One MCP prompt: a procedure that the user starts by name. `text` becomes one user message. */
final case class McpPrompt(name: String, description: String, text: String)

/** The MCP protocol itself: JSON-RPC 2.0 messages in, JSON-RPC messages out. It knows nothing about HTTP or about authentication; [[McpRoutes]] has
  * already done both when a message arrives here.
  *
  * The server is stateless. It keeps no session, sends no notifications and makes no requests to the client, so each message is answered from its own
  * content. That is the full "Streamable HTTP" transport for a server that has only tools and prompts.
  */
final class McpServer(tools: List[McpTool], prompts: List[McpPrompt], instructions: String) {

  import McpServer.*

  private val log          = LoggerFactory.getLogger(classOf[McpServer])
  private val toolByName   = tools.map(t => t.name -> t).toMap
  private val promptByName = prompts.map(p => p.name -> p).toMap

  /** Answers one message. `None` = the message is a notification or a response, and gets no answer. `tokenName` is the access token that sent it, for
    * the audit log.
    */
  def handle(message: JsonObject, tokenName: String): IO[Option[Json]] = {
    val id     = message("id").filterNot(_.isNull)
    val params = message("params").flatMap(_.asObject).getOrElse(JsonObject.empty)
    (message("method").flatMap(_.asString), id) match {
      case (None, _)                   => IO.pure(None) // a response from the client: this server asks nothing, so it expects none
      case (Some(_), None)             => IO.pure(None) // a notification
      case (Some(method), Some(reqId)) =>
        dispatch(method, params, tokenName)
          .map {
            case Right(result)         => Json.obj("jsonrpc" := "2.0", "id" := reqId, "result" := result)
            case Left((code, message)) => error(reqId, code, message)
          }
          .handleError { e =>
            log.error(s"[mcp] $method failed", e)
            error(reqId, InternalError, "Internal error")
          }
          .map(Some(_))
    }
  }

  private def dispatch(method: String, params: JsonObject, tokenName: String): IO[Either[(Int, String), Json]] = method match {
    case "initialize"   =>
      val requested = params("protocolVersion").flatMap(_.asString)
      IO.pure(
        Right(
          Json.obj(
            "protocolVersion" := requested.filter(SupportedVersions.contains).getOrElse(SupportedVersions.head),
            "capabilities"    := Json.obj("tools" := Json.obj(), "prompts" := Json.obj()),
            "serverInfo"      := Json.obj("name" := "ssbudget", "version" := "1.0.0"),
            "instructions"    := instructions,
          ),
        ),
      )
    case "ping"         => IO.pure(Right(Json.obj()))
    case "tools/list"   => IO.pure(Right(Json.obj("tools" := tools.map(toolJson))))
    case "tools/call"   =>
      params("name").flatMap(_.asString).flatMap(toolByName.get) match {
        case None       => IO.pure(Left((InvalidParams, "Unknown tool")))
        case Some(tool) => callTool(tool, params("arguments").flatMap(_.asObject).getOrElse(JsonObject.empty), tokenName).map(Right(_))
      }
    case "prompts/list" =>
      IO.pure(Right(Json.obj("prompts" := prompts.map(p => Json.obj("name" := p.name, "description" := p.description)))))
    case "prompts/get"  =>
      IO.pure(params("name").flatMap(_.asString).flatMap(promptByName.get) match {
        case None    => Left((InvalidParams, "Unknown prompt"))
        case Some(p) =>
          Right(
            Json.obj(
              "description" := p.description,
              "messages"    := List(Json.obj("role" := "user", "content" := Json.obj("type" := "text", "text" := p.text))),
            ),
          )
      })
    case _              => IO.pure(Left((MethodNotFound, "Method not found")))
  }

  /** A tool failure is a RESULT with `isError`, not a protocol error: the model must see it to correct its call. An exception is not shown — its text
    * can hold internals — and goes to the log. Each write is logged with its arguments, so there is a record of what a token did.
    */
  private def callTool(tool: McpTool, args: JsonObject, tokenName: String): IO[Json] = {
    val audit = IO.whenA(!tool.readOnly)(
      IO(log.info(s"[mcp] token '${tokenName.filter(!_.isControl)}': ${tool.name} ${Json.fromJsonObject(args).noSpaces.take(2000)}")),
    )
    (audit >> tool.run(args))
      .handleError { e =>
        log.error(s"[mcp] tool ${tool.name} failed", e)
        Left("Internal error")
      }
      .map {
        case Right(result) => Json.obj("content" := List(textContent(result.noSpaces)), "isError" := false)
        case Left(message) => Json.obj("content" := List(textContent(message)), "isError" := true)
      }
  }

  private def textContent(text: String): Json = Json.obj("type" := "text", "text" := text)

  private def toolJson(t: McpTool): Json = Json.obj(
    "name"        := t.name,
    "description" := t.description,
    "inputSchema" := t.inputSchema,
    "annotations" := Json.obj(
      "readOnlyHint"    := t.readOnly,
      "destructiveHint" := t.destructive,
      "openWorldHint"   := false,
    ),
  )
}

object McpServer {

  /** Protocol revisions that this server speaks, newest first. All of them have the Streamable HTTP transport. */
  val SupportedVersions: List[String] = List("2025-06-18", "2025-03-26")

  val ParseError     = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams  = -32602
  val InternalError  = -32603

  def error(id: Json, code: Int, message: String): Json =
    Json.obj("jsonrpc" := "2.0", "id" := id, "error" := Json.obj("code" := code, "message" := message))
}

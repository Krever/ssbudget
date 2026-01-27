package ssbudget.shared.json

import io.circe.{Codec, Decoder, Encoder}

object EnumCodec {

  /** Creates a circe Codec for an enum type.
    *
    * @param values
    *   All enum values (e.g., `Currency.values`)
    * @param toName
    *   Function to convert enum value to its JSON string representation
    * @param typeName
    *   Name used in error messages (defaults to "value")
    */
  def apply[E](values: Array[E], toName: E => String, typeName: String = "value"): Codec[E] = {
    val nameToValue = values.map(v => toName(v) -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => nameToValue.get(s).toRight(s"Unknown $typeName: $s")),
      Encoder.encodeString.contramap(toName),
    )
  }
}

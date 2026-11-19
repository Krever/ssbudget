import { defineConfig } from 'vite'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

// Serve over HTTPS when local certs are present (Enable Banking requires an https
// redirect URL). `dev.sh` generates these into frontend/.certs; without them we
// fall back to plain HTTP.
const certKey = fileURLToPath(new URL('./.certs/localhost-key.pem', import.meta.url))
const certCrt = fileURLToPath(new URL('./.certs/localhost.pem', import.meta.url))
const https = fs.existsSync(certKey) && fs.existsSync(certCrt)
  ? { key: fs.readFileSync(certKey), cert: fs.readFileSync(certCrt) }
  : undefined

// index.html loads the output of `sbt frontend/fastLinkJS`: the fast link that the dev server and a quick local build use.
// A release build sets SCALAJS_STAGE=full, and the page then loads the output of `sbt frontend/fullLinkJS` (the optimized link).
// Without this, a release bundled the fast output of whatever was last compiled, and it failed on a fresh checkout.
const scalaJSStage = () => ({
  name: 'ssbudget-scalajs-stage',
  transformIndexHtml: {
    order: 'pre',
    handler: (html) => {
      if (process.env.SCALAJS_STAGE !== 'full') return html
      if (!html.includes('/frontend-fastopt/')) throw new Error('index.html does not load frontend-fastopt/main.js; update scalaJSStage')
      return html.replace('/frontend-fastopt/', '/frontend-opt/')
    }
  }
})

export default defineConfig(({ mode }) => ({
  plugins: [
    scalaJSStage(),
    scalaJSPlugin({
      cwd: "..",
      projectID: "frontend"
    })
  ],
  server: {
    port: 3000,
    https,
    // `changeOrigin: false` keeps the Host header of the page. The backend accepts a write only when it comes from the page it serves
    // (the Origin of the request is its Host; see Hardening.scala), and the short form of a proxy entry would replace the Host.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      // Metabase, reached only through the backend's session-gated proxy (see AnalyticsProxyRoutes).
      '/metabase': { target: 'http://localhost:8080', changeOrigin: false },
      // The MCP endpoint, so that the command the Settings page gives also works against the dev server.
      '/mcp': { target: 'http://localhost:8080', changeOrigin: false }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true
  }
}))

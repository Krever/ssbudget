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

export default defineConfig(({ mode }) => ({
  plugins: [
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

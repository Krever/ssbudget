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
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true
  }
}))

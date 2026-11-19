import { defineConfig } from 'vite'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"

// E2E test configuration - reads ports from environment variables
const frontendPort = parseInt(process.env.VITE_PORT || '3002', 10)
const backendUrl = process.env.VITE_API_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      cwd: "..",
      projectID: "frontend"
    })
  ],
  server: {
    port: frontendPort,
    strictPort: true,
    host: '127.0.0.1',
    // `changeOrigin: false` keeps the Host header of the page: the backend accepts a write only when its Origin is its Host
    // (see Hardening.scala), and the short form of a proxy entry would replace the Host.
    proxy: {
      '/api': { target: backendUrl, changeOrigin: false }
    }
  }
})

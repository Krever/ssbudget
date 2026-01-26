import { defineConfig } from 'vite'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"

export default defineConfig({
  plugins: [
    scalaJSPlugin({
      cwd: "..",
      projectID: "frontend"
    })
  ],
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})

import { defineConfig } from 'vite'
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs"

export default defineConfig(({ mode }) => ({
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
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: true
  }
}))

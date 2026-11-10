import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The development server proxies the API to the Spring Boot backend, so the
 * frontend talks to the same origin it is served from — no CORS, no
 * hard-coded host in the code.
 *
 *   npm run dev   ->  http://localhost:5173   (API proxied to :8080)
 *
 * Set VITE_API_TARGET to point at a backend on another host or port, for
 * example when the backend runs on 8092:
 *
 *   VITE_API_TARGET=http://localhost:8092 npm run dev
 *
 * Vite refuses unknown host names by default (protection against DNS
 * rebinding). The dev server is often reached through a tunnel, a container
 * name or a preview host, so the hosts may be listed explicitly:
 *
 *   ALLOWED_HOSTS=localhost,127.0.0.1 npm run dev
 *
 * Without that variable every host is allowed, which is convenient for
 * development and should not be used for a public machine.
 */
const target = process.env.VITE_API_TARGET || 'http://localhost:8080'

// Every host is allowed unless the operator narrows it down. The dev server
// is often reached through a tunnel, a container name or a preview host, and
// refusing those makes the preview unusable; a public machine should set
// ALLOWED_HOSTS to the names it really answers to.
function readAllowedHosts(value) {
  if (!value) return true
  const trimmed = value.trim()
  // "true" and "*" both mean "any host"; anything else is a list of names.
  if (trimmed === '' || trimmed === 'true' || trimmed === '*') return true
  return trimmed
    .split(',')
    .map((host) => host.trim())
    .filter(Boolean)
}

const allowedHosts = readAllowedHosts(process.env.ALLOWED_HOSTS)

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts,
    proxy: {
      '/api': {
        target,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})

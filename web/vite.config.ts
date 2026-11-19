import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const api = process.env.API_TARGET ?? 'http://127.0.0.1:8097'

export default defineConfig({
  base: '/anaquel/',
  plugins: [react()],
  server: { proxy: { '/anaquel/api': api, '/anaquel/media': api } },
})

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backendUrl = process.env.VITE_DEV_BACKEND_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/auth': backendUrl,
      '/agent': backendUrl,
      '/admin': backendUrl,
    },
  },
})

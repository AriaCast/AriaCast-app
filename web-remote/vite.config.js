import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The phone serves index.html at / and bundles under /assets/.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: true,
    assetsDir: 'assets',
  },
  server: {
    // `npm run dev` against a phone: VITE_PHONE=http://192.168.1.20:8930 npm run dev
    proxy: process.env.VITE_PHONE ? { '/api': process.env.VITE_PHONE } : undefined,
  },
})

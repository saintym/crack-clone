import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// 여러 worktree에서 앱을 동시에 띄울 수 있게 포트와 프록시 대상을 환경변수로 바꾼다 (docs/PARALLEL.md)
// 예: CRACK_WEB_PORT=15207 CRACK_API_TARGET=http://localhost:18207 npm run dev
const webPort = Number(process.env.CRACK_WEB_PORT) || 5173
const apiTarget = process.env.CRACK_API_TARGET || 'http://localhost:8082'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      // 화면(index.html)은 캐시하지 않고 매번 서버에서 받는다 (T42).
      // 프리캐시해 두면 새 빌드를 올려도 **캐시된 옛 페이지가 먼저 떠서** 새로고침을 두 번 해야 했다.
      // 해시가 붙은 에셋은 그대로 프리캐시한다(불변이라 안전하고 두 번째 방문이 빠르다).
      // 대가: 오프라인에서 앱 껍데기가 열리지 않는다. AI 응답에 서버가 필요하므로 오프라인 값어치가 크지 않다.
      workbox: {
        globPatterns: ['**/*.{js,css,svg,png,ico,webmanifest}'],
        navigateFallback: undefined,
        cleanupOutdatedCaches: true,
      },
      manifest: {
        name: 'Crack Clone',
        short_name: 'Crack',
        description: 'AI 캐릭터 채팅',
        theme_color: '#0f0f0f',
        background_color: '#0f0f0f',
        display: 'standalone',
        orientation: 'portrait',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
    }),
  ],
  server: {
    host: '0.0.0.0',
    port: webPort,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
})

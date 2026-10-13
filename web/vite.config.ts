// [파일 역할] Vite(개발 서버·빌드)와 Vitest(테스트) 설정을 한 파일에 둔다. defineConfig를 'vitest/config'에서
// 가져오는 이유는 test 항목의 타입까지 함께 검사받기 위해서다.
// @vitejs/plugin-react: JSX 변환과 개발 중 빠른 새로고침(HMR)을 맡는 공식 플러그인.

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 개발 서버(npm run dev, 5173). 화면 코드는 /api로 시작하는 같은 출처 경로만 부르고, Vite가 그것을 Spring
  // Boot 서버(8080)로 넘긴다. 브라우저 입장에서는 같은 출처라 서버에 CORS 설정이 필요 없다. 배포 이미지에서는
  // nginx가 같은 역할을 한다(web/nginx.conf의 /api/ 프록시).
  server: {
    port: 5173,
    // The Spring Boot server (./gradlew bootRun) listens on 8080; the page talks to /api only.
    proxy: { '/api': 'http://localhost:8080' },
  },
  // Vitest 설정: jsdom(브라우저 DOM 흉내)에서 컴포넌트를 그려 보고, describe/it 같은 전역 함수를 import 없이
  // 쓰게 하며(globals), 매 테스트 전에 src/test/setup.ts(jest-dom의 toBeInTheDocument 같은 DOM 단언 등록)를
  // 실행한다.
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
})

// [파일 역할] 브라우저에서 가장 먼저 실행되는 앱 진입점(index.html의 script가 부른다). 전역 CSS를 불러오고
// App 컴포넌트를 #root에 그린다.
// [참고] 이 파일은 Vite 템플릿 그대로라 다른 파일과 달리 작은따옴표·세미콜론 없는 스타일이다.

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import createCache from '@emotion/cache'
import { CacheProvider } from '@emotion/react'
import './index.css'
import App from './App.tsx'

// CSP nonce(2026-10-15): 배포 이미지의 nginx가 index.html의 __CSP_NONCE__ 자리를 요청마다 새 값으로 바꾸고, 같은 값을
// CSP 헤더의 style-src 'nonce-...'에 넣는다(web/nginx.conf). MUI가 쓰는 emotion은 실행 중에 <style> 태그를 만들므로
// 그 태그에 이 nonce를 달아야 'unsafe-inline' 없이도 스타일이 적용된다. 처음에는 style-src 'unsafe-inline'으로
// 허용했고 OWASP ZAP 전체 스택 스캔이 이를 Medium 경보로 잡았다(docs/experiments.md 5단계 'DAST 범위 넓히기'). Vite
// 개발 서버에서는 자리 표시가 바뀌지 않아 nonce 없이 동작한다(개발 서버에는 CSP 헤더도 없다).
const nonceMeta = document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content
const nonce = nonceMeta && nonceMeta !== '__CSP_NONCE__' ? nonceMeta : undefined
const emotionCache = createCache({ key: 'css', nonce })

// React 18+ 방식의 루트 생성(createRoot). index.html에 #root가 반드시 있으므로 ! (null 아님 단언)로 타입만
// 좁힌다.
// StrictMode는 개발 모드에서만 컴포넌트·effect를 한 번 더 실행해 정리(cleanup) 누락 같은 부작용 버그를
// 드러낸다. 배포 빌드에는 영향이 없다.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <CacheProvider value={emotionCache}>
      <App />
    </CacheProvider>
  </StrictMode>,
)

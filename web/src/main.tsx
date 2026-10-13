// [파일 역할] 브라우저에서 가장 먼저 실행되는 앱 진입점(index.html의 script가 부른다). 전역 CSS를 불러오고
// App 컴포넌트를 #root에 그린다.
// [참고] 이 파일은 Vite 템플릿 그대로라 다른 파일과 달리 작은따옴표·세미콜론 없는 스타일이다.

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// React 18+ 방식의 루트 생성(createRoot). index.html에 #root가 반드시 있으므로 ! (null 아님 단언)로 타입만
// 좁힌다.
// StrictMode는 개발 모드에서만 컴포넌트·effect를 한 번 더 실행해 정리(cleanup) 누락 같은 부작용 버그를
// 드러낸다. 배포 빌드에는 영향이 없다.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

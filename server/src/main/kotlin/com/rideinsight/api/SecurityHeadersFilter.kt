package com.rideinsight.api

// [파일 역할] 서버가 내보내는 모든 HTTP 응답에 보안 헤더를 붙이는 서블릿 필터다.
// [왜 필요한가] 2026-10-10 OWASP ZAP 1차 스캔에서 X-Content-Type-Options 누락이 Low 경보로 나와 추가했고,
// 같은 계획으로 다시 돌린 2차 스캔에서 이 경보가 사라졌다(docs/experiments.md 5단계 H9).
// [연결] @Component라 Spring Boot가 자동으로 서블릿 필터 체인에 등록한다. ApiIntegrationTest가 nosniff와
// no-store 헤더를 확인한다.

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// OncePerRequestFilter를 상속해 한 요청에 한 번만 실행되게 한다(내부 forward·오류 디스패치 때 중복 실행
// 방지).
/**
 * JSON만 돌려주는 API에 맞춘 응답 보안 헤더를 붙인다(2026-10-10 OWASP ZAP 1차 스캔 경보
 * "X-Content-Type-Options Header Missing"). 화면 페이지는 이 서버가 아니라 nginx나 Vite가 내보낸다.
 */
@Component
class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // - X-Content-Type-Options: nosniff: 브라우저가 Content-Type을 추측(스니핑)해 다른 형식으로 해석하지
        //   않게 한다.
        // - X-Frame-Options: DENY와 CSP의 frame-ancestors 'none': 응답을 다른 페이지의 프레임 안에 넣지
        //   못하게 한다(클릭재킹 방지).
        // - Referrer-Policy: no-referrer: 이동할 때 Referer 헤더를 보내지 않는다.
        // - Content-Security-Policy: default-src 'none': 이 서버는 JSON만 돌려주고 화면은 nginx·Vite가
        //   내보내므로, 응답이 문서로 열리더라도 스크립트·스타일 등 어떤 리소스도 불러오지 못하게 막는다.
        // - Cache-Control: no-store: 선수 데이터가 담긴 응답을 브라우저·중간 캐시에 저장하지 않게 한다.
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        response.setHeader("Cache-Control", "no-store")
        // 헤더는 응답이 커밋(전송 시작)되기 전에 넣어야 하므로, 다음 필터·컨트롤러로 넘기기 전에 먼저
        // 설정한다.
        chain.doFilter(request, response)
    }
}

package com.rideinsight.api

// [파일 역할] 요청의 Host(포트를 뺀 서버 이름)가 허용 목록에 없으면 다른 필터·컨트롤러에 닿기 전에 421 Misdirected
// Request로 돌려보내는 서블릿 필터다.
// [왜 필요한가] 이 API에는 인증이 없고(README 한계), 포트를 127.0.0.1에만 여는 것(compose.yaml)이 출처를 가리는 유일한
// 장치였다. DNS rebinding에서는 사용자가 연 외부 페이지(예: attacker.example)가 자기 도메인의 DNS 응답을 짧은 TTL 뒤
// 127.0.0.1로 바꾸고, 브라우저는 그 뒤의 요청을 같은 출처로 보므로 preflight 없이 http://attacker.example:8080/api/...를
// 부르고 응답까지 읽는다. 이때 브라우저가 보내는 Host는 attacker.example:8080이라, 서버가 자기 이름이 아닌 Host를 거절하면
// 이 경로가 막힌다(2026-10-19 코드 검토). 고치기 전에는 `Host: evil.example`로 보낸 선수 생성 요청이 201이었다
// (ApiIntegrationTest). 웹(nginx, 8081)은 web/nginx.conf에서 같은 규칙을 따로 둔다(모르는 Host는 응답 없이 연결을 닫음).
// [무엇을 허용하나] 설정값 ride-insight.allowed-hosts(쉼표로 나눈 목록, 기본 localhost,127.0.0.1,[::1]). 포트는 보지
// 않는다: 테스트는 임의 포트, 개발은 8080과 Vite 5173, compose는 8080·8081을 쓰고, rebinding을 막는 데는 이름만 있으면
// 된다. 호스트 이름 규칙대로 대소문자는 가리지 않는다. 끝에 점이 붙은 이름(localhost.)이나 다른 IPv6 표기
// ([0:0:0:0:0:0:0:1])는 다른 이름으로 보고 거절한다. Host 문자열을 DNS로 풀어 비교하지는 않는다(받은 이름마다 조회가
// 나가고, 결과가 공격자의 DNS에 달리게 된다).
// [어떤 값을 보나] HttpServletRequest.getServerName(): Tomcat이 Host 헤더(요청 줄이 절대 URI면 그 호스트, 둘이 다르면
// Tomcat이 먼저 400)에서 포트를 뗀 값이고 IPv6는 괄호까지 들어 있다([::1]). HTTP/1.1에서 Host가 없으면 Tomcat이 먼저
// 400으로 거절한다. Host가 없는 HTTP/1.0 요청은 Tomcat이 서버 이름을 엔진의 기본 호스트 이름(내장 Tomcat은 localhost)으로
// 채우므로 통과한다(Tomcat 11.0.27 Mapper, 2026-10-29 compose 스택에서 확인). 브라우저는 늘 Host를 보내므로 rebinding
// 경로가 아니다.
// [통과하는 쪽] compose healthcheck(wget http://localhost:8080), web/nginx.conf의 /api/ 프록시(proxy_set_header Host $host라
// 브라우저가 쓴 localhost·127.0.0.1이 포트 없이 온다), Vite 개발 프록시(Host를 바꾸지 않아 localhost:5173),
// ml의 load.py와 ZAP(127.0.0.1), Testcontainers 통합 테스트(localhost:<임의 포트>). 다른 기기에서 보려고 포트를 바깥에
// 열면 그 주소를 이 목록에 더해야 한다(compose.yaml 머리 주석).
// [순서] @Order(HIGHEST_PRECEDENCE)로 가장 먼저 돈다. 거절할 요청은 업로드 multipart 본문을 해석하거나(DispatcherServlet)
// 다른 필터가 일하기 전에 끝낸다. 그래서 SecurityHeadersFilter를 거치지 않으므로, 거절 응답에 의미 있는 헤더(nosniff,
// no-store)는 여기서 직접 붙인다. CSP·X-Frame-Options는 문서로 그려지는 응답을 위한 것이라 고정 문구 JSON에는 이득이 없다.

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.Locale
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Host가 [allowedHosts]에 없는 요청을 421 `host_not_allowed`로 끝낸다(DNS rebinding 방지). 목록은 포트 없는 이름이고
 * 대소문자를 가리지 않는다. 빈 목록은 시작할 때 [IllegalArgumentException].
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HostAllowlistFilter(
    @Value("\${ride-insight.allowed-hosts:localhost,127.0.0.1,[::1]}") allowedHosts: List<String>,
) : OncePerRequestFilter() {
    // 비교용 목록: 앞뒤 공백을 지우고 소문자로 바꾸며, 빈 항목(쉼표가 겹친 자리 등)은 버린다.
    private val allowed: Set<String> = allowedHosts.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    init {
        // 목록이 비면 모든 요청이 421이 된다. 조용히 그렇게 뜨지 않고 시작할 때 설정 오류로 멈춘다.
        require(allowed.isNotEmpty()) { "ride-insight.allowed-hosts must name at least one host" }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val host = request.serverName?.lowercase(Locale.ROOT)
        if (host != null && host in allowed) {
            chain.doFilter(request, response)
            return
        }
        // 거절. 받은 Host 값은 응답에 되돌려 쓰지 않는다(고정 문구, 400 오류 문구와 같은 방침). 길이를 먼저 알려 chunked
        // 없이 보낸다. 요청 본문은 읽지 않는다(남은 본문은 Tomcat이 max-swallow-size까지 버리거나 연결을 닫는다).
        response.status = MISDIRECTED_REQUEST
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Cache-Control", "no-store")
        response.setContentLength(BODY.size)
        response.outputStream.write(BODY)
    }

    companion object {
        /** RFC 9110 15.5.20 421 Misdirected Request: 이 서버는 요청이 가리킨 대상(여기서는 Host)에 답하지 않는다. */
        const val MISDIRECTED_REQUEST = 421

        // API 오류 형식({ "error", "message" }, docs/api.md)과 같은 고정 본문. ASCII라 바이트 수가 곧 글자 수다.
        private val BODY = """{"error":"host_not_allowed","message":"host not allowed"}""".toByteArray(Charsets.UTF_8)
    }
}

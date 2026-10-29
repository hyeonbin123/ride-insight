package com.rideinsight.api

// [파일 역할] HostAllowlistFilter(Host 허용 목록, DNS rebinding 방지)의 단위 테스트. 서블릿 목 객체(spring-test)로 Host
// 헤더만 바꿔 가며 허용·거절과 거절 응답의 모양을 확인한다. Spring 컨텍스트와 DB 없이 돈다.
// [나눈 이유] 목 요청의 getServerName()은 Host 헤더에서 포트를 떼는 규칙(IPv6는 괄호 포함)이 Tomcat과 같아 필터의 판단만
// 빠르게 볼 수 있다. 실제 Tomcat이 Host를 해석하는 경로(포트, [::1], 대문자)는 ApiIntegrationTest가 소켓으로 따로 확인한다.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

class HostAllowlistFilterTest {
    private val mapper = JsonMapper.builder().build()

    // application.properties의 기본 목록(ride-insight.allowed-hosts)과 같은 값.
    private val defaults = HostAllowlistFilter(listOf("localhost", "127.0.0.1", "[::1]"))

    // 필터를 한 번 통과시킨 결과: 응답과, 요청이 다음 단계(다른 필터·컨트롤러)로 넘어갔는지.
    private class Outcome(val response: MockHttpServletResponse, val passed: Boolean)

    // Host 헤더 하나를 단 GET 요청을 필터에 넣는다. MockFilterChain은 doFilter가 불리면 받은 요청을 기억하므로
    // chain.request가 null이 아니면 필터가 요청을 넘긴 것이다.
    private fun send(filter: HostAllowlistFilter, host: String): Outcome {
        val request = MockHttpServletRequest("GET", "/api/athletes/x")
        request.addHeader("Host", host)
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return Outcome(response, chain.request != null)
    }

    // 이 PC를 가리키는 이름은 포트와 대소문자에 상관없이 통과한다: 테스트의 임의 포트, 개발 서버 8080, Vite 5173,
    // compose의 8080·8081, 포트를 생략한 Host(80번)까지.
    @Test
    fun `loopback names pass on any port and in any letter case`() {
        for (host in listOf("localhost", "localhost:8080", "localhost:5173", "127.0.0.1", "127.0.0.1:8081", "[::1]", "[::1]:8080", "LocalHost:8081")) {
            val outcome = send(defaults, host)
            assertTrue(outcome.passed, "'$host' did not reach the chain")
            assertEquals(200, outcome.response.status, host)
        }
    }

    // 목록에 없는 이름은 다음 단계로 넘기지 않고 421 + 고정 JSON 오류로 끝낸다. 허용 이름을 앞뒤에 품은 이름
    // (localhost.evil.example, evil-localhost, 127.0.0.1.nip.io), 다른 루프백 주소(127.0.0.2, [::2]), 끝에 점이 붙은
    // 이름(localhost.), 빈 Host도 모두 거절한다. 받은 Host 값은 응답에 되돌려 쓰지 않는다.
    @Test
    fun `any other host is refused with 421 and a fixed JSON error before the chain runs`() {
        val bodies = mutableSetOf<String>()
        for (host in listOf("evil.example", "evil.example:8080", "localhost.evil.example", "evil-localhost", "127.0.0.1.nip.io", "127.0.0.2", "[::2]:8080", "localhost.", "")) {
            val outcome = send(defaults, host)
            assertFalse(outcome.passed, "'$host' reached the chain")
            assertEquals(421, outcome.response.status, host)
            assertEquals("application/json", outcome.response.contentType?.substringBefore(';'), host)
            val body = mapper.readTree(outcome.response.contentAsString)
            assertEquals("host_not_allowed", body.path("error").asString(), host)
            assertEquals("host not allowed", body.path("message").asString(), host)
            assertEquals("nosniff", outcome.response.getHeader("X-Content-Type-Options"), host)
            assertEquals("no-store", outcome.response.getHeader("Cache-Control"), host)
            bodies += outcome.response.contentAsString
        }
        // 본문은 Host가 무엇이든 같다(받은 값을 되돌려 쓰지 않는다).
        assertEquals(1, bodies.size, "$bodies")
    }

    // 목록은 설정값에서 온다. 앞뒤 공백과 빈 항목은 버리고 대소문자는 가리지 않는다. 목록을 바꾸면 기본 이름도
    // 더는 통과하지 않는다(다른 기기에서 보려고 주소를 더할 때는 기본 이름도 함께 적어야 한다).
    @Test
    fun `the list comes from the setting, ignoring blanks and letter case`() {
        val custom = HostAllowlistFilter(listOf(" Ride.Local ", "", "192.168.0.10"))
        assertTrue(send(custom, "ride.local:8081").passed)
        assertTrue(send(custom, "192.168.0.10").passed)
        assertFalse(send(custom, "localhost:8080").passed)
    }

    // 빈 목록이면 모든 요청이 421이 되므로, 조용히 그렇게 뜨지 않고 시작할 때 설정 오류로 멈춘다.
    @Test
    fun `an empty list is refused at startup instead of rejecting every request`() {
        assertFailsWith<IllegalArgumentException> { HostAllowlistFilter(listOf("", " ")) }
    }
}

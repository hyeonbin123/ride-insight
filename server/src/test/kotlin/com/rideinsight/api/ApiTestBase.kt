package com.rideinsight.api

// [파일 역할] HTTP 통합 테스트의 공통 기반 클래스. 무작위 포트에 실제 서버(내장 톰캣 + PostgreSQL 컨테이너)를
// 띄우고, RestClient로 요청을 보내 상태 코드와 JSON 본문을 돌려주는 도우미를 제공한다.
// [왜 이렇게 하나] 실제 HTTP로 호출하므로 multipart 크기 제한(413), 보안 헤더 필터, 존재하지 않는 경로의
// 404처럼 서블릿 컨테이너와 필터를 거쳐야 드러나는 동작까지 확인된다.
// [연결] ApiIntegrationTest와 TamperedFitTest가 상속한다.

import com.rideinsight.TestcontainersConfiguration
import java.io.File
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Shared HTTP helpers for the end-to-end tests against the PostgreSQL container. */
// @SpringBootTest(RANDOM_PORT): 실제 내장 톰캣을 빈 포트에 띄운다. PER_CLASS: 테스트 인스턴스를 클래스당
// 하나만 만들어, @BeforeAll을 static이 아닌 인스턴스 메서드로 쓰고 주입된 env를 읽을 수 있게 한다.
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiTestBase {
    // 무작위로 정해진 포트 번호(local.server.port)를 읽으려고 Environment를 주입받는다. 응답 JSON은 Jackson 3
    // JsonMapper로 읽고, 샘플 FIT 파일은 src/test/resources/fit(python-fitparse 테스트 파일, MIT)에서
    // 가져온다.
    @Autowired lateinit var env: Environment

    protected lateinit var client: RestClient
    protected val mapper: JsonMapper = JsonMapper.builder().build()
    protected val fitDir = File("src/test/resources/fit")

    // 테스트가 검사하는 결과: 상태 코드와 JSON 본문(본문이 비면 null).
    class Response(val status: Int, val body: JsonNode?)

    // 서버가 뜬 뒤 실제 포트로 RestClient를 만든다.
    @BeforeAll
    fun setUpClient() {
        val port = env.getProperty("local.server.port")
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
    }

    // 요청 하나를 보내고 상태 코드와 본문을 돌려준다. json이 있으면 JSON 본문, multipartName이 있으면 bytes를
    // file 파트로 보낸다. exchange는 retrieve와 달리 상태 코드 처리기를 거치지 않아 4xx·5xx에서도 예외 없이
    // 응답을 넘겨주므로 오류 응답도 그대로 검사할 수 있다. 본문은 람다 안에서 끝까지 읽는다.
    protected fun call(
        method: HttpMethod,
        path: String,
        json: String? = null,
        multipartName: String? = null,
        bytes: ByteArray? = null,
    ): Response {
        val spec = client.method(method).uri(path)
        if (json != null) spec.contentType(MediaType.APPLICATION_JSON).body(json)
        if (multipartName != null) {
            // multipart 업로드: 파일 이름과 octet-stream 형식을 단 file 파트 하나를 만든다.
            // MultipartBodyBuilder는 reactive-streams를 요구해 NoClassDefFoundError가 났기 때문에
            // MultiValueMap + HttpEntity로 만든다(docs/ai-verification.md 2단계).
            // Plain MultiValueMap parts: MultipartBodyBuilder needs reactive-streams on the classpath.
            val parts = LinkedMultiValueMap<String, Any>()
            val partHeaders = HttpHeaders()
            partHeaders.setContentDispositionFormData("file", multipartName)
            partHeaders.contentType = MediaType.APPLICATION_OCTET_STREAM
            parts.add("file", HttpEntity(ByteArrayResource(bytes!!), partHeaders))
            spec.contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
        }
        return spec.exchange({ _, res ->
            val text = res.body.readAllBytes().toString(Charsets.UTF_8)
            Response(res.statusCode.value(), if (text.isBlank()) null else mapper.readTree(text))
        }, false)
    }

    // 메서드별 짧은 도우미.
    protected fun post(path: String, json: String) = call(HttpMethod.POST, path, json = json)
    protected fun put(path: String, json: String) = call(HttpMethod.PUT, path, json = json)
    protected fun get(path: String) = call(HttpMethod.GET, path)
    protected fun delete(path: String) = call(HttpMethod.DELETE, path)

    // 바이트 배열(직접 만들거나 변조한 FIT)을 업로드한다.
    protected fun uploadBytes(athleteId: String, name: String, bytes: ByteArray) =
        call(HttpMethod.POST, "/api/athletes/$athleteId/activities", multipartName = name, bytes = bytes)

    // src/test/resources/fit의 샘플 파일을 이름으로 업로드한다.
    protected fun upload(athleteId: String, name: String) = uploadBytes(athleteId, "$name.fit", File(fitDir, "$name.fit").readBytes())

    // 선수를 만들고 id를 돌려준다. 기본 FTP는 315 W(Edge810 샘플 세션의 threshold_power와 같은 값)이고,
    // null이면 FTP 없이 만든다. 201이 아니면 check로 테스트를 바로 멈춘다.
    protected fun createAthlete(ftp: Int? = 315, name: String = "Tester"): String {
        val ftpJson = if (ftp != null) ",\"ftp\":$ftp" else ""
        val r = post("/api/athletes", """{"name":"$name","gender":"M","birthYear":1990,"weightKg":72.5$ftpJson}""")
        check(r.status == 201) { "athlete creation failed: ${r.status} ${r.body}" }
        return r.body!!.path("id").asString()
    }
}

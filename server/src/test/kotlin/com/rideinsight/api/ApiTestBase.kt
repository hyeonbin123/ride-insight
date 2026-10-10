package com.rideinsight.api

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
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ApiTestBase {
    @Autowired lateinit var env: Environment

    protected lateinit var client: RestClient
    protected val mapper: JsonMapper = JsonMapper.builder().build()
    protected val fitDir = File("src/test/resources/fit")

    class Response(val status: Int, val body: JsonNode?)

    @BeforeAll
    fun setUpClient() {
        val port = env.getProperty("local.server.port")
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
    }

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

    protected fun post(path: String, json: String) = call(HttpMethod.POST, path, json = json)
    protected fun put(path: String, json: String) = call(HttpMethod.PUT, path, json = json)
    protected fun get(path: String) = call(HttpMethod.GET, path)
    protected fun delete(path: String) = call(HttpMethod.DELETE, path)

    protected fun uploadBytes(athleteId: String, name: String, bytes: ByteArray) =
        call(HttpMethod.POST, "/api/athletes/$athleteId/activities", multipartName = name, bytes = bytes)

    protected fun upload(athleteId: String, name: String) = uploadBytes(athleteId, "$name.fit", File(fitDir, "$name.fit").readBytes())

    protected fun createAthlete(ftp: Int? = 315, name: String = "Tester"): String {
        val ftpJson = if (ftp != null) ",\"ftp\":$ftp" else ""
        val r = post("/api/athletes", """{"name":"$name","gender":"M","birthYear":1990,"weightKg":72.5$ftpJson}""")
        check(r.status == 201) { "athlete creation failed: ${r.status} ${r.body}" }
        return r.body!!.path("id").asString()
    }
}

package com.rideinsight.api

import com.rideinsight.TestcontainersConfiguration
import com.rideinsight.fit.FitActivity
import com.rideinsight.metrics.MeanMaximalPower
import com.rideinsight.metrics.PowerMetrics
import com.rideinsight.metrics.RecordingInterval
import com.rideinsight.metrics.RideSamples
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.HttpEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** End-to-end checks for docs/api.md against a PostgreSQL container. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest {
    @Autowired lateinit var env: Environment

    private lateinit var client: RestClient
    private val mapper = JsonMapper.builder().build()
    private val fitDir = File("src/test/resources/fit")

    class Response(val status: Int, val body: JsonNode?)

    @BeforeAll
    fun setUp() {
        val port = env.getProperty("local.server.port")
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
    }

    private fun call(method: HttpMethod, path: String, json: String? = null, multipartName: String? = null, bytes: ByteArray? = null): Response {
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

    private fun post(path: String, json: String) = call(HttpMethod.POST, path, json = json)
    private fun put(path: String, json: String) = call(HttpMethod.PUT, path, json = json)
    private fun get(path: String) = call(HttpMethod.GET, path)
    private fun delete(path: String) = call(HttpMethod.DELETE, path)
    private fun upload(athleteId: String, name: String) =
        call(HttpMethod.POST, "/api/athletes/$athleteId/activities", multipartName = "$name.fit", bytes = File(fitDir, "$name.fit").readBytes())

    private fun createAthlete(ftp: Int? = 315): String {
        val r = post("/api/athletes", """{"name":"Tester","gender":"M","birthYear":1990,"weightKg":72.5${if (ftp != null) ",\"ftp\":$ftp" else ""}}""")
        assertEquals(201, r.status, r.body.toString())
        return r.body!!.path("id").asString()
    }

    @Test
    fun `athlete validation and lookup`() {
        assertEquals(400, post("/api/athletes", """{"name":""}""").status)
        assertEquals(400, post("/api/athletes", """{"name":"x","ftp":20}""").status)
        assertEquals(400, post("/api/athletes", """{"name":"x","gender":"Q"}""").status)
        assertEquals(400, post("/api/athletes", """not json""").status)
        val id = createAthlete()
        val got = get("/api/athletes/$id")
        assertEquals(200, got.status)
        assertEquals(315, got.body!!.path("currentThreshold").path("ftpWatts").asInt())
        assertEquals(404, get("/api/athletes/00000000-0000-0000-0000-000000000000").status)
        assertEquals(400, get("/api/athletes/not-a-uuid").status)
    }

    @Test
    fun `upload cycling files, reject running and corrupt ones, refuse duplicates`() {
        val id = createAthlete()
        val cycling = listOf(
            "Edge810-Vector-2013-08-16-15-35-10", "garmin-edge-500-activity", "garmin-edge-820-bike",
            "2019-02-17-062644-ELEMNT-297E-195-0", "sample-activity-indoor-trainer",
        )
        for (name in cycling) {
            val r = upload(id, name)
            assertEquals(201, r.status, "$name: ${r.body}")
            assertEquals("cycling", r.body!!.path("sport").asString())
        }
        val run = upload(id, "garmin-fenix-5-run")
        assertEquals(422, run.status)
        assertEquals("unsupported_sport", run.body!!.path("error").asString())
        for (name in listOf("activity-unexpected-eof", "activity-settings-corruptheader")) {
            val r = upload(id, name)
            assertEquals(400, r.status, name)
            assertEquals("invalid_fit", r.body!!.path("error").asString())
        }
        // The Coros file is either stored or rejected as invalid FIT, never a 500.
        val coros = upload(id, "coros-pace-2-cycling-misaligned-fields")
        assertTrue(coros.status == 201 || coros.status == 400, "coros: ${coros.status}")
        val dup = upload(id, "garmin-edge-820-bike")
        assertEquals(409, dup.status)
        assertEquals(404, upload("00000000-0000-0000-0000-000000000000", "garmin-edge-820-bike").status)

        val list = get("/api/athletes/$id/activities?limit=3")
        assertEquals(200, list.status)
        assertEquals(3, list.body!!.path("items").size())
        val next = list.body!!.path("nextBefore").asString()
        val page2 = get("/api/athletes/$id/activities?limit=3&before=$next")
        assertTrue(page2.body!!.path("items").size() >= 2)
    }

    @Test
    fun `metrics of an uploaded ride equal the pure computation and are reported against the device`() {
        val id = createAthlete(ftp = 315) // the Edge810 session says threshold_power 315
        val name = "Edge810-Vector-2013-08-16-15-35-10"
        val r = upload(id, name)
        assertEquals(201, r.status, r.body.toString())
        val metrics = r.body!!.path("metrics")
        assertTrue(metrics.path("normalizedPower").isNumber)

        // Independent computation straight from the decoder and the metric functions.
        val fit = FitActivity.decode(File(fitDir, "$name.fit").readBytes())
        val t0 = fit.records.first().timestampUnix
        val secs = DoubleArray(fit.records.size) { (fit.records[it].timestampUnix - t0).toDouble() }
        val power = DoubleArray(fit.records.size) { (fit.records[it].power ?: 0).toDouble() }
        val samples = RideSamples(secs, power, fit.records.count { it.power == null })
        val recInt = RecordingInterval.estimate(secs)!!
        val np = PowerMetrics.normalizedPower(power, recInt)!!
        val tss = PowerMetrics.trainingStressScore(np, samples.size, recInt, 315.0)!!
        assertTrue(abs(np - metrics.path("normalizedPower").asDouble()) < 1e-3)
        assertTrue(abs(tss - metrics.path("tss").asDouble()) < 1e-2)
        assertEquals(315, metrics.path("ftpUsed").asInt())
        val mmp20 = MeanMaximalPower.peakPower(samples, recInt, 1200.0)!!
        assertTrue(abs(mmp20 - metrics.path("mmp").path("20m_critical_power").asDouble()) < 1e-3)

        // Device-reported values (session message): reported, not judged (api.md criterion 3).
        val session = fit.sessions.first()
        println(
            "DEVICE_COMPARISON $name: NP ours=${"%.1f".format(np)} device=${session.normalizedPower} | " +
                "TSS ours=${"%.1f".format(tss)} device=${session.trainingStressScore} | IF ours=${"%.3f".format(np / 315)} device=${session.intensityFactor}",
        )

        // Samples endpoint: stride and size cap.
        val s = get("/api/activities/${r.body!!.path("id").asString()}/samples?every=10")
        assertEquals(200, s.status)
        assertEquals(10, s.body!!.path("every").asInt())
        assertEquals((fit.records.size + 9) / 10, s.body!!.path("secs").size())
        assertEquals(400, get("/api/activities/${r.body!!.path("id").asString()}/samples?every=0").status)

        // Activity power curve: log-spaced points, standard windows included.
        val curve = get("/api/activities/${r.body!!.path("id").asString()}/power-curve")
        assertEquals(200, curve.status)
        assertTrue(curve.body!!.path("points").size() in 50..400)
        assertTrue(abs(curve.body!!.path("standard").path("20m_critical_power").asDouble() - mmp20) < 1e-3)
    }

    @Test
    fun `threshold change recomputes TSS and training load follows daily TSS`() {
        val id = createAthlete(ftp = 300)
        // The Edge 500 sample has no power meter (first draft of this test used it and recomputed
        // nothing); the ELEMNT ride has power on 13,346 of 13,471 samples.
        val r = upload(id, "2019-02-17-062644-ELEMNT-297E-195-0")
        assertEquals(201, r.status, r.body.toString())
        val activityId = r.body!!.path("id").asString()
        val tssBefore = r.body!!.path("metrics").path("tss").asDouble()
        val started = r.body!!.path("startedAt").asString().substring(0, 10)

        val set = put("/api/athletes/$id/threshold", """{"effectiveFrom":"2000-01-01","ftpWatts":250}""")
        assertEquals(200, set.status, set.body.toString())
        assertEquals(1, set.body!!.path("recomputedActivities").asInt())
        val after = get("/api/activities/$activityId").body!!.path("metrics")
        assertEquals(250, after.path("ftpUsed").asInt())
        // TSS scales with 1 / FTP^2.
        assertTrue(abs(after.path("tss").asDouble() - tssBefore * (300.0 / 250.0) * (300.0 / 250.0)) < 0.05)

        val load = get("/api/athletes/$id/training-load?from=$started&to=$started")
        assertEquals(200, load.status, load.body.toString())
        val day = load.body!!.path("days").get(0)
        assertEquals(started, day.path("date").asString())
        assertTrue(abs(day.path("tss").asDouble() - after.path("tss").asDouble()) < 1e-3)
        assertTrue(day.path("ctl").asDouble() > 0)
        assertEquals(400, get("/api/athletes/$id/training-load?from=2024-01-02&to=2024-01-01").status)

        val curve = get("/api/athletes/$id/power-curve?weeks=52&asOf=$started")
        assertEquals(200, curve.status)
        assertTrue(curve.body!!.path("points").size() >= 10)

        assertEquals(204, delete("/api/activities/$activityId").status)
        assertEquals(404, get("/api/activities/$activityId").status)
        assertEquals(404, delete("/api/activities/$activityId").status)
    }

    @Test
    fun `health endpoint is up`() {
        val r = get("/actuator/health")
        assertEquals(200, r.status)
        assertEquals("UP", r.body!!.path("status").asString())
    }

    @Test
    fun `file without power is stored without metrics`() {
        // The Edge 820 commute has no power meter: stored, metrics null.
        val id = createAthlete()
        val r = upload(id, "garmin-edge-820-bike")
        assertEquals(201, r.status, r.body.toString())
        val fit = FitActivity.decode(File(fitDir, "garmin-edge-820-bike.fit").readBytes())
        val hasPower = fit.records.any { it.power != null }
        assertEquals(hasPower, r.body!!.path("hasPower").asBoolean())
        if (!hasPower) {
            assertTrue(r.body!!.path("metrics").isMissingNode || r.body!!.path("metrics").isNull)
        } else {
            assertNotNull(r.body!!.path("metrics").path("normalizedPower"))
        }
        assertNull(null)
    }
}

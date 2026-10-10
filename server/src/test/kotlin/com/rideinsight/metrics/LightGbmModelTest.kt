package com.rideinsight.metrics

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/** The Kotlin tree walker must reproduce LightGBM's own predictions (fixtures from ml/ftp_export.py). */
class LightGbmModelTest {
    private val dir = File("src/test/resources/lgbm")
    private val mapper = JsonMapper.builder().build()

    private fun check(modelFile: String, casesFile: String) {
        val model = LightGbmModel.fromJson(File(dir, modelFile).readText())
        val cases = mapper.readTree(File(dir, casesFile))
        val names = ArrayList<String>()
        for (n in cases.path("feature_names")) names.add(n.asString())
        assertEquals(names, model.featureNames)
        var n = 0
        for (c in cases.path("cases")) {
            val features = HashMap<String, Double?>()
            for (name in names) {
                val v = c.path("features").path(name)
                features[name] = if (v.isNull || v.isMissingNode) null else v.asDouble()
            }
            val expected = c.path("prediction").asDouble()
            val actual = model.predict(features)
            assertTrue(abs(expected - actual) < 1e-6, "$modelFile case $n: expected $expected got $actual")
            n++
        }
        assertTrue(n > 0)
    }

    @Test
    fun `synthetic model with missing values matches python`() = check("tiny_model.json", "tiny_cases.json")

    @Test
    fun `ftp model matches python when exported`() {
        if (!File(dir, "ftp_model.json").exists()) return // exported only when stage 3 adopts a model
        check("ftp_model.json", "ftp_cases.json")
    }
}

package com.rideinsight.metrics

// [파일 역할] Kotlin 트리 순회 추론기(LightGbmModel)가 LightGBM(Python)의 예측을 1e-6 안에서 재현하는지
// 확인한다. 모델 JSON과 기대 예측은 ml/의 ftp_export.py가 만든 고정 파일(src/test/resources/lgbm/)이다.
// [왜 중요한가] 3단계 규칙은 모델을 서버에 넣는다면 Python 예측과 1e-6 안에서 같은지 테스트하라고 정했다.
// 결측값(NaN)이 갈 방향처럼 틀리기 쉬운 부분을 합성 모델로 미리 확인해 둔다.

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

/** The Kotlin tree walker must reproduce LightGBM's own predictions (fixtures from ml/ftp_export.py). */
class LightGbmModelTest {
    private val dir = File("src/test/resources/lgbm")
    private val mapper = JsonMapper.builder().build()

    // 모델과 사례 파일을 읽어 특징 이름 순서가 같은지 확인하고, 사례마다 특징(null은 결측)으로 예측해
    // 기대값과 1e-6 안에서 비교한다. 사례가 0개면 실패로 처리해 빈 파일로 통과하는 일을 막는다.
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

    // 결측값이 섞인 작은 합성 모델(tiny_model.json)의 Python 예측과 비교.
    @Test
    fun `synthetic model with missing values matches python`() = check("tiny_model.json", "tiny_cases.json")

    // missing_type "Zero"(zero_as_missing으로 학습한 모델) 노드는 LightGBM이 값 0과 결측을 default_left 쪽으로
    // 보내는데, 이 추론기는 그 규칙을 구현하지 않았다. 틀린 예측을 조용히 내지 않도록 읽는 단계에서 거절하는지
    // 확인한다. 뿌리 아래 노드에만 있어도 잡아야 한다.
    @Test
    fun `models with missing_type Zero are rejected instead of mispredicted`() {
        fun node(missingType: String, left: String, right: String) =
            """{"split_index":0,"split_feature":0,"threshold":1.5,"decision_type":"<=","default_left":true,""" +
                """"missing_type":"$missingType","left_child":$left,"right_child":$right}"""
        fun model(root: String) =
            """{"objective":"regression","feature_names":["a"],"tree_info":[{"tree_index":0,"tree_structure":$root}]}"""
        val leaf = """{"leaf_index":0,"leaf_value":1.0}"""
        // The same tree with "None" loads and predicts.
        assertEquals(1.0, LightGbmModel.fromJson(model(node("None", leaf, leaf))).predict(doubleArrayOf(0.0)))
        val e = assertFailsWith<IllegalArgumentException> {
            LightGbmModel.fromJson(model(node("None", node("Zero", leaf, leaf), leaf)))
        }
        assertTrue(e.message!!.contains("Zero"), e.message)
    }

    // 실제 FTP 모델 대조. 3단계에서 모델이 채택되지 않아 ftp_model.json이 없으므로, 지금은 아무것도 검사하지
    // 않고 통과한다.
    @Test
    fun `ftp model matches python when exported`() {
        if (!File(dir, "ftp_model.json").exists()) return // exported only when stage 3 adopts a model
        check("ftp_model.json", "ftp_cases.json")
    }
}

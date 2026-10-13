package com.rideinsight.metrics

// [파일 역할] LightGBM 회귀 모델을 Python의 `booster.dump_model()` JSON 그대로 읽어, 트리를 직접 순회해
// 예측하는 Kotlin 추론기다.
// [설계 이유] 계획(docs/plan.md)에는 ONNX로 내보내 JVM에서 추론한다고 적었지만, 3단계 규칙에서 'LightGBM이
// 채택되면 dump_model JSON을 Kotlin에서 직접 순회하고(새 의존성 없음) Python 예측과 1e-6 안에서 같은지
// 테스트한다'로 정했다(docs/experiments.md 3단계 서비스 반영). 네이티브 라이브러리 없이 JSON
// 파서(Jackson)만으로 돈다.
// [현재 상태] 3단계에서 모델이 채택되지 않아(H5 판정 불가) 서비스는 이 클래스를 쓰지 않는다. 결측값이 섞인
// 합성 모델로 Python 예측 100개와 대조하는 테스트(LightGbmModelTest, 기대값은 ml/의 ftp_export.py가 만듦)만
// 준비해 두었다.

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Inference for a LightGBM regression model exported with `booster.dump_model()` (JSON). The trees
 * are walked directly, so no native dependency is needed; the Python predictions are checked
 * against this class in the tests (docs/experiments.md, 3단계 서비스 반영).
 *
 * Decision rule per node: numerical split `feature <= threshold` goes left. A missing value
 * (NaN) follows `default_left` when the node's `missing_type` is "NaN"; otherwise LightGBM treats
 * a missing value as zero.
 */
// 생성자는 private이고 fromJson으로만 만든다. featureNames의 순서가 입력 배열의 열 순서다.
class LightGbmModel private constructor(
    val featureNames: List<String>,
    private val trees: List<JsonNode>,
) {
    // 특징 이름 → 값 맵으로 예측한다. 맵에 없거나 null인 특징은 NaN(결측)으로 넣어 트리의 결측 규칙을 따르게
    // 한다.
    fun predict(features: Map<String, Double?>): Double {
        val x = DoubleArray(featureNames.size) { i -> features[featureNames[i]] ?: Double.NaN }
        return predict(x)
    }

    // 회귀 모델의 예측은 모든 트리에서 도달한 잎 값의 합이다. 특징 수가 모델과 다르면 거절한다.
    fun predict(x: DoubleArray): Double {
        require(x.size == featureNames.size) { "expected ${featureNames.size} features, got ${x.size}" }
        var sum = 0.0
        for (tree in trees) sum += leafValue(tree.path("tree_structure"), x)
        return sum
    }

    // 트리 하나를 뿌리부터 leaf_value가 있는 잎까지 내려간다. 분할 노드는 split_feature(특징 번호)와
    // threshold를 가지며, 값 ≤ threshold면 왼쪽(left_child)으로 간다. 재귀 대신 반복문이라 깊은 트리에서도
    // 스택을 쓰지 않는다.
    private fun leafValue(start: JsonNode, x: DoubleArray): Double {
        var node = start
        while (!node.has("leaf_value")) {
            val feature = node.path("split_feature").asInt()
            val threshold = node.path("threshold").asDouble()
            val value = x[feature]
            // 결측(NaN): 노드의 missing_type이 "NaN"이면 학습 때 정해진 기본 방향(default_left)으로 보내고,
            // 그 밖이면 결측을 0으로 보고 비교한다(클래스 설명의 규칙). 값이 있으면 숫자 분할("<=")만
            // 지원하고, 다른 decision_type(범주 분할 등)이 나오면 require로 거절한다.
            val goLeft = if (value.isNaN()) {
                if ((node.path("missing_type").asString(null) ?: "None") == "NaN") node.path("default_left").asBoolean(true) else 0.0 <= threshold
            } else {
                val decision = node.path("decision_type").asString(null) ?: "<="
                require(decision == "<=") { "unsupported decision type $decision" }
                value <= threshold
            }
            node = if (goLeft) node.path("left_child") else node.path("right_child")
        }
        return node.path("leaf_value").asDouble()
    }

    companion object {
        // dump_model() JSON을 읽어 objective가 regression으로 시작하는지 확인하고, feature_names와
        // tree_info(트리 목록)를 꺼낸다. 트리가 없으면 거절한다. 트리는 JSON 노드 그대로 들고 있다가 예측 때
        // 순회한다.
        fun fromJson(json: String): LightGbmModel {
            val root = JsonMapper.builder().build().readTree(json)
            val objective = root.path("objective").asString(null) ?: ""
            require(objective.startsWith("regression")) { "only regression models are supported" }
            val names = ArrayList<String>()
            for (n in root.path("feature_names")) names.add(n.asString())
            val trees = ArrayList<JsonNode>()
            for (t in root.path("tree_info")) trees.add(t)
            require(trees.isNotEmpty()) { "model has no trees" }
            return LightGbmModel(names, trees)
        }
    }
}

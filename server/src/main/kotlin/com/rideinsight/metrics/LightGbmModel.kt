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
 * `booster.dump_model()`(JSON)로 내보낸 LightGBM 회귀 모델의 추론기. 트리를 직접 순회하므로 네이티브
 * 의존성이 필요 없고, 테스트에서 Python 예측과 이 클래스의 결과를 대조한다(docs/experiments.md, 3단계
 * 서비스 반영).
 *
 * 노드마다의 판정 규칙: 숫자 분할 `feature <= threshold`이면 왼쪽으로 간다. 결측값(NaN)은 노드의
 * `missing_type`이 "NaN"이면 `default_left`를 따르고, "None"이면 LightGBM처럼 결측을 0으로 본다.
 * `missing_type`이 "Zero"인 모델(`zero_as_missing`으로 학습)은 0과 NaN을 모두 `default_left`로 보내는데,
 * 이 규칙은 구현하지 않았으므로 [fromJson]이 그런 모델을 거절한다.
 *
 * 생성자는 private이고 fromJson으로만 만든다. featureNames의 순서가 입력 배열의 열 순서다.
 */
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
            // "None"이면 결측을 0으로 보고 비교한다(클래스 설명의 규칙). "Zero" 노드는 fromJson이 미리
            // 거절하므로 여기까지 오지 않는다. 값이 있으면 숫자 분할("<=")만 지원하고, 다른 decision_type(범주
            // 분할 등)이 나오면 require로 거절한다.
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
        // tree_info(트리 목록)를 꺼낸다. 트리가 없거나 missing_type "Zero" 노드가 있으면 거절한다. 트리는 JSON
        // 노드 그대로 들고 있다가 예측 때 순회한다.
        fun fromJson(json: String): LightGbmModel {
            val root = JsonMapper.builder().build().readTree(json)
            val objective = root.path("objective").asString(null) ?: ""
            require(objective.startsWith("regression")) { "only regression models are supported" }
            val names = ArrayList<String>()
            for (n in root.path("feature_names")) names.add(n.asString())
            val trees = ArrayList<JsonNode>()
            for (t in root.path("tree_info")) trees.add(t)
            require(trees.isNotEmpty()) { "model has no trees" }
            for (t in trees) rejectZeroMissingType(t.path("tree_structure"))
            return LightGbmModel(names, trees)
        }

        // missing_type "Zero"(zero_as_missing으로 학습한 모델)에서 LightGBM은 값 0(절댓값 1e-35 이하)과 결측을
        // 모두 default_left 방향으로 보낸다. leafValue는 이 규칙을 구현하지 않아 그런 모델에서는 틀린 예측을
        // 조용히 낸다. 지금 이 클래스를 쓰는 서비스는 없고 ftp_export.py가 만드는 모델에도 이 값은 나오지
        // 않지만, 나중에 그런 모델을 넣으면 읽는 단계에서 바로 실패하도록 모든 노드를 확인한다(2026-10-13).
        private fun rejectZeroMissingType(start: JsonNode) {
            val stack = ArrayDeque<JsonNode>()
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (node.has("leaf_value")) continue
                require(node.path("missing_type").asString(null) != "Zero") {
                    "missing_type Zero (zero_as_missing) is not supported"
                }
                stack.addLast(node.path("left_child"))
                stack.addLast(node.path("right_child"))
            }
        }
    }
}

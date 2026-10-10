package com.rideinsight.metrics

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
class LightGbmModel private constructor(
    val featureNames: List<String>,
    private val trees: List<JsonNode>,
) {
    fun predict(features: Map<String, Double?>): Double {
        val x = DoubleArray(featureNames.size) { i -> features[featureNames[i]] ?: Double.NaN }
        return predict(x)
    }

    fun predict(x: DoubleArray): Double {
        require(x.size == featureNames.size) { "expected ${featureNames.size} features, got ${x.size}" }
        var sum = 0.0
        for (tree in trees) sum += leafValue(tree.path("tree_structure"), x)
        return sum
    }

    private fun leafValue(start: JsonNode, x: DoubleArray): Double {
        var node = start
        while (!node.has("leaf_value")) {
            val feature = node.path("split_feature").asInt()
            val threshold = node.path("threshold").asDouble()
            val value = x[feature]
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

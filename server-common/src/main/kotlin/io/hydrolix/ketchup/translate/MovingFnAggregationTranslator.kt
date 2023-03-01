package io.hydrolix.ketchup.translate

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.MovingFunctionAggregation
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.query.IRQuery
import org.slf4j.Logger

object MovingFnAggregationTranslator : AggregationTranslator<MovingFunctionAggregation> {
    private val movingFnParseR = """MovingFunctions\.(.*?)\(values\)""".toRegex()

    override fun translate(
        path: List<String>,
        mapping: DBMapping,
        agg: MovingFunctionAggregation,
        children: Map<String, Aggregation>,
        parent: IRQuery,
        translator: DefaultTranslator,
        logger: Logger
    ): IRQuery {
        val script = agg.script() ?: error("moving_fn.script is required")
        val bucketPath = agg.bucketsPath() ?: error("moving_fn.buckets_path is required")
        val window = agg.window() ?: error("moving_fn.window is required")
        if (agg.shift() != null) { throw Untranslatable(UntranslatableReason.Other, "moving_fn.shift is not supported (yet?)") }

        val scriptMatch = movingFnParseR.matchEntire(script) ?: throw Untranslatable(UntranslatableReason.Other, "Unsupported moving_fn script: $script")
        val fn = scriptMatch.groupValues[1]

        val siblingPath = path.dropLast(1) + bucketPath.single()
        val info = parent.aggProjs[siblingPath] ?: error("moving_fn's path $bucketPath couldn't be resolved to an already-translated sibling aggregation")

        val get = GetField<Any>(info.alias, ValueType.ANY)
        val (expr, name) = when (fn) {
            "max" -> MovingMax(get, window) to "moving_max_${info.alias}"
            "min" -> MovingMin(get, window) to "moving_min_${info.alias}"
            "sum" -> MovingSum(get, window) to "moving_sum_${info.alias}"
            "unweightedAvg" -> MovingAvg(get, window) to "moving_avg_${info.alias}"
            else -> throw Untranslatable(UntranslatableReason.Other, "Unsupported moving_fn function $fn")
        }

        return parent.withAgg(expr, agg._toAggregation(), path, name).first
    }
}
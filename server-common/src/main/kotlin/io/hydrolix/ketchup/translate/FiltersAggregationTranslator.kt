package io.hydrolix.ketchup.translate

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.Buckets
import co.elastic.clients.elasticsearch._types.aggregations.FiltersAggregation
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.query.IRQuery
import org.slf4j.Logger

object FiltersAggregationTranslator : AggregationTranslator<FiltersAggregation> {
    override fun translate(
        path: List<String>,
        mapping: DBMapping,
        agg: FiltersAggregation,
        children: Map<String, Aggregation>,
        parent: IRQuery,
        translator: DefaultTranslator,
        logger: Logger
    ): IRQuery {
        val filters = agg.filters() ?: error("`filters` property of filters aggregation was null?!")

        val tests = mutableListOf<Expr<Boolean>>()
        val results = mutableListOf<Expr<Any>>()

        @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
        val case = when (filters._kind()) {
            Buckets.Kind.Array -> {
                if (agg.otherBucket() == true || agg.otherBucketKey() != null) {
                    throw Untranslatable(UntranslatableReason.Other, "Don't know how to do non-keyed Filters with an other bucket")
                }
                for ((i, q) in filters.array().withIndex()) {
                    tests += DefaultTranslator.handleQuery(q, mapping)
                    results += IntLiteral(i)
                }
                Case(tests, results)
            }
            Buckets.Kind.Keyed -> {
                for ((key, query) in filters.keyed()) {
                    tests += DefaultTranslator.handleQuery(query, mapping)
                    results += StringLiteral(key)
                }

                if (agg.otherBucket() == true) {
                    tests += BooleanLiteral.True
                    results += StringLiteral(agg.otherBucketKey() ?: "_other_")
                }

                Case(tests, results)
            }
        }

        var out = parent
            .withGroupBy(case, "case_result", BooleanLiteral.True)
            .withAgg(CountAll, agg._toAggregation(), path, "count").first

        children.forEach { (key, kid) ->
            out = translator.handleAgg(mapping, path + key, kid, out)
        }

        return out
    }
}
package io.hydrolix.ketchup.translate

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.TermsAggregation
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.query.IRQuery
import org.slf4j.Logger

/**
 * TODO this should return:
 *  * one or more group-by expressions
 *  * one or more projections
 *  * zero or more aggregates
 *  * a sort
 */
object TermsTranslator : AggregationTranslator<TermsAggregation> {
    override fun translate(
        path: List<String>,
        mapping: DBMapping,
        agg: TermsAggregation,
        children: Map<String, Aggregation>,
        parent: IRQuery,
        translator: DefaultTranslator,
        logger: Logger
    ): IRQuery {
        if (agg.include() != null || agg.exclude() != null) {
            throw Untranslatable(UntranslatableReason.Other, "terms aggregation had unsupported `include`/`exclude`")
        }
        if (agg.script() != null) {
            throw Untranslatable(UntranslatableReason.Other, "terms aggregation had unsupported `script`")
        }

        val keyField = GetField<Any>(agg.field()!!, ValueType.ANY)

        val k = agg.size() ?: 10
        var out = parent
            .withGroupBy(keyField, agg.field()!!, In(keyField, TopK(keyField, k)))
            .withAgg(CountAll, agg._toAggregation(), path, "count").first

        children.forEach { (key, kid) ->
            out = translator.handleAgg(mapping, path + key, kid, out)
        }

        return translator.processOrderBy(agg.order(), keyField, out, path)
    }
}
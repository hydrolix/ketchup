package io.hydrolix.ketchup.translate

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.AggregationVariant
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.query.IRQuery
import org.slf4j.Logger

interface AggregationTranslator<V : AggregationVariant> {
    fun translate(
        path: List<String>,
        mapping: DBMapping,
        agg: V,
        children: Map<String, Aggregation>,
        parent: IRQuery,
        translator: DefaultTranslator,
        logger: Logger
    ): IRQuery
}

package io.hydrolix.ketchup

import co.elastic.clients.elasticsearch.core.SearchRequest
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import org.junit.jupiter.api.Test
import java.util.*

class UnsupportedAggsTest : TestUtils() {
    @Test
    fun `test date_histogram+cardinality nesting`() {
        val req = load<SearchRequest>("/unsupportedAggs/dateHistogramCardinality.json")

        val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (req to "hits")))

        val sql = tres[0].query.render("  ", RenderContext.forTable(listOf("hits"), "hits"), logger)

        println(sql)
    }

    @Test
    fun `test filters`() {
        val req = load<SearchRequest>("/unsupportedAggs/filters/request.json")

        val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (req to "hits")))

        val sql = tres[0].query.render("  ", RenderContext.forTable(listOf("hits"), "hits"), logger)

        println(sql)
    }

    @Test
    fun `test moving_fn`() {
        val req = load<SearchRequest>("/unsupportedAggs/movingFn/request.json")

        val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (req to "hits")))

        val sql = tres[0].query.render("  ", RenderContext.forTable(listOf("hits"), "hits"), logger)

        println(sql)
    }

}
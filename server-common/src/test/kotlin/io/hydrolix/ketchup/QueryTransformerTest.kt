package io.hydrolix.ketchup

import io.hydrolix.ketchup.model.opensearch.KibanaSearchRequest
import io.hydrolix.ketchup.model.transform.QueryTransformer
import io.hydrolix.ketchup.model.transform.TimeRangeZero
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class QueryTransformerTest : TestUtils() {
    @Test
    fun `check all search test cases are unmolested by the Identity transformer`() {
        check(load("/scenarios/ecommerce/queries/search1/request.json"))
        check(load("/scenarios/ecommerce/queries/search2/request.json"))
        check(load("/scenarios/ecommerce/queries/search3/request.json"))
        check(load("/scenarios/ecommerce/queries/search4/request.json"))
        check(load("/scenarios/ecommerce/queries/search5/request.json"))
        check(load("/scenarios/ecommerce/queries/search6/request.json"))
        check(load("/scenarios/ecommerce/queries/search7/request.json"))
        check(load("/scenarios/ecommerce/queries/search8/request.json"))
        check(load("/scenarios/ecommerce/queries/search9/request.json"))
        check(load("/scenarios/ecommerce/queries/search10/request.json"))
        check(load("/scenarios/ecommerce/queries/search11/request.json"))
        check(load("/scenarios/flights/queries/search1/request.json"))
        check(load("/scenarios/flights/queries/search2/request.json"))
        check(load("/scenarios/flights/queries/search3/request.json"))
        check(load("/scenarios/flights/queries/search4/request.json"))
        check(load("/scenarios/flights/queries/search5/request.json"))
        check(load("/scenarios/flights/queries/search6/request.json"))
        check(load("/scenarios/flights/queries/search7/request.json"))
        check(load("/scenarios/flights/queries/search8/request.json"))
        check(load("/scenarios/flights/queries/search9/request.json"))
        check(load("/scenarios/flights/queries/search10/request.json"))
        check(load("/scenarios/flights/queries/search11/request.json"))
        check(load("/scenarios/flights/queries/search12/request.json"))
        check(load("/scenarios/flights/queries/search13/request.json"))
        check(load("/scenarios/flights/queries/search14/request.json"))
        check(load("/scenarios/flights/queries/search15/request.json"))
        check(load("/scenarios/flights/queries/search16/request.json"))
        check(load("/scenarios/flights/queries/search17/request.json"))
        check(load("/scenarios/logs/queries/search1/request.json"))
        check(load("/scenarios/logs/queries/search2/request.json"))
        check(load("/scenarios/logs/queries/search3/request.json"))
        check(load("/scenarios/logs/queries/search4/request.json"))
        check(load("/scenarios/logs/queries/search5/request.json"))
        check(load("/scenarios/logs/queries/search6/request.json"))
        check(load("/scenarios/logs/queries/search7/request.json"))
        check(load("/scenarios/logs/queries/search8/request.json"))
        check(load("/scenarios/logs/queries/search9/request.json"))
        check(load("/scenarios/logs/queries/search10/request.json"))
    }

    private fun check(req: KibanaSearchRequest) {
        val origQuery = req.params.body.query()!!
        val xQuery = QueryTransformer.transform(origQuery)

        assertEquals(origQuery.toString(), xQuery.toString()) // .toString() because of https://github.com/elastic/elasticsearch-java/issues/101
    }

    @Test
    fun `test stuff`() {
        val req = load<KibanaSearchRequest>("/scenarios/ecommerce/queries/search2/request.json")

        val inQuery = req.params.body.query()!!

        val xQuery = QueryTransformer.transform(inQuery, listOf(TimeRangeZero))

        assertNotEquals(inQuery, xQuery) // TODO be more specific about checking that only the timestamps have changed
    }
}

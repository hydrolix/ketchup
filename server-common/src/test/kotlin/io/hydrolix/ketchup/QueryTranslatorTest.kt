package io.hydrolix.ketchup

import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql
import io.hydrolix.ketchup.model.expr.GetAllFields
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.opensearch.BatchRequest
import io.hydrolix.ketchup.model.opensearch.KibanaSearchRequest
import io.hydrolix.ketchup.model.opensearch.SearchRequestContent
import io.hydrolix.ketchup.model.query.IRQuery
import io.hydrolix.ketchup.model.query.IRQueryKey
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.*

class QueryTranslatorTest : TestUtils() {
    @Test fun `flight search1`() = req(load("/scenarios/flights/queries/search1/request.json"))
    @Test fun `flight search2`() = req(load("/scenarios/flights/queries/search2/request.json"))
    @Disabled("geotile grid aggregation")
    @Test fun `flight search3`() = req(load("/scenarios/flights/queries/search3/request.json"))
    @Disabled("top_hits aggregation")
    @Test fun `flight search4`() = req(load("/scenarios/flights/queries/search4/request.json"))
    @Test fun `flight search5`() = req(load("/scenarios/flights/queries/search5/request.json"))
    @Test fun `flight search6`() = req(load("/scenarios/flights/queries/search6/request.json"))
    @Test fun `flight search7`() = req(load("/scenarios/flights/queries/search7/request.json"))
    @Test fun `flight search8`() = req(load("/scenarios/flights/queries/search8/request.json"))
    @Test fun `flight search9`() = req(load("/scenarios/flights/queries/search9/request.json"))
    @Test fun `flight search10`() = req(load("/scenarios/flights/queries/search10/request.json"))
    @Test fun `flight search11`() = req(load("/scenarios/flights/queries/search11/request.json"))
    @Test fun `flight search12`() = req(load("/scenarios/flights/queries/search12/request.json"))
    @Test fun `flight search13`() = req(load("/scenarios/flights/queries/search13/request.json"))
    @Test fun `flight search14`() = req(load("/scenarios/flights/queries/search14/request.json"))
    @Test fun `flight search15`() = req(load("/scenarios/flights/queries/search15/request.json"))
    @Test fun `flight search16`() = req(load("/scenarios/flights/queries/search16/request.json"))
    @Disabled("histogram aggregation")
    @Test fun `flight search17`() = req(load("/scenarios/flights/queries/search17/request.json"))

    @Test fun `logs search1`() = req(load("/scenarios/logs/queries/search1/request.json"))
    @Test fun `logs search2`() = req(load("/scenarios/logs/queries/search2/request.json"))
    @Test fun `logs search3`() = req(load("/scenarios/logs/queries/search3/request.json"))
    @Test fun `logs search4`() = req(load("/scenarios/logs/queries/search4/request.json"))
    @Disabled("terms with script parameter")
    @Test fun `logs search5`() = req(load("/scenarios/logs/queries/search5/request.json"))
    @Test fun `logs search6`() = req(load("/scenarios/logs/queries/search6/request.json"))
    @Test fun `logs search7`() = req(load("/scenarios/logs/queries/search7/request.json"))
    @Disabled("composite aggregation")
    @Test fun `logs search8`() = req(load("/scenarios/logs/queries/search8/request.json"))
    @Disabled("geotile grid aggregation")
    @Test fun `logs search9`() = req(load("/scenarios/logs/queries/search9/request.json"))
    @Test fun `logs search10`() = req(load("/scenarios/logs/queries/search10/request.json"))

    @Test fun `ecommerce search1`() = req(load("/scenarios/ecommerce/queries/search1/request.json"))
    @Test fun `ecommerce search2`() = req(load("/scenarios/ecommerce/queries/search2/request.json"))
    @Test fun `ecommerce search3`() = req(load("/scenarios/ecommerce/queries/search3/request.json"))
    @Test fun `ecommerce search4`() = req(load("/scenarios/ecommerce/queries/search4/request.json"))
    @Test fun `ecommerce search5`() = req(load("/scenarios/ecommerce/queries/search5/request.json"))
    @Test fun `ecommerce search6`() = req(load("/scenarios/ecommerce/queries/search6/request.json"))
    @Test fun `ecommerce search7`() = req(load("/scenarios/ecommerce/queries/search7/request.json"))
    @Test fun `ecommerce search8`() = req(load("/scenarios/ecommerce/queries/search8/request.json"))
    @Test fun `ecommerce search9`() = req(load("/scenarios/ecommerce/queries/search9/request.json"))
    @Test fun `ecommerce search10`() = req(load("/scenarios/ecommerce/queries/search10/request.json"))
    @Disabled("geotile grid aggregation")
    @Test fun `ecommerce search11`() = req(load("/scenarios/ecommerce/queries/search11/request.json"))

    @Test fun `random batch1`() = breq("/scenarios/random/queries/batch1/request.json")
    @Test fun `random batch2`() = breq("/scenarios/random/queries/batch2/request.json")

    private fun req(req: KibanaSearchRequest) {
        val res = translator.translate(linkedMapOf(UUID.randomUUID() to (req.params.body to req.params.index)))

        for (r in res) {
            println(r.query.render("  ", RenderContext.forTable(listOf("tmp"), "tmp"), logger))
            println("    ${r.query.aggProjs}")
            println("    ${r.query.projections}")
        }
    }

    private fun reqSRC(req: SearchRequestContent) {
        val res = translator.translate(linkedMapOf(UUID.randomUUID() to (req.body to "hits")))

        for (r in res) {
            println(r.query.render("  ", RenderContext.forTable(listOf("tmp"), "tmp"), logger))
            println("    ${r.query.aggProjs}")
            println("    ${r.query.projections}")
        }
    }

    private fun breq(path: String) {
        val breq = load<BatchRequest>(path)
        for (req in breq.batch) {
            req(req.request)
        }
    }

    @Test
    fun `nested + between`() {
        val queryString = "(host_header:userprivacyrights* OR host:userprivacyrights*) AND (envoy_ingress.status:[400 TO 599] OR status:[400 TO 599])"
        val parsed = FastparseKql.parseKql(queryString).orNull()!!
        println(parsed)
        val q = IRQuery(IRQueryKey("TODO_table", emptyList(), emptyList(), parsed), linkedMapOf(GetAllFields to "*"), emptyMap())
        println(q.render("  ", RenderContext.forTable(listOf("TODO_index"), "TODO_table"), logger))
    }

    @Test
    fun `simple discover batch`() {
        val breq = load<BatchRequest>("/scenarios/random/queries/batch6/request.json")

        val tuples = breq.batch.map { UUID.randomUUID() to (it.request.params.body to it.request.params.index) }

        for (res in translator.translate(linkedMapOf(*tuples.toTypedArray()))) {
            println(res.query)
            println()
        }
    }
}

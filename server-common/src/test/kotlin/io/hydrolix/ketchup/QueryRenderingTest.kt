package io.hydrolix.ketchup

import co.elastic.clients.elasticsearch.core.SearchRequest
import io.hydrolix.ketchup.model.config.ElasticMetadata
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class QueryRenderingTest : TestUtils() {
    @Test
    fun `check nested fields use catch_all or not as appropriate`() {
        val metas = findStuff("elasticMetadata").filter { !it.fail }.map { case ->
            case.open().use { stream ->
                JSON.objectMapper.readValue(stream, ElasticMetadata::class.java)
            }
        }

        val req = SearchRequest.Builder()
            .query { qb -> qb.bool { bb ->
                bb.must { mb ->
                    mb.queryString { qsb ->
                        qsb.query("foo.foo2.foo3:hello AND bar.bar2.bar3:world")
                    }
                }

            }}.build()

        val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (req to "hits")))

        val q = tres[0].query

        val sql = q.render("  ", RenderContext(listOf("hits"), "hits", elasticMetadatas = metas), logger)

        assertTrue(sql.contains("catch_all['foo_foo2_foo3']"), "field not marked as top-level goes to catch_all")
        assertTrue(sql.contains("bar['bar2_bar3']"), "field marked as top-level is used instead of catch_all")

        println(sql)
    }
}
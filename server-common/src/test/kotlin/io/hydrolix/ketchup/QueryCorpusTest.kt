package io.hydrolix.ketchup

import arrow.core.Either
import co.elastic.clients.elasticsearch.core.SearchRequest
import com.fasterxml.jackson.core.type.TypeReference
import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql
import io.hydrolix.ketchup.model.config.ElasticMetadata
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import io.hydrolix.ketchup.translate.Untranslatable
import io.hydrolix.ketchup.translate.UntranslatableReason
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.*

class QueryCorpusTest : TestUtils() {
    @Test
    fun `try parsing KQL samples`() {
        findStuff("kql").filter { !it.fail }.forEach { case ->
            println(case)

            val kqls = case.open().use { stream ->
                JSON.objectMapper.readValue(stream, object : TypeReference<List<String>>() { })
            }

            for ((i, kql) in kqls.withIndex()) {
                when (val res = FastparseKql.parseKql(kql)) {
                    is Either.Left<*> -> fail("${case.path} ${i+1}: Couldn't parse $kql: $res")
                    is Either.Right<*> -> println("${case.path} ${i+1}: ${res.value}")
                }
            }
        }
    }

    private fun report(
        messages: MutableMap<Pair<TestCase, UntranslatableReason>, Int>,
        kqlErrors: Set<Pair<TestCase, String>>
    ) {
        val messagesByCount = messages.toList().sortedByDescending { it.second }.map { it.second to it.first }
        val msg = messagesByCount.joinToString("\n  ") { (count, message) ->
            "$count: $message"
        }
        logger.info("Untranslatable reasons:\n  $msg\ntotal: ${messagesByCount.sumOf { it.first }}")
        logger.info("KQL errors (${kqlErrors.size}):\n  ${kqlErrors.joinToString("\n  ")}")
    }

    private fun handle(
        metas: List<ElasticMetadata>,
        case: TestCase,
        req: SearchRequest?,
        i: Int,
        line: String,
        messages: MutableMap<Pair<TestCase, UntranslatableReason>, Int>,
        kqlParseErrors: MutableSet<Pair<TestCase, String>>
    ) {
        try {
            val resps = translator.translate(linkedMapOf(UUID.randomUUID() to (req!! to "hits")))
            for (resp in resps) {
                if (resp.query.aggProjs.isNotEmpty()) {
                    val sql = resp.query.render("  ", RenderContext(listOf("hits"), "hits", elasticMetadatas = metas), logger)
                    logger.info("Query #${i+1} SQL:\n$sql")
                }
                if (resp.query.aggProjs.any { it.key.size != 1 }) {
                    logger.debug("Query #${i+1} had nested aggregations: $req")
                }
            }
        } catch (e: Untranslatable) {
            logger.info("Untranslatable query #${i+1} (${e.message}; $line")
            messages[case to e.reason] = messages.getValue(case to e.reason) + 1
            if (e.reason == UntranslatableReason.KqlParse) {
                kqlParseErrors += case to e.info!!
            }
        } catch (e: Exception) {
            throw RuntimeException("Query translation failure: ${i+1} $line\n  $req", e)
        }
    }
}
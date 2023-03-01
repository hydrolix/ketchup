package io.hydrolix.ketchup.opensearch

import arrow.core.left
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import com.fasterxml.jackson.core.type.TypeReference
import io.hydrolix.ketchup.TestUtils
import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql
import io.hydrolix.ketchup.model.expr.GetField
import io.hydrolix.ketchup.model.expr.GetSomeFields
import io.hydrolix.ketchup.model.kql.FieldMatchPred
import io.hydrolix.ketchup.model.kql.OneField
import io.hydrolix.ketchup.model.kql.SelectedFields
import io.hydrolix.ketchup.model.opensearch.SearchRequestContent
import io.hydrolix.ketchup.translate.Untranslatable
import io.hydrolix.ketchup.util.JSON
import org.apache.lucene.queryparser.flexible.precedence.PrecedenceQueryParser
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*

class MiscQueryArchaeologyTest : TestUtils() {
    /**
     * TODO this doesn't test anything, it just prints to stdout. We should put some non-trivial test queries in
     *  testdata/private and have this check that it finds what's expected
     */
    @Test
    fun findNestedFieldRoots() {
        val fields = mutableMapOf<String, Int>().withDefault { 0 }

        findStuff("searchRequestContent").filter { !it.fail }.forEach { case ->
            case.open().use { stream ->
                processCorpus<SearchRequestContent>(stream) { _, _, req ->
                    try {
                        val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (req!!.body to req.index)))

                        val q = tres.first().query

                        q.visitExprs<GetField<*>> {
                            fields[it.fieldName] = fields.getValue(it.fieldName) + 1
                        }

                        q.visitExprs<GetSomeFields> {
                            for (f in it.fieldNames) {
                                fields[f] = fields.getValue(f) + 1
                            }
                        }

                        q.visitExprs<FieldMatchPred> {
                            when (val t = it.target) {
                                is OneField -> {
                                    fields[t.fieldName] = fields.getValue(t.fieldName) + 1
                                }
                                is SelectedFields -> {
                                    for (f in t.fieldNames) {
                                        fields[f] = fields.getValue(f) + 1
                                    }
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Untranslatable) {
                        logger.debug("Skipping untranslatable query")
                    }
                }
            }
        }

        val sorted = fields.toList().sortedByDescending { it.second }

        println(sorted.joinToString("\n") { (name, count) ->
            "$name: $count"
        })
    }

    /**
     * TODO this doesn't test anything, it just prints to stdout; we should add some actual KQL test cases in
     *  testdata/public and verify that they're parsed successfully, or allow-listed as known failures.
     */
    @Test
    fun collectLuceneSuccessesAndFailures() {
        val qp = PrecedenceQueryParser().apply {
            allowLeadingWildcard = true
        }

        val successes = mutableMapOf<Pair<String, org.apache.lucene.search.Query>, Int>().withDefault { 0 }
        val failures = mutableMapOf<String, Exception>()

        findStuff("searchRequestContent").filter { !it.fail }.forEach { case ->
            case.open().use { stream ->
                processCorpus<SearchRequestContent>(stream) { i, _, req ->
                    req?.body?.query()?.also {
                        visitQueryStrings(it) { q ->
                            try {
                                val query = qp.parse(q, "foo")

                                successes[q to query] = successes.getValue(q to query) + 1

                                logger.debug("Line $i: Lucene parsed $q to $query")
                            } catch (e: Exception) {
                                failures[q] = e

                                logger.debug("Line $i: Lucene couldn't parse $q", e)
                            }
                        }
                    }
                }
            }
        }

        logger.info("# Successes: ${successes.size} unique queries, ${successes.values.sum()} usages")
        logger.info("# Failures: ${failures.size}")

        logger.info("Successes JSON: ${JSON.objectMapper.writeValueAsString(successes.keys.map { it.first })}")
        logger.info("Failures JSON: ${JSON.objectMapper.writeValueAsString(failures.keys)}")
    }

    /**
     * Visits an Elastic [Query] and calls [f] on any KQL/Lucene query strings found within it or any of its descendants
     */
    private fun visitQueryStrings(query: Query, f: (String) -> Unit) {
        when (query._kind()) {
            Query.Kind.QueryString -> f(query.queryString().query())
            Query.Kind.SimpleQueryString -> f(query.simpleQueryString().query())
            Query.Kind.ConstantScore -> visitQueryStrings(query.constantScore().filter(), f)
            Query.Kind.Bool -> {
                val b = query.bool()
                b.filter().forEach { visitQueryStrings(it, f) }
                b.should().forEach { visitQueryStrings(it, f) }
                b.must().forEach { visitQueryStrings(it, f) }
                b.mustNot().forEach { visitQueryStrings(it, f) }
            }
            else -> {
                // TODO can any other kind of query be recursive?
            }
        }
    }

    @Test
    @Disabled("This only really makes sense with out-of-tree content")
    fun `try our parser on Lucene successes`() {
        val fails = mutableListOf<String>()
        File("../successes.json").inputStream().use { stream ->
            val strings = JSON.objectMapper.readValue(stream, object : TypeReference<Array<String>>() { })
            for ((i, s) in strings.withIndex()) {
                val res = FastparseKql.parseKql(s)
                if (res.isRight()) {
                    logger.debug("Line $i: Fastparse successfully parsed $s to ${res.orNull()}")
                } else {
                    fails += s
                    logger.debug("Line $i: Fastparse couldn't parse:\n  $s\n  ${res.left()}")
                }
            }
        }
        println("All failed queries as JSON: ${JSON.objectMapper.writeValueAsString(fails)}")
    }
}

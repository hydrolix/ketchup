package io.hydrolix.ketchup.cli

import arrow.core.Either
import co.elastic.clients.elasticsearch._types.query_dsl.QueryStringQuery
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.JsonData
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.contains
import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.config.ElasticMetadata
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.opensearch.KibanaSearchRequest
import io.hydrolix.ketchup.model.opensearch.SearchRequestContent
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import io.hydrolix.ketchup.server.ProxyConfig
import io.hydrolix.ketchup.server.ServerJSON
import io.hydrolix.ketchup.translate.DefaultTranslator
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.multiple
import kotlinx.cli.optional
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.system.exitProcess

object CliQueryTranslator {
    private val logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic
    fun main(args: Array<String>) {
        val parser = ArgParser("query-translator")
        val metaPaths by parser.option(ArgType.String, shortName = "e", fullName = "metadataFile", description = "Elastic Metadata document path(s)").multiple()
        val dbMappingPaths by parser.option(ArgType.String, shortName = "d", fullName = "dbMappingFile", description = "DBMapping document path(s)").multiple()
        val queryString by parser.option(ArgType.String, shortName = "q", description = "KQL/Lucene query string")
        val timeFieldArg by parser.option(ArgType.String, shortName = "t", fullName = "timestampField", description = "Timestamp field name")
        val idFieldArg by parser.option(ArgType.String, fullName = "idField", description = "ID field name")
        val indexArg by parser.option(ArgType.String, shortName = "i", fullName = "index", description = "Elastic index name or cluster:name")
        val minTime by parser.option(ArgType.String, shortName = "min", description = "Minimum time (inclusive)")
        val maxTime by parser.option(ArgType.String, shortName = "max", description = "Maximum time (inclusive)")
        val tableArg by parser.option(ArgType.String, fullName = "table", description = "Table name")
        val inputPath by parser.argument(ArgType.String, description = "Input file (use `-` for stdin)").optional()
        parser.parse(args)

        val config = ProxyConfig(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            dbMappingPaths.flatMap { stream -> Path(stream).inputStream().use { ServerJSON.objectMapper.readValue(it, DBMapping.ListOfThese) } },
            metaPaths.map { stream -> Path(stream).inputStream().use { ServerJSON.objectMapper.readValue(it, ElasticMetadata::class.java) } },
            logger
        )

        if (minTime != null || maxTime != null) {
            if (timeFieldArg == null) {
                logger.error("timestampField must be specified if minTime and/or maxTime is specified")
                exitProcess(2)
            }
        }

        val (mIndex, req) = if (queryString != null) {
            try {
                when (val res = FastparseKql.parseKql(queryString)) {
                    is Either.Left<*> -> {
                        logger.error("Couldn't parse KQL/Lucene query string: {}", res.value)
                        exitProcess(3)
                    }
                    is Either.Right<*> -> {
                        // TODO this actually discards the parsed expression but there's no clean way to reuse it

                        val qsq = QueryStringQuery.of {
                            it.query(queryString)
                        }

                        val timeQ = if (minTime != null && maxTime != null) {
                            RangeQuery.of {
                                it.gte(JsonData.of(minTime))
                                    .lte(JsonData.of(maxTime))
                                    .format("strict_date_optional_time")
                                    .field(timeFieldArg!!)
                            }
                        } else null

                        (indexArg to SearchRequest.of { srb ->
                            srb.query { qb ->
                                qb.bool {
                                    it.must(listOfNotNull(qsq._toQuery(), timeQ?._toQuery()))
                                }
                            }
                        })
                    }
                }
            } catch (e: Exception) {
                logger.error("Couldn't translate KQL/Lucene query string", e)
                exitProcess(3)
            }
        } else {
            val input = (if (inputPath == "-" || inputPath == null) System.`in` else Path(inputPath!!).inputStream()).use {
                it.readAllBytes().decodeToString()
            }
            val obj = ServerJSON.objectMapper.readValue(input, ObjectNode::class.java)

            if (obj.contains("params")) {
                val src = ServerJSON.objectMapper.convertValue(obj, KibanaSearchRequest::class.java)
                src.params.index to src.params.body
            } else if (obj.contains("body")) {
                val src = ServerJSON.objectMapper.convertValue(obj, SearchRequestContent::class.java)
                src.index to src.body
            } else {
                indexArg to ServerJSON.objectMapper.convertValue(obj, SearchRequest::class.java)
            }
        }

        val mappings = config.dbMappingsByIndex[mIndex].orEmpty()

        val mapping = if (mappings.isEmpty()) {
            val index = mIndex ?: run {
                logger.error("Couldn't identify target index from input document; use --index/-i")
                exitProcess(2)
            }

            val table = tableArg ?: run {
                logger.error("Couldn't identify target table from configuration; use --table")
                exitProcess(2)
            }

            val idField = idFieldArg ?: run {
                logger.error("Couldn't identify ID field from configuration; use --idField")
                exitProcess(2)
            }

            DBMapping(
                index,
                emptyList(),
                UUID.randomUUID(),
                table,
                null,
                null,
                idField
            )
        } else if (mappings.size > 1) {
            logger.warn("Multiple DBMappings for mIndex $mIndex; picking the first arbitrarily: ${dbMappingPaths.first()}")
            mappings.first()
        } else {
            mappings.first()
        }

        val translator = DefaultTranslator(config.copy(dbMappings = listOf(mapping)))

        val tres = try {
            translator.translate(linkedMapOf(UUID.randomUUID() to (req to mapping.indexPattern)))
        } catch (e: Exception) {
            logger.error("Untranslatable query", e)
            exitProcess(4)
        }

        val rc = RenderContext(
            listOf(mapping.indexPattern),
            mapping.clickHouseTableName,
            elasticMetadatas = config.elasticMetadatas
        )

        for (res in tres) {
            val sql = res.query.render("  ", rc, logger)
            println(sql)
        }
    }
}
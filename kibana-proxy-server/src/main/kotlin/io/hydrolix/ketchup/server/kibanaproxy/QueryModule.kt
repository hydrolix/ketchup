package io.hydrolix.ketchup.server.kibanaproxy

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import com.clickhouse.client.config.ClickHouseDefaults
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.model.*
import io.hydrolix.ketchup.model.config.ClickhouseConnection
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.config.ElasticVendor
import io.hydrolix.ketchup.model.config.KibanaConnection
import io.hydrolix.ketchup.model.expr.GetField
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.opensearch.*
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import io.hydrolix.ketchup.model.translate.TranslationResult
import io.hydrolix.ketchup.server.*
import io.hydrolix.ketchup.translate.DefaultTranslator
import io.hydrolix.ketchup.util.JSON
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.util.logging.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yandex.clickhouse.ClickHouseDataSource
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.sql.JDBCType
import java.sql.Types
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.DeflaterOutputStream
import javax.sql.DataSource

/**
 * Handles `data`, `opensearch' (`/es`) and `bsearch` query endpoints
 */
@Suppress("unused")
fun Application.queryModule() {
    val logger = environment.log

    Class.forName("ru.yandex.clickhouse.ClickHouseDriver")

    val config = ProxyConfig.load("ketchup.ketchup.query-module", environment.config, logger)

    val kibanaClient = HttpClient.newBuilder()
        .cookieHandler(KibanaProxyMain.cookieManager)
        .build()

    logger.info("Query module initializing: clickhouses are ${config.clickHouses}; dbMappings are ${config.dbMappings}")

    val defaultKibana = config.kibanas.values.singleOrNull { it.default } ?: error("There must be exactly one Kibana Connection with default=true")

    val translator = DefaultTranslator(config)

    val clickhouseDataSources = mutableMapOf<UUID, DataSource>()

    for ((id, chc) in config.clickHouses) {
        val user = config.credentials[id]?.username
        val pass = config.credentials[id]?.credential

        val ds = ClickHouseDataSource(chc.url, Properties().apply {
            user?.also { put(ClickHouseDefaults.USER, it) }
            pass?.also { put(ClickHouseDefaults.PASSWORD, it) }
        })

        clickhouseDataSources[id] = ds
    }

    // TODO restore this when we feel like it
    //  install(LearningPlugin)

    val batchRespContentType = ContentType.parse("application/x-ndjson")

    routing {
        // Kibana
        post("/internal/search/*") {
            // TODO are /opensearch and /es all there is?
            val bytes: ByteArray = call.receive()

            val searchReq = try {
                JSON.objectMapper.readValue(bytes, KibanaSearchRequest::class.java)
            } catch (e: Exception) {
                logger.error("Couldn't parse search request", e)
                throw e
            }

            val tres = try {
                translator.translate(linkedMapOf(UUID.randomUUID() to (searchReq.params.body to searchReq.params.index)))
            } catch (e: Throwable) {
                logger.warn("Couldn't translate search request $searchReq", e)
                emptyList()
            }

            if (tres.isEmpty()) {
                val mapping = identifyDispatchMapping(
                    config.dbMappingsByIndex,
                    config.dbMappingsById,
                    NamedIndexPattern(searchReq.params.body.index().single()), // TODO handle multivalues here
                    logger
                )

                if (mapping != null) {
                    val kibana = config.kibanas[mapping.kibanaConnectionId] ?: error("Unknown Kibana #${mapping.kibanaConnectionId}")
                    passthrough(
                        kibana.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?")),
                        kibana.vendor,
                        bytes,
                        logger,
                        kibanaClient
                    )
                } else {
                    // TODO is there some way to opt out of this request and let the passthrough module handle it?
                    passthrough(
                        defaultKibana.url.resolve(call.request.path()), defaultKibana.vendor,
                        bytes,
                        logger,
                        kibanaClient
                    )
                }
            } else {
                // TODO if there are multiple results, figure out how to reassemble them into a single response
                require(tres.size == 1) { "TODO Query translation returned multiple responses" }

                try {
                    val resp = query(
                        listOf(searchReq.params.index),
                        clickhouseDataSources,
                        tres[0], config.mappingsByTable,
                        logger, 1
                    )

                    call.respondOutputStream(contentType = ContentType.Application.Json, status = HttpStatusCode.OK) {
                        val bytes = JSON.objectMapper.writeValueAsBytes(resp)
                        write(bytes)
                    }
                } catch (e: UnsupportedQuery) {
                    logger.warn("Unsupported query, falling back to passthrough", e)
                    passthrough(
                        defaultKibana.url.resolve(call.request.path()), defaultKibana.vendor,
                        bytes,
                        logger,
                        kibanaClient
                    )
                }
            }
        }

        // Kibana
        post("/internal/bsearch") {
            val requestBytes: ByteArray = call.receive()

            val batchReq = JSON.objectMapper.readValue(requestBytes, BatchRequest::class.java)

            val indices = batchReq.batch.flatMap { it.request.params.body.index() + it.request.params.index }.toSet()
            val indexMappings = indices.associateWith {
                identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, NamedIndexPattern(it), logger)
            }

            val tres = try {
                val reqs = batchReq.batch.map {
                    UUID.randomUUID() to (it.request.params.body to it.request.params.index) // TODO do we care about [BatchSingleRequest.options]?
                }

                translator.translate(linkedMapOf(*reqs.toTypedArray()))
            } catch (e: Throwable) {
                logger.warn("Request couldn't be translated; will passthrough anyway", e)
                null
            }

            if (tres == null) {
                val ks = mutableSetOf<KibanaConnection>()
                val cs = mutableSetOf<ClickhouseConnection>()

                for (req in batchReq.batch) {
                    val mapping = identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, NamedIndexPattern(req.request.params.index), logger)

                    if (mapping != null) {
                        ks += config.kibanas[mapping.kibanaConnectionId] ?: error("Unknown Kibana #${mapping.kibanaConnectionId}")
                        cs += config.clickHouses[mapping.clickHouseConnectionId] ?: error("Unknown Clickhouse #${mapping.clickHouseConnectionId}")

                        // TODO each request in the batch can be for a different index, so if we're selectively dispatching parts of a request to Clickhouse, we need to reassemble the responses later
                        logger.info("TODO Batch Request should be handled by clickhouse #${mapping.clickHouseConnectionId}, table ${mapping.clickHouseTableName}")
                    }
                }

                require (ks.size == 1) { "Couldn't identify a single unique Kibana connection for Batch request" }

                dispatch(ks.first(), logger, requestBytes, kibanaClient)
            } else {
                if (tres.size != batchReq.batch.size) {
                    // TODO do a mix & match instead of opting out completely
                    // TODO do a mix & match instead of opting out completely
                    // TODO do a mix & match instead of opting out completely
                    // TODO do a mix & match instead of opting out completely

                    logger.warn("${batchReq.batch.size} queries received, ${tres.size} translated :/")

                    dispatch(defaultKibana, logger, requestBytes, kibanaClient)
                } else {
                    logger.info("Every Elastic query (${tres.size}) was translated!")

                    val compress = call.request.queryParameters["compress"]?.toBooleanStrict() ?: false

                    withContext(Dispatchers.IO) {
                        call.respondOutputStream(batchRespContentType, HttpStatusCode.OK) {
                            for ((i, res) in tres.withIndex()) {
                                val resp = query(listOf(batchReq.batch.first().request.params.index), clickhouseDataSources, res, config.mappingsByTable, logger, i)

                                val respBytes = JSON.objectMapper.writeValueAsBytes(BatchSingleResponse(i, resp))

                                logger.info("Response #${i+1} from ClickHouse is ${respBytes.size} bytes")

                                if (compress) {
                                    val baos = ByteArrayOutputStream(16384)
                                    DeflaterOutputStream(baos).use { it.write(respBytes) }
                                    write(Base64.getEncoder().encode(baos.toByteArray()))
                                } else {
                                    write(respBytes)
                                }

                                write('\n'.code)
                            }
                        }
                    }
                }
            }
        }

        // Kibana
        post("/api/metrics/vis/data") {
            val bytes: ByteArray = call.receive()

            val dataRequest = JSON.objectMapper.readValue(bytes, DataRequest::class.java)
            val cs = mutableSetOf<ClickhouseConnection>()
            val ks = mutableSetOf<KibanaConnection>()

            for (panel in dataRequest.panels) {
                val index = panel.indexPattern
                val mapping = identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, index, logger)

                if (mapping != null) {
                    logger.info("TODO Data Request should be handled by clickhouse #${mapping.clickHouseConnectionId}, table ${mapping.clickHouseTableName}")
                    // TODO each panel can be for a different index, so if we're selectively dispatching parts of a request to Clickhouse, we need to reassemble the responses later
                    ks += config.kibanas[mapping.kibanaConnectionId] ?: error("Unknown Kibana #${mapping.kibanaConnectionId}")
                    cs += config.clickHouses[mapping.clickHouseConnectionId] ?: error("Unknown Clickhouse #${mapping.clickHouseConnectionId}")
                }
            }

            // TODO go back to this when vis requests are actually handled
            //  require (ks.size == 1) { "Couldn't identify a single unique Kibana connection for Vis request" }

            dispatch(defaultKibana, logger, bytes, kibanaClient)
        }
    }
}

private fun query(
    indices: List<String>,
    clickhouseDataSources: MutableMap<UUID, DataSource>,
    res: TranslationResult,
    mappingsByTable: Map<Pair<UUID, String>, DBMapping>,
    logger: Logger,
    i: Int
): KibanaSearchResponse {
    val ds = clickhouseDataSources[res.clickhouseConnectionId]
        ?: error("No data source for clickhouse #${res.clickhouseConnectionId}")
    val mapping = mappingsByTable[res.clickhouseConnectionId to res.query.key.table]
        ?: error("No DBMapping for ${res.clickhouseConnectionId to res.query.key.table}")
    val sql = res.query.render(indent = "  ", ctx = RenderContext.forTable(indices, res.query.key.table), logger)
    logger.info("Query #${i + 1}: $sql")

    val start = System.currentTimeMillis()

    // TODO stream this instead of collecting it in memory!

    val sourceRows = mutableListOf<Map<String, Any>>()
    val projRows = mutableListOf<Map<String, Any>>()
    val fieldAliases = res.query.projections.filterKeys { it is GetField<*> }.values.toSet()
    val nonFieldAliases = res.query.projections.filterKeys { it !is GetField<*> }.values.toSet()

    try {
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val md = rs.metaData
                    val allAliases = List(md.columnCount) { md.getColumnName(it + 1) }

                    while (rs.next()) {
                        val sourceRow = mutableMapOf<String, Any>()
                        val projRow = mutableMapOf<String, Any>()

                        for (alias in allAliases) {
                            if (alias != "*") {
                                // TODO the "*" probably shouldn't be in the projections?
                                val cno = rs.findColumn(alias)
                                val fieldValue = when (val ct = md.getColumnType(cno)) {
                                    in setOf(
                                        Types.VARCHAR,
                                        Types.CHAR,
                                        Types.LONGVARCHAR,
                                        Types.NCHAR,
                                        Types.NVARCHAR,
                                        Types.LONGNVARCHAR
                                    ) -> rs.getString(alias)

                                    Types.BOOLEAN -> rs.getBoolean(alias)
                                    in setOf(
                                        Types.FLOAT,
                                        Types.DECIMAL,
                                        Types.DOUBLE,
                                        Types.REAL
                                    ) -> rs.getDouble(alias) // TODO BigDecimal sometimes?
                                    in setOf(
                                        Types.INTEGER,
                                        Types.BIGINT,
                                        Types.SMALLINT,
                                        Types.TINYINT,
                                        Types.NUMERIC
                                    ) -> rs.getLong(alias)

                                    in setOf(
                                        Types.TIMESTAMP,
                                        Types.TIMESTAMP_WITH_TIMEZONE,
                                        Types.TIME_WITH_TIMEZONE
                                    ) -> rs.getTimestamp(alias)?.let { it.toInstant() }

                                    Types.DATE -> rs.getDate(alias)?.toLocalDate()
                                        ?.let { DateTimeFormatter.ISO_DATE.format(it) }

                                    Types.NULL -> null
                                    else -> {
                                        logger.info("$alias is SQL type ${JDBCType.valueOf(ct)}")
                                        rs.getObject(alias)
                                    }
                                }

                                fieldValue?.also {
                                    if (nonFieldAliases.contains(alias)) {
                                        projRow[alias] = it
                                    } else {
                                        // Assume everything not mentioned in a non-GetField projection is in the original doc
                                        sourceRow[alias] = it
                                    }
                                }
                            }
                        }
                        sourceRows += sourceRow
                        projRows += projRow
                    }
                }
            }
        }
    } catch (e: Exception) {
        logger.error("Clickhouse query exception", e)
        throw e
    }

    val took = System.currentTimeMillis() - start

    // TODO the request can ask for aggs and rows at the same time (if size > 0 IIRC) but we're either/or here
    // TODO the request can ask for aggs and rows at the same time (if size > 0 IIRC) but we're either/or here
    // TODO the request can ask for aggs and rows at the same time (if size > 0 IIRC) but we're either/or here
    // TODO the request can ask for aggs and rows at the same time (if size > 0 IIRC) but we're either/or here
    val resp = try {
        if (res.query.isAggOnly) {
            // Aggregation query
            aggResponse(projRows, res, took, logger)
        } else {
            // Non-aggregation query
            nonAggResponse(
                sourceRows.toList(),
                projRows.toList(),
                mapping.idFieldName,
                res,
                took,
                logger
            )
        }
    } catch (e: Exception) {
        logger.error("Response handling exception", e)
        throw e
    }

    return resp
}

private fun nonAggResponse(
    sourceRows: List<Map<String, Any>>,
    projRows: List<Map<String, Any>>,
    idField: String,
    res: TranslationResult,
    took: Long,
    logger: Logger
): KibanaSearchResponse {
    val hitsOut = sourceRows.zip(projRows).map { (sourceRow, projRow) ->
        val id = (sourceRow + projRow)[idField] ?: error("No $idField value in row")
        SearchHit(
            index = res.index,
            id = id.toString(), // TODO not always .toString()
            version = 1,
            score = null,
            source = JSON.objectMapper.valueToTree(sourceRow),
            fields = JSON.objectMapper.valueToTree(projRow),
            sort = null,
            ignoredFieldValues = null
        )
    }

    return KibanaSearchResponse(
        null,
        false,
        false,
        SearchRawResponse(
            took.toInt(),
            false,
            SearchResponseShardInfo(1, 1, 0, 0),
            SearchResponseHits(
                sourceRows.size,
                null,
                hitsOut
            ),
            aggregations = null,
            terminatedEarly = false
        ),
        total = 1, // TODO is this right?
        loaded = 1, // TODO is this right?
        isRestored = false

    )
}

private fun aggResponse(
    rows: List<Map<String, Any>>,
    res: TranslationResult,
    took: Long,
    logger: Logger
): KibanaSearchResponse {
    val aggProjs = res.query.aggProjs

    val (roots, nonRoots) = aggProjs.keys.partition { it.size == 1 }

    val out = mutableMapOf<String, ObjectNode>()

    for (path in roots) {
        val aggInfo = aggProjs[path] ?: error("Unknown aggregation path $path")

        val rootOut = JSON.objectMapper.nodeFactory.objectNode()
        out[path[0]] = rootOut

        val agg = aggInfo.agg ?: error("info.agg == null is only expected in the `aggs: {}, size: 0` special case")

        when (agg._kind()) {
            Aggregation.Kind.DateHistogram -> {
                val groupByExpr = res.query.key.groupBys.singleOrNull() ?: error("DateHistogram aggregation had more than one GROUP BY")
                val groupByAlias = res.query.projections[groupByExpr.first] ?: error("GROUP BY projection not found for expression $groupByExpr")

                val buckets = JSON.objectMapper.nodeFactory.arrayNode()
                rootOut.replace("buckets", buckets)

                for (row in rows) {
                    val time = row[groupByAlias] as Instant
                    val agg = row[aggInfo.alias] as Long

                    val outRow = buckets.objectNode()
                        .put("key_as_string", time.toString()) // TODO something better than .toString
                        .put("key", time.toEpochMilli())
                        .put("doc_count", agg)

                    // TODO refactor this stanza to be general
                    val kidPaths = nonRoots.filter { it.take(path.size) == path }

                    for (kidPath in kidPaths) {
                        val agg = aggProjs[kidPath] ?: error("No aggregation with path $kidPath")
                        val kidRow = outRow.objectNode().replace("value", row[agg.alias]?.let { agg.valueType.toJSON(it) })
                        outRow.replace(kidPath.last(), kidRow)
                    }

                    buckets.add(outRow)
                }
            }
            Aggregation.Kind.Cardinality -> {
                if (res.query.key.groupBys.isEmpty()) {
                    // one row
                    val row = rows.singleOrNull() ?: error("Cardinality without GROUP BY had other than one row?")
                    rootOut.replace("value", aggInfo.valueType.toJSON(row[aggInfo.alias]))
                } else {
                    // multiple rows
                    TODO()
                }
            }
            else -> error("Unsupported aggregation: ${agg._kind()}")
        }
    }

    return KibanaSearchResponse(
        null,
        false,
        false,
        SearchRawResponse(
            took.toInt(),
            false,
            SearchResponseShardInfo(1, 1, 0, 0),
            SearchResponseHits(
                rows.size, // TODO this should actually be the number of documents processed by the aggregations, not the results
                null,
                emptyList()
            ),
            aggregations = out,
            terminatedEarly = false
        ),
        total = 1, // TODO is this right?
        loaded = 1, // TODO is this right?
        isRestored = false
    )
}

fun String?.prefixIfNotEmpty(s: String): String? {
    return if (this.isNullOrEmpty()) this else s + this
}

private suspend fun PipelineContext<Unit, ApplicationCall>.dispatch(
    k: KibanaConnection,
    logger: Logger,
    bytes: ByteArray,
    kibanaClient: HttpClient
) {
    // TODO is there some way to opt out of this request and let the passthrough module handle it?
    passthrough(
        k.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?")),
        k.vendor,
        bytes,
        logger,
        kibanaClient
    )
}

private suspend fun PipelineContext<Unit, ApplicationCall>.passthrough(
    url: URI,
    ktype: ElasticVendor,
    bytes: ByteArray,
    logger: Logger,
    kibanaClient: HttpClient
) {
    val resp = dupe(HttpMethod.Post, url, ktype, bytes, logger, kibanaClient)

    val ct = resp.headers().firstValue("Content-Type").orElseThrow()

    call.respondBytes(
        bytes = resp.body(),
        contentType = ContentType.parse(ct),
        status = HttpStatusCode.fromValue(resp.statusCode())
    )
}

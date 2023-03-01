package io.hydrolix.ketchup.server.elasticproxy

import co.elastic.clients.elasticsearch._types.ShardStatistics
import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.aggregations.*
import co.elastic.clients.elasticsearch.async_search.SubmitRequest
import co.elastic.clients.elasticsearch.async_search.SubmitResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.core.search.Hit
import co.elastic.clients.elasticsearch.core.search.PointInTimeReference
import co.elastic.clients.elasticsearch.core.search.TotalHits
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation
import co.elastic.clients.json.JsonData
import com.clickhouse.client.config.ClickHouseDefaults
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.LongNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Expiry
import io.hydrolix.ketchup.model.*
import io.hydrolix.ketchup.model.config.ElasticVendor
import io.hydrolix.ketchup.model.config.ElasticsearchConnection
import io.hydrolix.ketchup.model.expr.Case
import io.hydrolix.ketchup.model.expr.GetField
import io.hydrolix.ketchup.model.expr.RenderContext
import io.hydrolix.ketchup.model.opensearch.*
import io.hydrolix.ketchup.model.query.AggInfo
import io.hydrolix.ketchup.model.query.IRQuery
import io.hydrolix.ketchup.model.translate.QueryRenderer.render
import io.hydrolix.ketchup.model.translate.QueryTranslator
import io.hydrolix.ketchup.model.translate.TranslationResult
import io.hydrolix.ketchup.server.*
import io.hydrolix.ketchup.translate.DefaultTranslator
import io.hydrolix.ketchup.util.JSON
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.util.logging.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.sql.JDBCType
import java.sql.Types
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern
import javax.sql.DataSource
import com.clickhouse.jdbc.ClickHouseDataSource as ModernDataSource
import ru.yandex.clickhouse.ClickHouseDataSource as LegacyDataSource

@Suppress("unused") // ktor loads the entry point using reflection based on application.conf
fun Application.queryModule() {
    val logger = environment.log

    val config = ProxyConfig.load("ketchup.elasticproxy.query-module", environment.config, logger)

    val kibanaClient = HttpClient.newBuilder()
        .cookieHandler(ElasticProxyMain.cookieManager)
        .build()

    logger.info("Query module initializing: clickhouses are ${config.clickHouses}; dbMappings are ${config.dbMappings}")

    val defaultElastic = config.elastics.values.singleOrNull { it.default } ?: error("There must be exactly one Elastic Connection with default=true")

    val translator = DefaultTranslator(config)

    val clickhouseDataSources = mutableMapOf<UUID, DataSource>()

    for ((id, chc) in config.clickHouses) {
        val user = config.credentials[id]?.username
        val pass = config.credentials[id]?.credential

        val props = Properties().apply {
            user?.also { put(ClickHouseDefaults.USER.key, it) }
            pass?.also { put(ClickHouseDefaults.PASSWORD.key, it) }
            for ((k, v) in chc.extraProperties) {
                put(k, v)
            }
        }

        val ds = if (chc.legacyDriver) {
            LegacyDataSource(chc.url, props)
        } else {
            ModernDataSource(chc.url, props)
        }

        clickhouseDataSources[id] = ds
    }

    val pitCache = Caffeine.newBuilder()
        .expireAfter(PointInTimeCacheExpiry)
        .build<String, PointInTimeCacheEntry>()

    val asyncSearchCache = Caffeine.newBuilder()
        .expireAfter(AsyncSearchCacheExpiry)
        .build<String, ObjectNode>()

    routing {
        post("/{indices}/_pit") {
            logger.debug("Received POST ${call.request.uri}")
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            val keepAlive = call.request.queryParameters["keep_alive"] ?: error("_pit request didn't specify a keep_alive time")
            val indices = call.parameters["indices"]!!.split(Pattern.compile(",\\s*"))

            passthrough(HttpMethod.Post, uri, ElasticVendor.Elastic, byteArrayOf(), logger, kibanaClient) { _, respBytes, _, code ->
                if (code.isSuccess()) {
                    val obj = JSON.objectMapper.readValue(respBytes, ObjectNode::class.java)
                    val id = obj["id"].asText()
                    pitCache.get(id) { id ->
                        PointInTimeCacheEntry(
                            indices,
                            PointInTimeReference.of {
                                it.keepAlive(Time.of { tb -> tb.time(keepAlive) }).id(id)
                            }
                        )
                    }
                }
            }
        }
        delete("/_pit") {
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(HttpMethod.Delete, uri, ElasticVendor.Elastic, call.receive(), logger, kibanaClient) { reqBytes, _, _, code ->
                if (code.isSuccess()) {
                    val obj = JSON.objectMapper.readValue(reqBytes, ObjectNode::class.java)
                    val id = obj["id"].asText()
                    pitCache.invalidate(id)
                }
            }
        }
        get("/{indices}/_search") {
            handleSearch(HttpMethod.Get, config, pathIndices(), translator, kibanaClient, defaultElastic, clickhouseDataSources, pitCache)
        }
        post("/{indices}/_search") {
            handleSearch(HttpMethod.Post, config, pathIndices(), translator, kibanaClient, defaultElastic, clickhouseDataSources, pitCache)
        }

        // TODO sadly ktor doesn't support optional path components very well, so these are separate routes
        get("/_search") {
            handleSearch(HttpMethod.Get, config, emptyList(), translator, kibanaClient, defaultElastic, clickhouseDataSources, pitCache)
        }
        post("/_search") {
            handleSearch(HttpMethod.Post, config, emptyList(), translator, kibanaClient, defaultElastic, clickhouseDataSources, pitCache)
        }

        get("/_async_search/{searchId}") {
            val searchId = call.parameters["searchId"] ?: error("Search ID is required")
            val cachedResp = asyncSearchCache.getIfPresent(searchId)

            logger.info("Async search #$searchId requested; we had $cachedResp")

            if (cachedResp == null) {
                call.respond(HttpStatusCode.NotFound, "No async search with ID $searchId")
            } else {
                call.respond(HttpStatusCode.OK, ServerJSON.objectMapper.writeValueAsBytes(cachedResp))
            }
        }
        post("/{indices}/_async_search") {
            handleAsyncSearch(HttpMethod.Post, config, pathIndices(), translator, kibanaClient, defaultElastic, clickhouseDataSources, asyncSearchCache)
        }
        post("/_async_search") {
            handleAsyncSearch(HttpMethod.Post, config, emptyList(), translator, kibanaClient, defaultElastic, clickhouseDataSources, asyncSearchCache)
        }

        // TODO dedupe these, they're all identical except for the HTTP method
        get("{path...}") {
            logger.debug("Received passthrough GET ${call.request.uri}")
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(HttpMethod.Get, uri, ElasticVendor.Elastic, call.receive(), logger, kibanaClient)
        }
        post("{path...}") {
            logger.debug("Received passthrough POST ${call.request.uri}")
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(HttpMethod.Post, uri, ElasticVendor.Elastic, call.receive(), logger, kibanaClient)
        }
        put("{path...}") {
            logger.debug("Received passthrough PUT ${call.request.uri}")
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(HttpMethod.Put, uri, ElasticVendor.Elastic, call.receive(), logger, kibanaClient)
        }
        delete("{path...}") {
            logger.debug("Received passthrough DELETE ${call.request.uri}")
            val uri = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(HttpMethod.Delete, uri, ElasticVendor.Elastic, call.receive(), logger, kibanaClient)
        }
    }
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleAsyncSearch(
    method: HttpMethod,
    config: ProxyConfig,
    pathIndices: List<String>,
    translator: DefaultTranslator,
    httpClient: HttpClient,
    defaultElastic: ElasticsearchConnection,
    clickhouseDataSources: MutableMap<UUID, DataSource>,
    asyncSearchCache: Cache<String, ObjectNode>
) {
    val reqBytes = call.receive<ByteArray>()

    val submitReq = ServerJSON.objectMapper.readValue(reqBytes, SubmitRequest::class.java)
    config.logger.info("Got async submit request: $submitReq")

    val searchIndices = submitReq.index().toSet() + pathIndices
    if (searchIndices.size > 1) {
        config.logger.warn("Multiple indices are eligible for this search; picking the first from $searchIndices")
    }

    val mapping = identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, NamedIndexPattern(searchIndices.first()), config.logger)
    if (mapping == null) {
        // TODO refactor so the query translator doesn't have to do its own mapping; it's good to do it here where
        //  we can passthrough
        config.logger.debug("No dispatch mapping for ${searchIndices.first()}; passing through to elasticsearch")
        val url = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
        try {
            withContext(Dispatchers.IO) {
                passthrough(method, url, defaultElastic.vendor, reqBytes, config.logger, httpClient) { _, respBytes, _, status ->
                    if (status.isSuccess()) {
                        val sresp = ServerJSON.objectMapper.readValue(respBytes, SubmitResponse::class.java)

                        config.logger.info("Elastic request ID: ${sresp.id()} $sresp")
                        // TODO put this in the cache
                    } else {
                        config.logger.warn("Elastic async response wasn't a success: $status ${respBytes.decodeToString()}")
                    }
                }
            }
        } catch (e: Exception) {
            config.logger.error("Async search passthrough failed", e)
        }
        return
    } else {
        try {
            val submitReqBytes = ServerJSON.objectMapper.writeValueAsBytes(submitReq)
            val searchReq = ServerJSON.objectMapper.readValue(submitReqBytes, SearchRequest::class.java)
            val tres = translator.translate(linkedMapOf(UUID.randomUUID() to (searchReq to searchIndices.first())))
            withContext(Dispatchers.IO) {
                val searchId = UUID.randomUUID().toString()

                val searchResp = query(config, searchIndices.toList(), submitReqBytes, clickhouseDataSources, tres.first(), 0)

                val searchRespObj = ServerJSON.objectMapper.valueToTree<ObjectNode>(searchResp)

                val submitResp = ServerJSON.objectMapper.nodeFactory.objectNode()
                    .set<ObjectNode>("id", TextNode.valueOf(searchId))
                    .set<ObjectNode>("is_partial", BooleanNode.FALSE)
                    .set<ObjectNode>("is_running", BooleanNode.FALSE)
                    .set<ObjectNode>("start_time_in_millis", LongNode.valueOf(1234L))
                    .set<ObjectNode>("expiration_time_in_millis", LongNode.valueOf(1234L))
                    .set<ObjectNode>("response", searchRespObj)
                    // TODO other stuff is probably required here

                asyncSearchCache.put(searchId, submitResp)

                call.response.headers.append("X-Elastic-Product", "Elasticsearch")
                call.respondBytes(contentType = ContentType.Application.Json, status = HttpStatusCode.OK) {
                    ServerJSON.objectMapper.writeValueAsBytes(submitResp)
                }
            }
        } catch (e: Exception) {
            config.logger.error("Async search intercept failed; request was ${reqBytes.decodeToString()}", e)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
}

object AsyncSearchCacheExpiry : Expiry<String, ObjectNode> {
    override fun expireAfterCreate(
        key: String,
        value: ObjectNode,
        currentTime: Long
    ): Long {
        val expirationTime = value["expiration_time_in_millis"].asLong()
        return System.currentTimeMillis() - expirationTime
    }

    override fun expireAfterUpdate(
        key: String,
        value: ObjectNode,
        currentTime: Long,
        currentDuration: Long
    ): Long {
        return System.currentTimeMillis() - value["expiration_time_in_millis"].asLong()
    }

    override fun expireAfterRead(
        key: String,
        value: ObjectNode,
        currentTime: Long,
        currentDuration: Long
    ): Long {
        return Long.MAX_VALUE
    }
}

data class PointInTimeCacheEntry(
    val indices: List<String>,
    val pitr: PointInTimeReference
)

object PointInTimeCacheExpiry : Expiry<String, PointInTimeCacheEntry> {
    private fun ttl(value: PointInTimeReference): Long {
        val time = value.keepAlive()?.time() ?: error("pit keepAlive was not a time")
        val (count, unit) = time.parseTime()
        return unit.toNanos(count.toLong())
    }

    override fun expireAfterCreate(key: String, value: PointInTimeCacheEntry, currentTime: Long): Long {
        return ttl(value.pitr)
    }

    override fun expireAfterUpdate(
        key: String,
        value: PointInTimeCacheEntry,
        currentTime: Long,
        currentDuration: Long
    ): Long {
        return ttl(value.pitr)
    }

    override fun expireAfterRead(
        key: String,
        value: PointInTimeCacheEntry,
        currentTime: Long,
        currentDuration: Long
    ): Long {
        return Long.MAX_VALUE
    }
}

private fun PipelineContext<Unit, ApplicationCall>.pathIndices(): List<String> {
    val indices = call.parameters["indices"]?.split(Pattern.compile(",\\s*")).orEmpty()

    return if (indices == listOf("*") || indices == listOf("_all")) emptyList() else indices
}

private suspend fun PipelineContext<Unit, ApplicationCall>.handleSearch(
    method: HttpMethod,
    config: ProxyConfig,
    indices: List<String>,
    translator: QueryTranslator,
    httpClient: HttpClient,
    defaultElastic: ElasticsearchConnection,
    clickhouseDataSources: MutableMap<UUID, DataSource>,
    pitCache: Cache<String, PointInTimeCacheEntry>,
) {
    val logger = config.logger

    logger.debug("Indices from URL: $indices")
    val reqBytes = call.receive<ByteArray>()
    logger.debug("Request bytes: ${reqBytes.size}")

    try {
        val req = ServerJSON.objectMapper.readValue(reqBytes, SearchRequest::class.java)
        logger.debug("Decoded SearchRequest: $req")

        val pitIndices = req.pit()?.let {
            val existing = pitCache.getIfPresent(it.id()) ?: error("Request referred to unknown or expired PIT #${it.id()}")
            // TODO update the PointInTimeReference with this request's keep-alive time, don't just refresh the original
            pitCache.put(it.id(), existing)
            existing.indices
        }

        val searchIndices = if (pitIndices != null) {
            if (indices.isNotEmpty()) logger.warn("Request contained both URL indices and PIT; using PIT")
            pitIndices
        } else {
            indices
        }

        if (searchIndices.size > 1) {
            logger.warn("Search requested multiple indices ($searchIndices), cowardly choosing the first")
        }

        val mapping = identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, NamedIndexPattern(searchIndices.first()), logger)
        if (mapping == null) {
            // TODO refactor so the query translator doesn't have to do its own mapping; it's good to do it here where
            //  we can passthrough
            logger.debug("No dispatch mapping for ${searchIndices.first()}; passing through to elasticsearch")
            val url = defaultElastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?"))
            passthrough(method, url, defaultElastic.vendor, reqBytes, logger, httpClient)
            return
        }

        val tres = try {
            translator.translate(linkedMapOf(UUID.randomUUID() to (req to searchIndices.first())))
        } catch (e: Throwable) {
            logger.warn("Couldn't translate search request $req", e)
            emptyList()
        }

        if (tres.isEmpty()) {
            val mapping = identifyDispatchMapping(
                config.dbMappingsByIndex,
                config.dbMappingsById,
                NamedIndexPattern(req.index().single()), // TODO handle multivalues here
                logger
            )

            if (mapping != null) {
                val elastic = config.elastics[mapping.elasticConnectionId] ?: error("Unknown Elastic #${mapping.elasticConnectionId}")
                passthrough(
                    HttpMethod.Post,
                    elastic.url.resolve(call.request.path() + call.request.queryString().prefixIfNotEmpty("?")),
                    elastic.vendor,
                    reqBytes,
                    logger,
                    httpClient
                )
            } else {
                // TODO is there some way to opt out of this request and let the passthrough module handle it?
                passthrough(
                    HttpMethod.Post,
                    defaultElastic.url.resolve(call.request.path()), defaultElastic.vendor,
                    reqBytes,
                    logger,
                    httpClient
                )
            }
        } else {
            // TODO if there are multiple results, figure out how to reassemble them into a single response
            require(tres.size == 1) { "TODO Query translation returned multiple responses" }

            try {
                val resp = query(
                    config,
                    searchIndices.toList(),
                    reqBytes,
                    clickhouseDataSources,
                    tres[0],
                    1
                )

                call.response.headers.append("X-Elastic-Product", "Elasticsearch")
                call.respondBytes(contentType = ContentType.Application.Json, status = HttpStatusCode.OK) {
                    JSON.objectMapper.writeValueAsBytes(resp)
                }
            } catch (e: UnsupportedQuery) {
                logger.warn("Unsupported query, falling back to passthrough", e)
                passthrough(
                    HttpMethod.Post,
                    defaultElastic.url.resolve(call.request.path()), defaultElastic.vendor,
                    reqBytes,
                    logger,
                    httpClient
                )
            }
        }
    } catch (e: Exception) {
        val badReq = reqBytes.decodeToString()
        logger.error("Malformed SearchRequest: $badReq", e) // TODO stop spewing badReq into the logs
        call.respond(HttpStatusCode.BadRequest, "Malformed SearchRequest")
    }
}

private fun query(
    config: ProxyConfig,
    indices: List<String>,
    reqBytes: ByteArray,
    clickhouseDataSources: MutableMap<UUID, DataSource>,
    res: TranslationResult,
    i: Int
): SearchResponse<Map<String, Any>> {
    val ds = clickhouseDataSources[res.clickhouseConnectionId]
        ?: error("No data source for clickhouse #${res.clickhouseConnectionId}")
    val mapping = config.mappingsByTable[res.clickhouseConnectionId to res.query.key.table]
        ?: error("No DBMapping for ${res.clickhouseConnectionId to res.query.key.table}")
    val sql = res.query.render(indent = "  ", ctx = RenderContext(indices, res.query.key.table, elasticMetadatas = config.elasticMetadatas), config.logger)
    config.logger.info("Query #${i + 1}: $sql")

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
                                    ) -> rs.getTimestamp(alias)?.toInstant()

                                    Types.DATE -> rs.getDate(alias)?.toLocalDate()
                                        ?.let { DateTimeFormatter.ISO_DATE.format(it) }

                                    Types.NULL -> null
                                    else -> {
                                        config.logger.info("$alias is SQL type ${JDBCType.valueOf(ct)}")
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
        config.logger.error("Clickhouse query exception; request was ${reqBytes.decodeToString()}", e)
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
            aggResponse(sourceRows, projRows, res, took, config.logger)
        } else {
            // Non-aggregation query
            nonAggResponse(
                sourceRows.toList(),
                projRows.toList(),
                mapping.idFieldName,
                res,
                took,
                config.logger
            )
        }
    } catch (e: Exception) {
        config.logger.error("Response handling exception; request was ${reqBytes.decodeToString()}", e)
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
): SearchResponse<Map<String, Any>> {
    val hitsOut = sourceRows.zip(projRows).map { (sourceRow, projRow) ->
        val id = (sourceRow + projRow)[idField] ?: error("No $idField value in row")
        Hit.of<Map<String, Any>> {
            it.index(res.index)
                .id(id.toString())
                .version(1L)
                .score(null)
                .source(sourceRow)
                .fields(projRow.mapValues { (_, v) ->
                    JsonData.of(JSON.objectMapper.writeValueAsString(v))
                })
                .sort(emptyList())
        }
    }

    return SearchResponse.of { builder ->
        builder.took(took)
            .timedOut(false)
            .shards(ShardStatistics.of {
                it.total(1)
                .successful(1)
                .failed(0)
                .skipped((0))
            })
            .hits {
                it.maxScore(null)
                  .total(TotalHits.of { thb ->
                    thb.value(sourceRows.size.toLong())
                    thb.relation(TotalHitsRelation.Eq)// TODO sometimes Gte?
                  })
                  .hits(hitsOut)
            }
            .terminatedEarly(false)
    }
}

val singleValueAggregationKinds: Map<Aggregation.Kind, (String, Double) -> AggregateVariant> = mapOf(
    Aggregation.Kind.Avg                     to { s, d -> AvgAggregate.of                     { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.Derivative              to { s, d -> DerivativeAggregate.of              { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.Max                     to { s, d -> MaxAggregate.of                     { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.MedianAbsoluteDeviation to { s, d -> MedianAbsoluteDeviationAggregate.of { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.Min                     to { s, d -> MinAggregate.of                     { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.Sum                     to { s, d -> SumAggregate.of                     { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.ValueCount              to { s, d -> ValueCountAggregate.of              { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.Cardinality             to { s, d -> CardinalityAggregate.of             { b -> b.value(d.toLong()) } },
    Aggregation.Kind.WeightedAvg             to { s, d -> WeightedAvgAggregate.of             { b -> b.value(d).valueAsString(s) } },
    Aggregation.Kind.MovingFn                to { s, d -> SimpleValueAggregate.of             { b -> b.value(d) } },
)

private val defaultDateTimeFormatZoned = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun populateMultiBucketRow(
    query: IRQuery,
    rows: List<Map<String, Any>>,
    path: List<String>,
    depth: Int
): Aggregate {
    val info = query.aggProjs[path] ?: error("No aggregation with path $path")

    val kids = query.aggProjs.mapNotNull { (aggPath, aggInfo) ->
        if (aggPath.size == path.size + 1 && aggPath.subList(0, path.size) == path) {
            aggPath[path.size] to aggInfo
        } else null
    }.toMap()

    val agg = info.agg ?: error("info.agg == null is only expected in the `aggs: {}, size: 0` special case")

    return when (agg._kind()) {
        Aggregation.Kind.DateHistogram -> {
            val dh = agg.dateHistogram()
            val zone = dh.timeZone()?.let { ZoneId.of(it) } ?: ZoneOffset.UTC.normalized()
            val fmt = dh.format()?.let { DateTimeFormatter.ofPattern(it) } ?: if (dh.timeZone() != null) defaultDateTimeFormatZoned else DateTimeFormatter.ISO_DATE_TIME

            val groupByExpr = query.key.groupBys[path.size - 1]
            val groupByAlias = query.projections[groupByExpr.first] ?: error("GROUP BY projection not found for expression $groupByExpr")

            val dhBuckets = rows.map { row ->
                val time = row[groupByAlias] as? Instant ?: error("Key column $groupByAlias not found in row")
                val count = row[info.alias] as? Long ?: error("Count column ${info.alias} not found in row")

                val localized = time.atZone(zone)

                DateHistogramBucket.of {
                    val dhbb = it.key(time.toEpochMilli())
                        .keyAsString(fmt.format(localized))
                        .docCount(count)

                    buildKids(kids, query, listOf(row), path, depth, dhbb) { builder, kidName, kidAgg ->
                        builder.aggregations(kidName, kidAgg)
                    }
                }
            }

            DateHistogramAggregate.of { dhab ->
                dhab.buckets {
                    it.array(dhBuckets)
                }
            }._toAggregate()
        }
        Aggregation.Kind.Terms -> {
            val groupByExpr = query.key.groupBys[path.size - 1]
            val groupByAlias = query.projections[groupByExpr.first] ?: error("GROUP BY projection not found for expression $groupByExpr")

            // TODO dedupe these
            // TODO dedupe these
            // TODO dedupe these
            // TODO dedupe these
            when (rows[0][groupByAlias]) {
                is Long -> {
                    val buckets = rows.mapIndexed { i, row ->
                        val key = row[groupByAlias] ?: error("No value in row $i for terms alias $groupByAlias")
                        val count = row[info.alias] as? Long ?: error("Count column ${info.alias} not found in row")
                        LongTermsBucket.of {
                            val bb = it.key(key.toString().toLong()) // TODO more type safety
                              .keyAsString(key.toString())
                              .docCount(count)

                            buildKids(kids, query, listOf(row), path, depth + 1, bb) { builder, kidName, kidAgg ->
                                builder.aggregations(kidName, kidAgg)
                            }
                        }

                    }
                    LongTermsAggregate.of { ltab ->
                        ltab.buckets(Buckets.of {
                            it.array(buckets)
                        })
                    }._toAggregate()
                }
                is Double -> {
                    val buckets = rows.mapIndexed { i, row ->
                        val key = row[groupByAlias] ?: error("No value in row $i for terms alias $groupByAlias")
                        val count = row[info.alias] as? Long ?: error("Count column ${info.alias} not found in row")

                        DoubleTermsBucket.of {
                            val bb = it.key(key.toString().toDouble()) // TODO more type safety
                              .keyAsString(key.toString())
                              .docCount(count)

                            buildKids(kids, query, listOf(row), path, depth + 1, bb) { builder, kidName, kidAgg ->
                                builder.aggregations(kidName, kidAgg)
                            }
                        }
                    }
                    DoubleTermsAggregate.of { dtab ->
                        dtab.buckets(Buckets.of {
                            it.array(buckets)
                        })
                    }._toAggregate()
                }
                is String -> {
                    val buckets = rows.mapIndexed { i, row ->
                        val key = row[groupByAlias] ?: error("No value in row $i for terms alias $groupByAlias")
                        val count = row[info.alias] as? Long ?: error("Count column ${info.alias} not found in row")

                        StringTermsBucket.of {
                            val bb = it.key(key.toString())
                              .docCount(count)

                            buildKids(kids, query, listOf(row), path, depth + 1, bb) { builder, kidName, kidAgg ->
                                builder.aggregations(kidName, kidAgg)
                            }
                        }
                    }
                    StringTermsAggregate.of { stab ->
                        stab.buckets(Buckets.of {
                            it.array(buckets)
                        })
                    }._toAggregate()
                }
                else -> error("Can't make Terms aggregate result from a ${rows[0][groupByAlias]?.javaClass}")
            }
        }
        Aggregation.Kind.Filters -> {
            val groupByExpr = query.key.groupBys[path.size - 1].first
            require (groupByExpr is Case<*>) {
                "TODO group-by for filters expression must be a Case"
            }
            val filters = agg.filters()

            val groupByAlias = query.projections[groupByExpr] ?: error("Can't get alias for GROUP BY expression $groupByExpr")

            // TODO dedupe these two stanzas
            // TODO dedupe these two stanzas
            // TODO dedupe these two stanzas
            // TODO dedupe these two stanzas
            if (filters.keyed() == false) {
                // TODO can we avoid two traversals here?
                val groupByValues = rows.groupBy { it[groupByAlias]?.toString()?.toInt() } // TODO do something better than toString().toInt() here

                val buckets = sortedMapOf<Int, FiltersBucket>()
                for ((key, thisKeyRows) in groupByValues) {
                    if (key == null) continue

                    buckets[key] = FiltersBucket.of { fbb ->
                        buildKids(kids, query, thisKeyRows, path, depth+1, fbb) { builder, kidName, kidAgg ->
                            builder.aggregations(kidName, kidAgg)
                        }.docCount(thisKeyRows.size.toLong())
                    }
                }

                return FiltersAggregate.of { fb ->
                    fb.buckets { it.array(buckets.values.toList()) }
                }._toAggregate()

            } else {
                // TODO can we avoid two traversals here?
                val groupByValues = rows.groupBy { it[groupByAlias]?.toString() } // TODO do something better than toString here

                val buckets = mutableMapOf<String, FiltersBucket>()
                for ((key, thisKeyRows) in groupByValues) {
                    if (key == null) continue

                    buckets[key] = FiltersBucket.of { fbb ->
                        buildKids(kids, query, thisKeyRows, path, depth+1, fbb) { builder, kidName, kidAgg ->
                            builder.aggregations(kidName, kidAgg)
                        }.docCount(thisKeyRows.size.toLong())
                    }
                }

                return FiltersAggregate.of { fb ->
                    fb.buckets { it.keyed(buckets) }
                }._toAggregate()
            }
        }
        in singleValueAggregationKinds.keys -> {
            val info = query.aggProjs[path] ?: error("Unknown aggregation path $path")
            val value = rows[0][info.alias] ?: error("No value in row for ${info.alias}")
            val builder = singleValueAggregationKinds[agg._kind()] ?: error("No builder for ${agg._kind()}")

            return builder(value.toString(), value.toString().toDouble())._toAggregate()
        }
        // TODO terms with children
        else -> {
            TODO("Support ${agg._kind()} aggregates")
        }
    }
}

private fun <T> buildKids(
    kids: Map<String, AggInfo>,
    query: IRQuery,
    rows: List<Map<String, Any>>,
    path: List<String>,
    depth: Int,
    builder: T,
    f: (T, String, Aggregate) -> T
): T {
    var builder0 = builder
    for (kidName in kids.keys) {
        // TODO listOf(row) assumes children are always single-valued; maybe valid?
        val kidAgg = populateMultiBucketRow(query, rows, path + kidName, depth + 1)
        builder0 = f(builder0, kidName, kidAgg)
    }
    return builder0
}

private fun aggResponse(
    sourceRows: List<Map<String, Any>>,
    projRows: List<Map<String, Any>>,
    res: TranslationResult,
    took: Long,
    logger: Logger
): SearchResponse<Map<String, Any>> {
    val rows = sourceRows.zip(projRows).map { (l, r) ->
        // TODO can there be colliding fieldnames between the two?
        l + r
    }

    val (aggs, count) = if (res.query.key.groupBys.isEmpty() && res.query.aggProjs.isNotEmpty() && res.query.aggProjs.values.first().agg == null) {
        // Special case: CountAll without GROUP BY

        require (rows.size == 1) {
            "Special-cased COUNT(*) didn't return exactly one row (${rows.size})"
        }
        require (res.query.aggProjs.size == 1) {
            "Special-cased COUNT(*) didn't have exactly one aggregate projection (${res.query.aggProjs})"
        }

        val info = res.query.aggProjs.values.first()

        emptyMap<String, Aggregate>() to rows[0][info.alias].toString().toLong()
    } else {
        val roots = res.query.aggProjs.keys.filter { it.size == 1 }.map { it.first() }

        val aggs = roots.associateWith {
            populateMultiBucketRow(res.query, rows, listOf(it), 0)
        }

        aggs to rows.size.toLong()
    }

    return SearchResponse.of { srb ->
        srb.took(took)
            .timedOut(false)
            .shards(ShardStatistics.of { ssb ->
                ssb.skipped(0)
                    .failed(0)
                    .successful(rows.size) // TODO this should actually be the number of documents processed by the aggregations, not the post-group-by results
                    .total(rows.size)
            })
            .hits { hb ->
                hb.total(TotalHits.of {
                        it.value(count)
                        .relation(TotalHitsRelation.Eq) // TODO sometimes Gte?
                    })
                  .maxScore(null)
                  .hits(emptyList())
            }
            .aggregations(aggs)
    }
}

fun String?.prefixIfNotEmpty(s: String): String? {
    return if (this.isNullOrEmpty()) this else s + this
}

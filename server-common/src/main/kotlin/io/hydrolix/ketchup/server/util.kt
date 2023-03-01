package io.hydrolix.ketchup.server

import co.elastic.clients.elasticsearch.async_search.SubmitRequest
import co.elastic.clients.elasticsearch.async_search.SubmitResponse
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.module.SimpleModule
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.config.ElasticVendor
import io.hydrolix.ketchup.model.opensearch.*
import io.hydrolix.ketchup.translate.Untranslatable
import io.hydrolix.ketchup.translate.UntranslatableReason
import io.hydrolix.ketchup.util.JSON
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.util.*
import java.util.concurrent.TimeUnit

fun <A> loadConfig(envName: String, ref: TypeReference<A>): A? {
    return System.getenv(envName)?.let { loadConfig(URL(it), ref) }

}

fun <A> loadConfig(url: URL, ref: TypeReference<A>): A? {
    return url.openStream().use { stream ->
        JSON.objectMapper.readValue(stream, ref)
    }
}

// TODO this needs to handle wildcards too
fun identifyDispatchMapping(
    mappingsByIndex: Map<String, Set<DBMapping>>,
    mappingsById: Map<UUID, Set<DBMapping>>,
    index: DataRequestPanelIndexPattern,
    logger: Logger
): DBMapping? {
    val mappings = when (index) {
        is NamedIndexPattern -> mappingsByIndex[index.name]
        is IdIndexPattern -> mappingsById[index.id]
    }.orEmpty()

    return if (mappings.isEmpty()) {
        logger.debug("No index mapping for $index, passing through to Kibana")
        null
    } else {
        if (mappings.size == 1) {
            mappings.first().also {
                logger.debug("Found unique index mapping for $index: it")
            }
        } else {
            logger.warn("Found multiple index mappings for $index: $mappings; choosing the first arbitrarily")
            mappings.first()
        }
    }
}

suspend fun PipelineContext<Unit, ApplicationCall>.passthrough(
    method: HttpMethod,
    url: URI,
    ktype: ElasticVendor,
    reqBytes: ByteArray,
    logger: Logger,
    kibanaClient: HttpClient,
    f: (ByteArray, ByteArray, ContentType, HttpStatusCode) -> Unit = { _, _, _, _ -> }
) {
    val resp = dupe(method, url, ktype, reqBytes, logger, kibanaClient)

    val ct = resp.headers().firstValue("Content-Type").orElseThrow()

    for ((k, v) in resp.headers().map().filterKeys { !setOf("content-type", "content-length").contains(it.lowercase()) }) {
        call.response.headers.append(k, v.single())
    }

    val respBytes = resp.body()
    val contentType = ContentType.parse(ct)
    val statusCode = HttpStatusCode.fromValue(resp.statusCode())

    f(reqBytes, respBytes, contentType, statusCode)

    call.respondBytes(
        bytes = respBytes,
        contentType = contentType,
        status = statusCode
    )
}

suspend fun PipelineContext<Unit, ApplicationCall>.dupe(
    method: HttpMethod,
    reqUri: URI,
    ktype: ElasticVendor,
    bytes: ByteArray,
    logger: Logger,
    client: HttpClient
): HttpResponse<ByteArray> {
    var reqb = HttpRequest.newBuilder(reqUri)
        .header(ktype.xsrfHeaderName, "true")
        .header("User-Agent", context.request.userAgent())

    if (context.request.accept() != null) reqb = reqb.header("Accept", context.request.accept())

    reqb.header("Content-Type", context.request.contentType().toString())

    reqb = when (method) {
        HttpMethod.Get    -> reqb.method("GET", BodyPublishers.ofByteArray(bytes))
        HttpMethod.Delete -> reqb.method("DELETE", BodyPublishers.ofByteArray(bytes))
        HttpMethod.Post   -> reqb.POST(BodyPublishers.ofByteArray(bytes))
        HttpMethod.Put    -> reqb.PUT(BodyPublishers.ofByteArray(bytes))
        else -> error("TODO can't passthrough $method requests")
    }

    val req = reqb.build()

    logger.debug("Outbound ${method.value} request: ${req.uri()} ${req.headers().map()}")

    val resp = withContext(Dispatchers.IO) {
        client.send(req, HttpResponse.BodyHandlers.ofByteArray())
    }

    if (resp.statusCode() !in setOf(200, 201)) {
        dump(logger, req, resp)
    }

    return resp
}

fun dump(
    logger: Logger,
    req: HttpRequest,
    resp: HttpResponse<ByteArray>
) {
    logger.info(
        "Backend response code for ${req.method()} ${resp.uri()} was ${resp.statusCode()}:\n" +
                "${resp.headers().map().entries.joinToString(separator = "\n  ", prefix = "  ")}\n\n  " +
                "${resp.body().decodeToString()}\n"
    )
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

@Suppress("HasPlatformType")
object ServerJSON {
    // TODO move the main JSON.objectMapper into this module if the dependencies will allow it
    val objectMapper = JSON.objectMapper.registerModule(object : SimpleModule() {
        init {
            addSerializer(SearchRequest::class.java, SearchRequestSerializer())
            addDeserializer(SearchRequest::class.java, SearchRequestDeserializer())

            addSerializer(SearchResponse::class.java, SearchResponseSerializer())
            addDeserializer(SearchResponse::class.java, SearchResponseDeserializer())

            addSerializer(SubmitRequest::class.java, SubmitRequestSerializer())
            addDeserializer(SubmitRequest::class.java, SubmitRequestDeserializer())

            addSerializer(SubmitResponse::class.java, SubmitResponseSerializer())
            addDeserializer(SubmitResponse::class.java, SubmitResponseDeserializer())
        }
    })
}

// TODO start using this once we're confident in selective dispatch
data class UnsupportedQuery(override val message: String, override val cause: Throwable?) : RuntimeException(message, cause)

private val timeR = """(\d+)(ms|s|m|h|d)""".toRegex()

fun String.parseTime(): Pair<Int, TimeUnit> {
    val match = timeR.matchEntire(this) ?: throw Untranslatable(UntranslatableReason.Other, "Couldn't parse time value: $this")
    val num = match.groupValues[1].toInt()
    return when (match.groupValues[2]) {
        "ms" -> num to TimeUnit.MILLISECONDS
        "s"  -> num to TimeUnit.SECONDS
        "m"  -> num to TimeUnit.MINUTES
        "h"  -> num to TimeUnit.HOURS
        "d"  -> num to TimeUnit.DAYS
        else -> throw Untranslatable(UntranslatableReason.Other, "Unsupported time value '$this'")
    }
}

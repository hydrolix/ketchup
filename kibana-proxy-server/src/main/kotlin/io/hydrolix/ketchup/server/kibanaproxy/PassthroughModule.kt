package io.hydrolix.ketchup.server.kibanaproxy

import io.hydrolix.ketchup.model.config.ElasticsearchConnection
import io.hydrolix.ketchup.model.config.KibanaConnection
import io.hydrolix.ketchup.server.dupe
import io.hydrolix.ketchup.server.enc
import io.hydrolix.ketchup.server.loadConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.doublereceive.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.URL
import java.net.http.HttpClient

fun Application.passthroughModule() {
    val kibanasJsonUrl = URL(environment.config.property("ketchup.ketchup.query-module.kibana-connections-json-url").getString())
    val elasticsJsonUrl = URL(environment.config.property("ketchup.ketchup.query-module.elasticsearch-connections-json-url").getString())
    val kibanas = loadConfig(kibanasJsonUrl, KibanaConnection.ListOfThese).orEmpty().associateBy { it.id }
    val elastics = loadConfig(elasticsJsonUrl, ElasticsearchConnection.ListOfThese).orEmpty().associateBy { it.id }
    val defaultKibana = kibanas.values.singleOrNull { it.default } ?: error("There must be exactly one KibanaConnection with default=true")
    val defaultElastic = elastics.values.singleOrNull { it.default } ?: error("There must be exactly one ElasticsearchConnection with default=true")

    val backendUri = defaultKibana.url // TODO decide how to multiplex/dispatch non-search requests to multiple kibanas

    val logger = this.log
    val client = HttpClient.newBuilder() // TODO use ktor client
        .cookieHandler(KibanaProxyMain.cookieManager)
        .build()

    logger.info("Passthrough module: base URL is $backendUri, listening on ${environment.config.port}")

    routing {
        install(DoubleReceive)

        get("{path...}") {
            val queryAdd = if (!context.request.queryParameters.isEmpty()) {
                // TODO just use context.request.queryString() unless we need fine-grained overrides
                val out = mutableListOf<String>()
                for ((k, vs) in context.request.queryParameters.entries()) {
                    for (v in vs) {
                        out += "${enc(k)}=${enc(v)}"
                    }
                }

                out.joinToString(prefix = "?", separator = "&")
            } else ""

            val reqUri = backendUri.resolve(context.request.path() + queryAdd)

            logger.debug("Proxying GET request from ${context.request.path()} to $reqUri")

            val resp = dupe(HttpMethod.Get, reqUri, defaultKibana.vendor, byteArrayOf(), logger, client)

            when (resp.statusCode()) {
                302 -> {
                    logger.debug("Redirect from backend: ${resp.headers().map()}; ${resp.body().decodeToString()}")
                    call.respondRedirect(resp.headers().firstValue("location").orElseThrow(), false)
                }
                else -> {
                    val ct = resp.headers().allValues("Content-Type").firstOrNull()

                    call.respondBytes(
                        bytes=resp.body(),
                        contentType=ct?.let { ContentType.parse(it) },
                        status= HttpStatusCode.fromValue(resp.statusCode())
                    )
                }
            }
        }
        post("{path...}") {
            val reqPath = context.request.path()

            val reqUri = backendUri.resolve(reqPath + context.request.queryString().prefixIfNotEmpty("?"))
            val bytes: ByteArray = context.receive()

            logger.info("Proxying POST request from $reqPath to $reqUri")

            val resp = dupe(HttpMethod.Post, reqUri, defaultKibana.vendor, bytes, logger, client)
            logger.info("Backend response code was ${resp.statusCode()}")

            val ct = resp.headers().firstValue("Content-Type").orElseThrow()

            call.respondBytes(
                bytes = resp.body(),
                contentType = ContentType.parse(ct),
                status = HttpStatusCode.fromValue(resp.statusCode())
            )
        }
    }
}

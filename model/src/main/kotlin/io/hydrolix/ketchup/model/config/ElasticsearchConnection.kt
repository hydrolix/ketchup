package io.hydrolix.ketchup.model.config

import com.fasterxml.jackson.core.type.TypeReference
import java.net.URI
import java.time.Instant
import java.util.*

/**
 * Information required for the proxy server to connect to an Elasticsearch cluster.
 *
 * Note: shouldn't contain credentials; those must be retrieved out-of-band at runtime, e.g. from AWS secret manager
 *
 * TODO write the AWS secret manager client :)
 */
data class ElasticsearchConnection(
    val id: UUID,
    val name: String?,
    val created: Instant,
    val modified: Instant?,
    val vendor: ElasticVendor,
    val url: URI,
    val extraProperties: Map<String, String>,
    val default: Boolean,
) {
    companion object {
        val ListOfThese = object : TypeReference<List<ElasticsearchConnection>>() { }
    }
}
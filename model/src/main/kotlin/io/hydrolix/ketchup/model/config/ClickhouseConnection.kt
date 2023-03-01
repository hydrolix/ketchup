package io.hydrolix.ketchup.model.config

import com.fasterxml.jackson.core.type.TypeReference
import java.time.Instant
import java.util.*

/**
 * Information required for the proxy server to connect to a ClickHouse cluster.
 *
 * Note: shouldn't contain credentials; those must be retrieved out-of-band at runtime, e.g. from AWS secret manager
 *
 * TODO write the AWS secret manager client :)
 */
data class ClickhouseConnection(
    val id: UUID,
    val name: String?,
    val created: Instant,
    val modified: Instant?,
    val legacyDriver: Boolean,
    val url: String,
    val extraProperties: Map<String, String>
) {
    companion object {
        val ListOfThese = object : TypeReference<List<ClickhouseConnection>>() { }
    }
}

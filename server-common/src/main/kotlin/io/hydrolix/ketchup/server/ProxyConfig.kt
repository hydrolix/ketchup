package io.hydrolix.ketchup.server

import io.hydrolix.ketchup.model.config.*
import io.hydrolix.ketchup.util.JSON
import io.ktor.server.config.*
import org.slf4j.Logger
import java.net.URL
import java.util.*

data class ProxyConfig(
    val clickHouses: Map<UUID, ClickhouseConnection>,
    val kibanas: Map<UUID, KibanaConnection>,
    val elastics: Map<UUID, ElasticsearchConnection>,
    val credentials: Map<UUID, CredentialEntry>,
    val dbMappings: List<DBMapping>,
    val elasticMetadatas: List<ElasticMetadata>,
    val logger: Logger
) {
    val mappingsByTable = dbMappings.associateBy { it.clickHouseConnectionId to it.clickHouseTableName }
    // TODO check if wildcards are permitted in the request
    val dbMappingsByIndex = dbMappings.groupBy { it.indexPattern }.mapValues { (_, v) -> v.toSet() }
    val dbMappingsById = mutableMapOf<UUID, Set<DBMapping>>().withDefault { mutableSetOf() }
    init {
        for (mapping in dbMappings) {
            for (id in mapping.indexPatternIds) {
                dbMappingsById[id] = dbMappingsById.getValue(id) + mapping
            }
        }
    }

    companion object {
        fun load(propertyPrefix: String, config: ApplicationConfig, logger: Logger): ProxyConfig {
            val clickhouseJsonUrl = URL(config.property("$propertyPrefix.clickhouse-connections-json-url").getString())
            val kibanasJsonUrl = config.propertyOrNull("$propertyPrefix.kibana-connections-json-url")?.let { URL(it.getString()) }
            val elasticsJsonUrl = URL(config.property("$propertyPrefix.elasticsearch-connections-json-url").getString())
            val dbMappingsJsonUrl = URL(config.property("$propertyPrefix.db-mappings-json-url").getString())
            val credentialsJsonUrl = URL(config.property("$propertyPrefix.credentials-json-url").getString())
            val elasticMetadataJsonUrls = config.property("$propertyPrefix.elastic-metadata-json-urls")
                .getList()
                .map(::URL)

            logger.info("Elastic metadata URL(s): $elasticMetadataJsonUrls")

            val clickHouses = loadConfig(clickhouseJsonUrl, ClickhouseConnection.ListOfThese).orEmpty().associateBy { it.id }
            val kibanas = kibanasJsonUrl?.let { url -> loadConfig(url, KibanaConnection.ListOfThese).orEmpty().associateBy { it.id } }.orEmpty()
            val elastics = loadConfig(elasticsJsonUrl, ElasticsearchConnection.ListOfThese).orEmpty().associateBy { it.id }
            val credentials = loadConfig(credentialsJsonUrl, CredentialEntry.ListOfThese).orEmpty().associateBy { it.id }
            val dbMappings = loadConfig(dbMappingsJsonUrl, DBMapping.ListOfThese).orEmpty()

            val elasticMetadatas = elasticMetadataJsonUrls.map {
                it.openStream().use { stream ->
                    JSON.objectMapper.readValue(stream, ElasticMetadata::class.java)
                }
            }.toList()

            return ProxyConfig(
                clickHouses,
                kibanas,
                elastics,
                credentials,
                dbMappings,
                elasticMetadatas,
                logger
            )
        }
    }
}
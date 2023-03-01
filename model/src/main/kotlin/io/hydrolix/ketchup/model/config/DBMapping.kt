package io.hydrolix.ketchup.model.config

import com.fasterxml.jackson.core.type.TypeReference
import java.util.*

/**
 * A mapping that tells the proxy server that when asked to search an index matching [indexPattern], we should try to
 * satisfy that query by connecting to the ClickHouse cluster whose connection info is identified by
 * [clickHouseConnectionId] and querying the table [clickHouseTableName].
 *
 * TODO
 *  * reference the metadata about the actual IndexPatterns from a [io.hydrolix.ketchup.model.opensearch.SavedObjectsResponse]?
 *  * maybe some hints about the kinds of queries that are likely to succeed?
 *  * Kibana can talk to remote clusters too (e.g. cluster-*:index-*), these should probably be scoped to a cluster name
 *  * COLUMN MAPPINGS!
 */
data class DBMapping(
    val indexPattern: String, // TODO maybe we want to reference the actual IndexPattern metadata from OSD/Kibana?
    val indexPatternIds: List<UUID>,
    val clickHouseConnectionId: UUID,
    val clickHouseTableName: String,
    val kibanaConnectionId: UUID?,
    val elasticConnectionId: UUID?,
    val idFieldName: String,
    val defaultFields: List<String> = listOf("*")
) {
    companion object {
        val ListOfThese = object : TypeReference<List<DBMapping>>() { }
    }
}

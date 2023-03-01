package io.hydrolix.ketchup.model.opensearch

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.*


/**
 * request: http://localhost:5601/api/saved_objects/_find?fields=title&fields=type&per_page=10000&type=index-pattern
 *  - type can be repeated too!
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SavedObjectsResponse(
    val page: Int,
    val perPage: Int,
    val total: Int,
    val savedObjects: List<SavedObject>
)

/**
 * This is probably extensible and maybe shouldn't be an enum per se, see
 * http://localhost:5601/api/opensearch-dashboards/management/saved_objects/_allowed_types
 */
enum class SavedObjectType {
    config, url, `index-pattern`, query, dashboard, visualization, search
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SavedObject(
    val type: SavedObjectType,
    val id: String, // often UUID but can be user-defined
    val attributes: ObjectNode,
    val references: List<SavedObjectReference>,
    val migrationVersion: Map<String, String>, // TODO more specific type?
    val updatedAt: Instant,
    val version: String, // TODO un-base64 maybe?
    val namespaces: List<String>,
    val score: Int,
    val meta: ObjectNode?, // TODO more specific type?
    val sort: List<String>?,
)

data class SavedObjectReference(
    val id: UUID,
    val name: String,
    val type: SavedObjectType,
)

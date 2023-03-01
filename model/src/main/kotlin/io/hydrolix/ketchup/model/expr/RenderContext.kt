package io.hydrolix.ketchup.model.expr

import io.hydrolix.ketchup.model.config.ElasticMetadata

data class RenderContext(
    val indices: List<String>,
    val primaryTable: String,
    val otherTables: List<String> = emptyList(),
    val defaultFields: Map<String, List<String>> = emptyMap(),
    val elasticMetadatas: List<ElasticMetadata> = emptyList()
) {
    companion object {
        fun forTable(indices: List<String>, tableName: String): RenderContext {
            return RenderContext(indices, tableName)
        }
    }
}
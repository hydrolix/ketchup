package io.hydrolix.ketchup.model.opensearch

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResolveIndexResponse(
    val indices: List<ResolvedIndex>,
    val aliases: List<ResolvedAlias>,
    val dataStreams: List<ResolvedDataStream>
)

data class ResolvedIndex(
    val name: String,
    val aliases: List<String>?,
    val attributes: List<IndexAttribute>
)

enum class IndexAttribute {
    open, closed, hidden, system, frozen;
}

data class ResolvedAlias(
    val name: String,
    val indices: List<String>,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ResolvedDataStream(
    val timestampField: String,
    val backingIndices: List<String>,
)

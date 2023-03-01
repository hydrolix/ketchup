package io.hydrolix.ketchup.model.opensearch

import co.elastic.clients.elasticsearch.core.SearchRequest
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.*

data class UIRestrictions(
    val whiteListedMetrics: Map<String, JsonNode>, // TODO custom deser if we decide to care about this field
    val whiteListedGroupByFields: Map<String, Boolean>,
    val whiteListedTimerangeModes: Map<String, Boolean>,
    val whiteListedConfigurationFeatures: Map<String, Boolean>?,
)

data class DataResponseSingleSeries(
    val key: String,
    val series: List<DataResponseSubSeries>
)

// TODO split this into `timeseries` and `table` flavours?
data class DataResponseSubSeries(
    val id: String, // TODO parse UUID:tag?
    val label: String,
    val data: List<ArrayNode>, // TODO can we tighten this up at all? Is field #1 always a timestamp? Are there ever more than two columns?
    val slope: BigDecimal?,
    val last: JsonNode?, // TODO can we tighten this up at all?
    val labelFormatted: String?,
    val color: String?,
    val seriesId: UUID?,
    val stack: String?, // TODO enum? `percent`
    val lines: DataResponseSubSeriesLines?,
    val points: DataResponseSubSeriesPoints?,
    val bars: DataResponseSubSeriesBars?,
    val splitByLabel: String?,
)

data class DataResponseSubSeriesLines(
    val show: Boolean,
    val fill: Double,
    val lineWidth: Int,
    val steps: Boolean,
)

data class DataResponseSubSeriesPoints(
    val show: Boolean,
    val radius: Int,
    val lineWidth: Int,
)

data class DataResponseSubSeriesBars(
    val show: Boolean,
    val fill: Double,
    val lineWidth: Int,
)

data class DataResponse(
    val type: DataRequestPanelType, // TODO is this right? A request can have multiple panels, but the response is only a single type?
    val uiRestrictions: UIRestrictions,
    val trackedEsSearches: Map<UUID, TrackedSearch>?,
    @JsonProperty("series")
    val singleSeries: List<DataResponseSingleSeries>?,
    @JsonAnySetter
    val otherSeries: Map<UUID, DataResponseOtherSeries>?,
)

data class TrackedSearch(
    val body: SearchRequest,
    val time: Int,
    val label: String,
    val response: TrackedSearchResponse,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TrackedSearchResponse(
    val took: Int,
    val timedOut: Boolean,
    @JsonProperty("_shards")
    val shards: SearchResponseShardInfo,
    val hits: SearchResponseHits
)

data class DataResponseOtherSeries(
    val id: UUID?,
    val annotations: Map<UUID, List<DataResponseSeriesAnnotation>>?,
    val series: List<DataResponseSubSeries>?,
)

data class DataResponseSeriesAnnotation(
    val key: Long, // TODO could this be anything else?
    val docs: List<ObjectNode>
)

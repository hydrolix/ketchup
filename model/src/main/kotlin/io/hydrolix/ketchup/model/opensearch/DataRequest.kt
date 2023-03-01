package io.hydrolix.ketchup.model.opensearch

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.json.JsonEnum
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.util.*

data class DataRequestFilterMeta(
    val alias: String?,
    val controlledBy: String,
    val disabled: Boolean,
    val index: UUID,
    val key: String,
    val negate: Boolean,
    val params: Map<String, String>, // TODO is it just an object with a single `query` field?
    val type: String
)

// TODO this is far from exhaustive, and it's probably extensible, so maybe shouldn't even be an enum
//  ... note that this overlaps significantly with, but is not coextensive with, [Aggregation.Kind] from ES
enum class AggregationType {
    sum, sum_bucket, count, cardinality, filter_ratio, min, max, geo_centroid, moving_average
    ;
}

// TODO consider splitting this into subtypes, since `sigma` and `numerator` only pertain to particular ones
data class DataRequestPanelSeriesMetric(
    val id: UUID,
    val type: AggregationType,
    val field: String?, // TODO if this is nullable, maybe this should have different types
    val sigma: String?, // TODO type?
    val numerator: DataRequestQuery?,
    val denominator: DataRequestQuery?,
)



abstract class JsonEnumDeserializer<J : JsonEnum>(val values: Array<J>) : JsonDeserializer<J>() {
    private val dsz = JsonEnum.Deserializer(values)
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): J {
        return dsz.parse(p.valueAsString)
    }
}

class JsonEnumSerializer : JsonSerializer<JsonEnum>() {
    override fun serialize(value: JsonEnum, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.jsonValue())
    }
}

class AggregationKindDeserializer : JsonEnumDeserializer<Aggregation.Kind>(Aggregation.Kind.values())

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DataRequestPanelSeries(
    val id: UUID,
    val color: String,
    val splitMode: String, // TODO enum? `split_mode`
    val metrics: List<DataRequestPanelSeriesMetric>,
    @JsonProperty("seperate_axis") @JsonAlias("separate_axis")
    val separateAxis: Int,
    val axisPosition: String, // TODO enum? `right`
    val formatter: String, // TODO enum? `bytes`
    val chartType: String, // TODO enum? `line`
    val lineWidth: Int,
    val pointSize: Int,
    val fill: BigDecimal,
    val stacked: String, // TODO enum? `none`
    val colorRules: List<Map<String, String>>?, // TODO type
    val label: String,
    val splitColorMode: String?, // TODO enum? `gradient`
    val offsetTime: String?, // TODO type?
    val valueTemplate: String?, // TODO type?
    val trendArrows: Int, // TODO type?
    val termsField: String?,
    val termsOrderBy: String?,
    val filter: DataRequestQuery?,
    val timeRangeMode: String?, // TODO enum? `entire_time_range`
    val palette: Palette?,
    val overrideIndexPattern: Int?,
    val seriesDropLastBucket: Int?,
)

data class Palette(
    val type: String,
    val name: String,
)

enum class DataRequestPanelType {
    table,
    timeseries,
    gauge,
    metric,
}

// TODO split this up into table & timeseries subtypes, might be able to get rid of some/all optional fields
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DataRequestPanel(
    val id: UUID,
    val type: DataRequestPanelType,
    val series: List<DataRequestPanelSeries>,
    val timeField: String,
    val indexPattern: DataRequestPanelIndexPattern,
    val interval: String, // TODO maybe a duration? `1h`
    val axisPosition: String, // TODO enum? `left`
    val axisFormatter: String, // TODO enum? `number`,
    val showLegend: Int,
    val showGrid: Int,
    val barColorRules: List<Map<String, String>>?, // TODO type
    val pivotId: String?,
    val pivotLabel: String?,
    val drilldownUrl: String?,
    val axisScale: String, // TODO enum? `normal`
    val tooltipMode: String, // TODO enum? `show_all`
    val annotations: List<DataRequestPanelAnnotation>?,
    val legendPosition: String?, // TODO enum? `bottom`
    val dropLastBucket: Int,
    val filter: DataRequestFilter?,
    val gaugeColorRules: List<GaugeColorRule>?, // TODO split this up into subtypes instead of having lots of nullable properties
    val gaugeWidth: String?,
    val gaugeInnerWidth: String?,
    val gaugeStyle: String?, // TODO enum? `half`
    val gaugeMax: String?,
    val timeRangeMode: String?, // TODO enum? `entire_time_range`
    val useKibanaIndexes: Boolean?,
    val truncateLegend: Int?,
    val maxLinesLegend: Int?,
    val backgroundColorRules: List<IdObject>?,
    @JsonProperty("isModelInvalid")
    val isModelInvalid: Boolean?,
)

data class IdObject(val id: UUID)

@JsonDeserialize(using = DRPIndexPatternDeserializer::class)
@JsonSerialize(using = DRPIndexPatternSerializer::class)
sealed interface DataRequestPanelIndexPattern {
    val stringValue: String
}
data class NamedIndexPattern(val name: String) : DataRequestPanelIndexPattern {
    override val stringValue = name
}
data class IdIndexPattern(val id: UUID) : DataRequestPanelIndexPattern {
    override val stringValue = id.toString() // TODO this isn't a good way to handle this case
}

class DRPIndexPatternDeserializer : JsonDeserializer<DataRequestPanelIndexPattern>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): DataRequestPanelIndexPattern {
        return if (p.currentToken() == JsonToken.VALUE_STRING) {
            NamedIndexPattern(p.text)
        } else if (p.currentToken == JsonToken.START_OBJECT) {
            val next = p.nextFieldName() ?: error("Expected field name")
            if (next == "id") {
                val s = p.nextTextValue()
                val xx = p.nextToken() // consume end_object
                IdIndexPattern(UUID.fromString(s))
            } else {
                error("Expected `id` field for object-style IndexPattern")
            }
        } else error("Can't deserialize an IndexPattern from ${p.currentToken()}")
    }
}

class DRPIndexPatternSerializer : JsonSerializer<DataRequestPanelIndexPattern>() {
    override fun serialize(
        value: DataRequestPanelIndexPattern,
        gen: JsonGenerator,
        serializers: SerializerProvider
    ) {
        when (value) {
            is NamedIndexPattern -> gen.writeString(value.name)
            is IdIndexPattern -> {
                gen.writeStartObject()
                gen.writeStringField("id", value.id.toString())
                gen.writeEndObject()
            }
        }
    }
}


data class GaugeColorRule(
    val value: Int,
    val id: UUID,
    val gauge: String,
    val operator: GaugeColorRuleOperator
)

// TODO this is much more general-purpose, rename it when we find out how global it is
enum class GaugeColorRuleOperator {
    gt, gte, lt, lte;
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DataRequestPanelAnnotation(
    val fields: String,
    val template: String,
    val indexPattern: String,
    val queryString: DataRequestQuery,
    val id: UUID,
    val color: String,
    val timeField: String,
    val icon: String,
    val ignoreGlobalFilters: Int,
    val ignorePanelFilters: Int,
)

@JsonIgnoreProperties("\$state")
data class DataRequestFilter(
    val meta: DataRequestFilterMeta,
    val query: ObjectNode, // TODO can we be more specific here?
)

data class Timerange(
    val timezone: String,
    val min: Instant,
    val max: Instant
)

data class DataRequestQuery(
    val language: String,
    val query: String,
)

data class DataRequestSearchSession(
    val sessionId: UUID,
    val isRestore: Boolean,
    val isStored: Boolean,
)

data class DataRequest(
    val timerange: Timerange,
    val query: List<DataRequestQuery>,
    val filters: List<DataRequestFilter>,
    val panels: List<DataRequestPanel>,
    val state: ObjectNode,
    val savedObjectId: String?,
    val searchSession: DataRequestSearchSession?,
) {
    fun timeless(): DataRequest {
        // TODO there might be time ranges in filters etc.
        return this.copy(
            timerange = this.timerange.copy(
                min = Instant.EPOCH,
                max = Instant.EPOCH
            )
        )
    }
}

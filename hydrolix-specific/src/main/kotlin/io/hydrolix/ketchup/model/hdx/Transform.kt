package io.hydrolix.ketchup.model.hdx

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import io.hydrolix.ketchup.model.TimeResolution

enum class TransformType {
    csv, json
}

data class Transform(
    val name: String,
    val description: String?,
    val type: TransformType,
    val table: String?,
    val settings: TransformSettings
)

enum class TransformCompression {
    gzip, zip, deflate, bzip2, none
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TransformSettings(
    val isDefault: Boolean,
    val compression: TransformCompression, // TODO layering
    val sqlTransform: String? = null,
    val nullValues: List<String>? = null,
    val formatDetails: TransformFormatDetails,
    val outputColumns: List<OutputColumn>
)

@JsonSubTypes(
    JsonSubTypes.Type(name = "csv", value = CsvFormatDetails::class),
    JsonSubTypes.Type(name = "json", value = JsonFormatDetails::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "type")
sealed interface TransformFormatDetails { }

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CsvFormatDetails(
    val delimiter: String, // TODO can be a number too?
    val escape: String, // TODO number
    val skipHead: Int?,
    val quote: String?, // TODO number
    val comment: String?, // TODO number
    val skipComments: Boolean,
    val windowsEnding: Boolean,
) : TransformFormatDetails

data class JsonFormatDetails(
    val flattening: JsonFlattening
) : TransformFormatDetails

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonFlattening(
    val active: Boolean,
    val mapFlatteningStrategy: FlatteningStrategy?,
    val sliceFlatteningStrategy: FlatteningStrategy?,
    val depth: Int?,
)

data class FlatteningStrategy(
    val left: String,
    val right: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class OutputColumn(
    val name: String,
    val position: Int?,
    val datatype: OutputColumnDatatype
)

enum class HdxValueType {
    boolean,
    double,
    int8,
    int32,
    int64,
    string,
    uint8,
    uint32,
    uint64,
    datetime,
    datetime64,
    epoch,
    array,
    map,
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OutputColumnDatatype(
    val type: HdxValueType,
    val primary: Boolean? = null,
    val format: String? = null,
    val default: JsonNode? = null, // TODO type?
    val elements: List<OutputColumnDatatype>? = null,
    val resolution: TimeResolution? = null,
    val script: String? = null,
    val source: OutputColumnSource? = null,
    val virtual: Boolean? = null,
    val catchAll: Boolean? = null,
    val nullValues: List<String>? = null,
    val ignore: Boolean? = null,
    val fulltext: Boolean? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OutputColumnSource(
    val fromInputField: String? = null,
    val fromInputFields: List<String>? = null,
    val fromInputIndex: Int? = null,
)
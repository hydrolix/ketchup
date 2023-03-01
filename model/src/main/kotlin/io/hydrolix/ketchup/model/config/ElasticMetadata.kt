package io.hydrolix.ketchup.model.config

import co.elastic.clients.elasticsearch._types.mapping.Property
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.hydrolix.ketchup.model.TimeResolution
import java.sql.JDBCType

// TODO where do cluster names/wildcards fit? Maybe a parent scope for this?
data class ElasticMetadata(
    val indexName: String,
    val indexAliases: List<String>? = null,
    val primaryKeyField: String,
    val timestampField: String,
    val nestedFieldsConfig: NestedFieldsConfig = NestedFieldsConfig.default,
    val columnMappings: List<MappedColumn>,
    val columnAliases: List<MappedAlias>
) {
    @JsonIgnore
    val columnsByPath = columnMappings.associateBy { it.path.joinToString(".") }
    @JsonIgnore
    val aliasesByPath = columnAliases.associateBy { it.path.joinToString(".") }
}

data class NestedFieldsConfig(
    val catchAllField: String,
    val topLevelFields: List<String>,
    val pathSeparator: String
) {
    companion object {
        val default = NestedFieldsConfig("detail", emptyList(), "_")
    }
}

sealed interface MappedThing {
    val path: List<String>
}

data class MappedAlias(
    override val path: List<String>,
    val target: String
) : MappedThing

data class MappedColumn(
    override val path: List<String>,
    val elasticType: Property.Kind,
    val jdbcType: JDBCType,
    @JsonSubTypes(
        JsonSubTypes.Type(name = "TIMESTAMP", value = DateTimeInfo::class),
        JsonSubTypes.Type(name = "TIMESTAMP_WITH_TIMEZONE", value = DateTimeInfo::class),
    )
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "jdbcType")
    val columnInfo: ColumnInfo? = null
) : MappedThing

sealed interface ColumnInfo
data class DateTimeInfo(val format: String, val resolution: TimeResolution) : ColumnInfo

// TODO ArrayInfo? Elastic doesn't have array types natively,
//  but we probably want to have a secondary mapping input for them

// TODO ObjectInfo? Map to map types, or just let flattening take care of it?
//  .. note that we only support maps where the value type is constant, not structs

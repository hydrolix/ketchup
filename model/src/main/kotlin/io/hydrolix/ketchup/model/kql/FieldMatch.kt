package io.hydrolix.ketchup.model.kql

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.hydrolix.ketchup.model.expr.Expr
import io.hydrolix.ketchup.model.expr.ExprType
import io.hydrolix.ketchup.model.expr.ListOfValues
import io.hydrolix.ketchup.model.expr.ValueType

enum class FieldMatchTargetType {
    ONE_FIELD,
    DEFAULT_FIELDS,
    SELECTED_FIELDS,
    FIELD_NAME_WILDCARD
}
@JsonSubTypes(
    JsonSubTypes.Type(name = "ONE_FIELD", value = OneField::class),
    JsonSubTypes.Type(name = "SELECTED_FIELDS", value = SelectedFields::class),
    JsonSubTypes.Type(name = "DEFAULT_FIELDS", value = DefaultFields::class),
    JsonSubTypes.Type(name = "FIELD_NAME_WILDCARD", value = FieldNameWildcard::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonPropertyOrder("type")
sealed interface FieldMatchTarget {
    val type: FieldMatchTargetType
}
data class OneField(val fieldName: String) : FieldMatchTarget {
    override val type = FieldMatchTargetType.ONE_FIELD
}
data class SelectedFields(val fieldNames: List<String>) : FieldMatchTarget {
    override val type = FieldMatchTargetType.SELECTED_FIELDS
}

/**
 * The query should search whatever the default fields for the target index are
 *
 * TODO we need a place to list the defaults in our configuration if we can't read them out of Elastic
 */
object DefaultFields : FieldMatchTarget {
    override val type = FieldMatchTargetType.DEFAULT_FIELDS
}
data class FieldNameWildcard(val pattern: String) : FieldMatchTarget {
    override val type = FieldMatchTargetType.FIELD_NAME_WILDCARD
}

data class FieldMatchPred(
    val target: FieldMatchTarget,
    val value: ListOfValues
) : Expr<Boolean> {
    override val type = ExprType.FIELD_MATCH

    override val valueType = ValueType.BOOLEAN

    override fun simplify(): Expr<Boolean> {
        return this.copy(value = value.simplify() as ListOfValues)
    }

    override fun children() = listOf(value)
}

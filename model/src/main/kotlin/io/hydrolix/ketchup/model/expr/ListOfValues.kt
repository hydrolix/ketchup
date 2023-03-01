package io.hydrolix.ketchup.model.expr

import com.fasterxml.jackson.annotation.JsonIgnore

sealed class ListOfValues : Expr<Any> {
    @get:JsonIgnore
    override val valueType: ValueType
        get() = leafValueType()

    abstract fun leafValueType(): ValueType
}

object AnyValue : ListOfValues() {
    override val type = ExprType.ANY_VALUE
    override fun leafValueType() = ValueType.ANY
    override fun children() = emptyList<Expr<*>>()
}

data class SingleValue(val value: Literal<*>) : ListOfValues() {
    override fun leafValueType() = value.valueType
    override val type = ExprType.SINGLE_VALUES
    override fun children() = listOf(value)
}

data class AndValues(val values: List<ListOfValues>) : ListOfValues() {
    override val type = ExprType.AND_VALUES

    override fun leafValueType(): ValueType {
        val vts = values.map { it.leafValueType() }.toSet()
        return if (vts.size > 1) ValueType.ANY else vts.first()
    }

    override fun children() = values
}

data class OrValues(val values: List<ListOfValues>) : ListOfValues() {
    override val type = ExprType.OR_VALUES
    override fun leafValueType(): ValueType {
        val vts = values.map { it.leafValueType() }.toSet()
        return if (vts.size > 1) ValueType.ANY else vts.first()
    }
    override fun children() = values
}

data class NotValues(val value: ListOfValues) : ListOfValues() {
    override val type = ExprType.NOT_VALUES
    override fun leafValueType(): ValueType {
        return value.leafValueType()
    }
    override fun children() = listOf(value)
}

data class BetweenValues<T : Any>(val lower: Expr<T>, val upper: Expr<T>) : ListOfValues() {
    override val type = ExprType.BETWEEN_VALUES

    override fun leafValueType(): ValueType {
        val types = setOf(lower.valueType, upper.valueType)
        return if (types.size == 1) types.first() else ValueType.ANY
    }
    override fun children() = listOf(lower, upper)
}
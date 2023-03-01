package io.hydrolix.ketchup.model.expr

data class GetField<T: Any>(
    val fieldName: String,
    override val valueType: ValueType
) : Expr<T> {
    override val type = ExprType.GET_FIELD
    override fun children() = emptyList<Expr<*>>()
}

data class GetSomeFields(
    val fieldNames: List<String>,
) : Expr<Any> {
    override val valueType = ValueType.ANY
    override val type = ExprType.GET_SOME_FIELDS
    override fun children() = emptyList<Expr<*>>()
}

object GetAllFields : Expr<Any> {
    override val valueType = ValueType.ANY
    override val type = ExprType.GET_ALL_FIELDS
    override fun children() = emptyList<Expr<*>>()
}

// TODO should this be NestedExpr<T>? e.g. a GetField inside a nested scope has to be have differently
data class NestedPredicate(
    val fieldName: String,
    val child: Expr<Boolean>
) : Expr<Boolean> {
    override val type = ExprType.NESTED
    override val valueType = ValueType.BOOLEAN

    override fun simplify(): Expr<Boolean> {
        return this.copy(child = child.simplify())
    }

    override fun children() = listOf(child)
}

data class IsNull(
    val operand: Expr<Any>
) : Expr<Boolean> {
    override val type = ExprType.IS_NULL
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(operand)
}

data class IsNotNull(
    val operand: Expr<Any>
) : Expr<Boolean> {
    override val type = ExprType.IS_NOT_NULL
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(operand)
}

data class Cast<T : Any>(
    val operand: Expr<Any>,
    override val valueType: ValueType,
) : Expr<T> {
    override val type = ExprType.CAST

    @Suppress("UNCHECKED_CAST")
    override fun simplify(): Expr<T> {
        return when (operand.valueType) {
            valueType -> operand as Expr<T>
            else -> this
        }
    }

    override fun children() = listOf(operand)
}

data class In<T : Any>(
    val needle: Expr<T>,
    val haystack: Expr<List<T>>
) : Expr<Boolean> {
    override val valueType = ValueType.BOOLEAN
    override val type = ExprType.IN

    override fun validate() {
        val mt = needle.valueType.toMulti() ?: error("In.needle valueType ${needle.valueType} has no multivalued equivalent")
        require (haystack.valueType == mt) {
            "In.haystack valueType ${haystack.valueType} was not the multivalued equivalent of ${needle.valueType}"
        }
    }

    override fun children() = listOf(needle) + haystack
}

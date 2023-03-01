package io.hydrolix.ketchup.model.expr

interface ComparisonOp<T: Any> : Expr<Boolean> {
    val left: Expr<T>
    val right: Expr<T>

    override fun validate() {
        require (left.valueType == right.valueType) {
            "Left and right operands' value types must match (left=${left.valueType}, right=${right.valueType})"
        }
    }

    override fun children() = listOf(left, right)
}

// TODO add close-enough versions for floats and doubles
data class Equal<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.EQUAL
    override val valueType = ValueType.BOOLEAN
}

// TODO add close-enough versions for floats and doubles
data class NotEqual<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.NOT_EQUAL
    override val valueType = ValueType.BOOLEAN
}

data class GreaterEqual<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.GREATER_EQUAL
    override val valueType = ValueType.BOOLEAN
}

data class Greater<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.GREATER
    override val valueType = ValueType.BOOLEAN
}

data class LessEqual<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.LESS_EQUAL
    override val valueType = ValueType.BOOLEAN
}

data class Less<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>,
) : ComparisonOp<T> {
    override val type = ExprType.LESS
    override val valueType = ValueType.BOOLEAN
}

data class Between<T : Any>(
    val operand: Expr<T>,
    val lower: Expr<T>,
    val upper: Expr<T>,
    val lowerInclusive: Boolean = true,
    val upperInclusive: Boolean = true,
) : Expr<Boolean> {
    override val type = ExprType.BETWEEN
    override val valueType = ValueType.BOOLEAN

    override fun validate() {
        require (operand.valueType == lower.valueType && operand.valueType == upper.valueType) {
            "BETWEEN operator: Operand, lower and upper value types must match (operand=${operand.valueType}, lower=${lower.valueType}, upper=${upper.valueType})"
        }
    }

    override fun children() = listOf(operand, lower, upper)
}

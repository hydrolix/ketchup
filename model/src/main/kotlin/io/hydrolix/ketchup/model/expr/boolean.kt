package io.hydrolix.ketchup.model.expr

data class And(
    val children: List<Expr<Boolean>>
) : Expr<Boolean> {
    override val type = ExprType.AND
    override val valueType = ValueType.BOOLEAN

    override fun simplify(): Expr<Boolean> {
        return when {
            children.isEmpty() -> BooleanLiteral.True // Empty is always true
            children.all { it == BooleanLiteral.True } -> BooleanLiteral.True // All trues => True
            children.any { it == BooleanLiteral.False } -> BooleanLiteral.False // Any false => False (TODO side effects elided here, maybe only do this when they're all literals?)
            children.size == 1 -> children.first().simplify() // Flatten singleton
            else -> this.copy(children = children.map { it.simplify() }.filter { it != BooleanLiteral.True })
        }
    }

    override fun children() = children
}

data class Or(
    val children: List<Expr<Boolean>>
) : Expr<Boolean> {
    override val type = ExprType.OR
    override val valueType = ValueType.BOOLEAN

    override fun simplify(): Expr<Boolean> {
        return when {
            children.isEmpty() -> BooleanLiteral.True // Empty is always true
            children.all { it == BooleanLiteral.True } -> BooleanLiteral.True // All trues => True
            children.all { it == BooleanLiteral.False } -> BooleanLiteral.False // All false => False
            children.size == 1 -> children.first().simplify() // Flatten singleton
            else -> this.copy(children = children.map { it.simplify() }.filter { it != BooleanLiteral.True })
        }
    }

    override fun children() = children
}

data class Not(
    val operand: Expr<Boolean>
) : Expr<Boolean> {
    override val type = ExprType.NOT
    override val valueType = ValueType.BOOLEAN

    override fun simplify(): Expr<Boolean> {
        return when (operand) {
            BooleanLiteral.True -> BooleanLiteral.False
            BooleanLiteral.False -> BooleanLiteral.True
            is IsNull -> IsNotNull(operand.operand.simplify()) // `x IS NOT NULL` is nicer to look at than `NOT(x IS NULL)`
            is Not -> operand.operand.simplify() // Flatten double negation
            is Equal<*> -> NotEqual(operand.left.simplify(), operand.right.simplify()) // `x != y` is nicer to look at than `NOT (x = y)`
            else -> this.copy(operand = operand.simplify())
        }
    }

    override fun children() = listOf(operand)
}

/**
 * An IR version of the SQL `CASE` expression.
 *
 * @param tests the boolean expressions to be evaluated to choose a result - use [BooleanLiteral.True] if you want an `else`
 * @param results the expression to be returned when the corresponding test is true
 */
data class Case<R : Any>(
    val tests: List<Expr<Boolean>>,
    val results: List<Expr<R>>
) : Expr<R> {
    override val type = ExprType.CASE
    override val valueType = results.first().valueType

    override fun validate() {
        require (results.map { it.valueType }.toSet().size == 1) {
            "All result expressions must be of the same type (${results.map { it.valueType }.toSet()})"
        }

        require (tests.size == results.size) {
            "`tests` and `results` must be the same size (tests ${tests.size}, results ${results.size})"
        }
    }

    override fun children() = tests + results
}
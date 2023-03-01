package io.hydrolix.ketchup.model.expr

data class Like(
    val operand: Expr<String>,
    val match: Expr<String>
) : Expr<Boolean> {
    override val type = ExprType.LIKE
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(operand, match)
}

data class ContainsTerm(
    val haystack: Expr<String>,
    val needle: Expr<String>,
) : Expr<Boolean> {
    override val type = ExprType.CONTAINS_TERM
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(haystack, needle)
}

data class StartsWith(
    val haystack: Expr<String>,
    val needle: Expr<String>,
) : Expr<Boolean> {
    override val type = ExprType.STARTS_WITH
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(haystack, needle)
}

data class EndsWith(
    val haystack: Expr<String>,
    val needle: Expr<String>,
) : Expr<Boolean> {
    override val type = ExprType.ENDS_WITH
    override val valueType = ValueType.BOOLEAN
    override fun children() = listOf(haystack, needle)
}

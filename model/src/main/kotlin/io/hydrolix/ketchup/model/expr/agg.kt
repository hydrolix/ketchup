package io.hydrolix.ketchup.model.expr

interface AggExpr<T : Any> : Expr<T>

data class Min<T : Any>(
    val source: Expr<T>
) : AggExpr<T> {
    override val type = ExprType.MIN_AGG
    override val valueType = source.valueType
    override fun children() = listOf(source)
}

data class Max<T : Any>(
    val source: Expr<T>
) : AggExpr<T> {
    override val type = ExprType.MAX_AGG
    override val valueType = source.valueType
    override fun children() = listOf(source)
}

data class Sum<T : Any>(
    val source: Expr<T>
) : AggExpr<T> {
    override val type = ExprType.SUM_AGG
    override val valueType = source.valueType
    override fun children() = listOf(source)
}

data class Avg<T : Any>(
    val source: Expr<T>
) : AggExpr<T> {
    override val type = ExprType.AVG_AGG
    override val valueType = source.valueType
    override fun children() = listOf(source)
}

object CountAll : AggExpr<Long> {
    override val type = ExprType.COUNT_ALL_AGG
    override val valueType = ValueType.LONG
    override fun children() = emptyList<Expr<*>>()
}

data class Count(
    val source: Expr<*>
) : AggExpr<Long> {
    override val type = ExprType.COUNT_AGG
    override val valueType = ValueType.LONG
    override fun children() = listOf(source)
}

data class CountDistinct(
    val source: Expr<*>
) : AggExpr<Long> {
    override val type = ExprType.COUNT_DISTINCT_AGG
    override val valueType = ValueType.LONG
    override fun children() = listOf(source)
}

data class TopK<T : Any>(
    val expr: Expr<T>,
    val k: Int
) : Expr<List<T>> {
    override val type = ExprType.TOP_K_AGG
    override val valueType = expr.valueType.toMulti() ?: error("TopK.expr has value type ${expr.valueType} which can't be transformed to multivalued")
    override fun children() = listOf(expr)
}

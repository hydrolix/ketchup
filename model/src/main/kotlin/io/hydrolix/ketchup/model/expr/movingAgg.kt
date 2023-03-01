package io.hydrolix.ketchup.model.expr

interface MovingAgg<T : Any> : AggExpr<T> {
    val source: Expr<T>
    val window: Int
    val sqlName: String

    override fun children() = listOf(source)
}

data class MovingAvg<T : Any>(
    override val source: Expr<T>,
    override val window: Int,
) : MovingAgg<T> {
    override val type = ExprType.MOVING_AVG_AGG
    override val valueType = source.valueType
    override val sqlName = "AVG"
}

data class MovingSum<T : Any>(
    override val source: Expr<T>,
    override val window: Int,
) : MovingAgg<T> {
    override val type = ExprType.MOVING_SUM_AGG
    override val valueType = source.valueType
    override val sqlName = "SUM"
}

data class MovingMin<T : Any>(
    override val source: Expr<T>,
    override val window: Int,
) : MovingAgg<T> {
    override val type = ExprType.MOVING_MIN_AGG
    override val valueType = source.valueType
    override val sqlName = "MIN"
}

data class MovingMax<T : Any>(
    override val source: Expr<T>,
    override val window: Int,
) : MovingAgg<T> {
    override val type = ExprType.MOVING_MAX_AGG
    override val valueType = source.valueType
    override val sqlName = "MAX"
}

package io.hydrolix.ketchup.model.expr

interface Call {
    val name: String
    val params: List<Expr<Any>>
}

data class Call1<R : Any, A : Any>(
    override val name: String,
    val p1: Expr<A>,
    override val valueType: ValueType
) : Expr<R>, Call {
    override val type = ExprType.CALL1
    override val params = listOf(p1)
    override fun children() = params
}

data class Call2<R : Any, A : Any, B : Any>(
    override val name: String,
    val p1: Expr<A>,
    val p2: Expr<B>,
    override val valueType: ValueType
) : Expr<R>, Call {
    override val type = ExprType.CALL2
    override val params = listOf(p1, p2)
    override fun children() = params
}

data class Call3<R : Any, A : Any, B : Any, C : Any>(
    override val name: String,
    val p1: Expr<A>,
    val p2: Expr<B>,
    val p3: Expr<C>,
    override val valueType: ValueType
) : Expr<R>, Call {
    override val type = ExprType.CALL3
    override val params = listOf(p1, p2, p3)
    override fun children() = params
}

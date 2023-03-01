package io.hydrolix.ketchup.model.expr

data class Abs<T : Any>(
    val operand: Expr<T>
) : Expr<T> {
    override val type = ExprType.ABS
    override val valueType = operand.valueType
    override fun children() = listOf(operand)
}

abstract class MathOp<T : Any> : Expr<T> {
    abstract val left: Expr<T>
    abstract val right: Expr<T>

    override val valueType by lazy { left.valueType }

    override fun validate() {
        require (left.valueType == right.valueType) { "Left and right operands must have the same valueType" }
    }
    override fun children() = listOf(left, right)
}

/** A MathOp that has a standardized symbolic operator */
abstract class SymOp<T : Any> : MathOp<T>() {
    abstract val sym: String
}

data class Add<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): SymOp<T>() {
    override val type = ExprType.ADD
    override val sym = "+"
}

data class Sub<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): SymOp<T>() {
    override val type = ExprType.SUB
    override val sym = "-"
}

data class Mul<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): SymOp<T>() {
    override val type = ExprType.MUL
    override val sym = "*"
}

data class Div<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): SymOp<T>() {
    override val type = ExprType.DIV
    override val sym = "/"
}

data class IntDiv<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): MathOp<T>() { // TODO some DBs support integer division with `/` but we're being conservative here in not promising it
    override val type = ExprType.INT_DIV
}

data class Mod<T : Any>(
    override val left: Expr<T>,
    override val right: Expr<T>
): MathOp<T>() { // TODO some DBs support `%` but we're being conservative here in not promising it
    override val type = ExprType.MOD
}
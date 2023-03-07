package io.hydrolix.ketchup.model.expr

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.node.*
import io.hydrolix.ketchup.model.kql.*

@JsonSubTypes(
    JsonSubTypes.Type(name = "GET_FIELD", value = GetField::class),
    JsonSubTypes.Type(name = "GET_ALL_FIELDS", value = GetAllFields::class),
    JsonSubTypes.Type(name = "GET_SOME_FIELDS", value = GetSomeFields::class),
    JsonSubTypes.Type(name = "NESTED", value = NestedPredicate::class),
    JsonSubTypes.Type(name = "IS_NULL", value = IsNull::class),
    JsonSubTypes.Type(name = "CAST", value = Cast::class),
    JsonSubTypes.Type(name = "CALL1", value = Call1::class),
    JsonSubTypes.Type(name = "CALL2", value = Call2::class),
    JsonSubTypes.Type(name = "CALL3", value = Call3::class),
    JsonSubTypes.Type(name = "AND", value = And::class),
    JsonSubTypes.Type(name = "OR", value = Or::class),
    JsonSubTypes.Type(name = "NOT", value = Not::class),
    JsonSubTypes.Type(name = "EQUAL", value = Equal::class),
    JsonSubTypes.Type(name = "GREATER_EQUAL", value = GreaterEqual::class),
    JsonSubTypes.Type(name = "LESS_EQUAL", value = LessEqual::class),
    JsonSubTypes.Type(name = "GREATER", value = Greater::class),
    JsonSubTypes.Type(name = "LESS", value = Less::class),
    JsonSubTypes.Type(name = "BETWEEN", value = Between::class),
    JsonSubTypes.Type(name = "STRING_LIT", value = StringLiteral::class),
    JsonSubTypes.Type(name = "BOOLEAN_LIT", value = BooleanLiteral::class),
    JsonSubTypes.Type(name = "FLOAT_LIT", value = FloatLiteral::class),
    JsonSubTypes.Type(name = "DOUBLE_LIT", value = DoubleLiteral::class),
    JsonSubTypes.Type(name = "INT_LIT", value = IntLiteral::class),
    JsonSubTypes.Type(name = "LONG_LIT", value = LongLiteral::class),
    JsonSubTypes.Type(name = "DATE_LIT", value = DateLiteral::class),
    JsonSubTypes.Type(name = "TIMESTAMP_LIT", value = TimestampLiteral::class),
    JsonSubTypes.Type(name = "LIKE", value = Like::class),
    JsonSubTypes.Type(name = "CONTAINS_TERM", value = ContainsTerm::class),
    JsonSubTypes.Type(name = "FIELD_MATCH", value = FieldMatchPred::class),
    JsonSubTypes.Type(name = "SINGLE_VALUES", value = SingleValue::class),
    JsonSubTypes.Type(name = "AND_VALUES", value = AndValues::class),
    JsonSubTypes.Type(name = "OR_VALUES", value = OrValues::class),
    JsonSubTypes.Type(name = "NOT_VALUES", value = NotValues::class),
    JsonSubTypes.Type(name = "BETWEEN_VALUES", value = BetweenValues::class),
    JsonSubTypes.Type(name = "ANY_VALUE", value = AnyValue::class),
    JsonSubTypes.Type(name = "ADD", value = Add::class),
    JsonSubTypes.Type(name = "SUB", value = Sub::class),
    JsonSubTypes.Type(name = "MUL", value = Mul::class),
    JsonSubTypes.Type(name = "DIV", value = Div::class),
    JsonSubTypes.Type(name = "INT_DIV", value = IntDiv::class),
    JsonSubTypes.Type(name = "MOD", value = Mod::class),
    JsonSubTypes.Type(name = "ABS", value = Abs::class),
)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonPropertyOrder("type")
/**
 * This is the root of an expression AST
 */
interface Expr<out R : Any> {
    val valueType: ValueType
    val type: ExprType

    /** Override this to check your args at plan time; throw an exception if they aren't valid */
    fun validate() { }

    /** Override this to simplify your subtree, hopefully preserving semantics... e.g. `Not(Not(x))` => `x` */
    fun simplify(): Expr<R> { return this }

    /** Override this to list your child expressions; used for recursive AST traversals */
    fun children(): List<Expr<*>>
}

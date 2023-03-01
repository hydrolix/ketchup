package io.hydrolix.ketchup.model.expr

import co.elastic.clients.elasticsearch._types.FieldValue
import java.time.*

interface Literal<T : Any> : Expr<T> {
    companion object {
        fun of(value: Any?): Literal<*> {
            return when (value) {
                null -> NullLiteral
                is FieldValue -> {
                    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
                    when (value._kind()) {
                        FieldValue.Kind.Null -> NullLiteral
                        FieldValue.Kind.String -> StringLiteral(value.stringValue())
                        FieldValue.Kind.Boolean -> BooleanLiteral.of(value.booleanValue())
                        FieldValue.Kind.Double -> DoubleLiteral(value.doubleValue())
                        FieldValue.Kind.Long -> LongLiteral(value.longValue())
                        FieldValue.Kind.Any -> TODO("AnyLiteral")
                    }
                }
                is String -> StringLiteral(value)
                is Instant -> TimestampLiteral(ZonedDateTime.ofInstant(value, ZoneOffset.UTC))
                is ZonedDateTime -> TimestampLiteral(value)
                is LocalDateTime -> TimestampLiteral(ZonedDateTime.of(value, ZoneOffset.UTC))
                is LocalDate -> DateLiteral(value)
                is Int -> IntLiteral(value)
                is Long -> LongLiteral(value)
                is Float -> FloatLiteral(value)
                is Boolean -> if (value == true) BooleanLiteral.True else BooleanLiteral.False
                is List<*> -> {
                    if (value.isEmpty()) MultiAnyLiteral(emptyList())
                    else {
                        val kidLit = of(value.first())
                        if (kidLit.valueType.isMulti) error("Nested multivalues are not allowed (yet?)")

                        // TODO this assumes every value in the array is of the same type, maybe check?
                        @Suppress("UNCHECKED_CAST")
                        when (kidLit.valueType) {
                            ValueType.STRING -> MultiStringLiteral(value as List<String>)
                            ValueType.BOOLEAN -> MultiBooleanLiteral(value as List<Boolean>)
                            ValueType.LONG -> MultiLongLiteral(value as List<Long>)
                            ValueType.INT -> MultiIntLiteral(value as List<Int>)
                            ValueType.DOUBLE -> MultiDoubleLiteral(value as List<Double>)
                            ValueType.FLOAT -> MultiFloatLiteral(value as List<Float>)
                            ValueType.TIMESTAMP -> MultiTimestampLiteral(value as List<ZonedDateTime>)
                            ValueType.DATE -> MultiDateLiteral(value as List<LocalDate>)
                            ValueType.ANY -> MultiAnyLiteral(value as List<Any>)
                            else -> error("Can't create MultiValueLiteral from ${value.first()}")
                        }
                    }
                }
                else -> error("Unsupported literal of type ${value.javaClass}: $value")
            }
        }
    }

    override fun children() = emptyList<Expr<*>>()
}

interface ValueLiteral<T : Any> : Literal<T> {
    val value: T
}

interface MultiValueLiteral<T : Any, SV : ValueLiteral<T>> : ValueLiteral<List<T>> {
    fun unwrap(): List<SV>
}

object NullLiteral : Literal<Nothing> {
    override val type = ExprType.NULL_LIT
    override val valueType = ValueType.NULL
}

data class StringLiteral(
    override val value: String
) : ValueLiteral<String> {
    override val type = ExprType.STRING_LIT
    override val valueType = ValueType.STRING

    companion object {
        val Empty = StringLiteral("")
        fun orNull(s: String?): Literal<*> {
            return s?.let { StringLiteral(it) } ?: NullLiteral
        }
    }
}

data class MultiStringLiteral(
    override val value: List<String>
) : MultiValueLiteral<String, StringLiteral> {
    override val type = ExprType.MULTI_STRING_LIT
    override val valueType = ValueType.MULTI_STRING

    override fun unwrap(): List<StringLiteral> {
        return value.map { StringLiteral(it) }
    }
}

data class IntLiteral(
    override val value: Int
) : ValueLiteral<Int> {
    override val type = ExprType.INT_LIT
    override val valueType = ValueType.INT

    companion object {
        val Zero = IntLiteral(0)
        val One = IntLiteral(1)
        val MinusOne = IntLiteral(-1)
    }
}

data class MultiIntLiteral(
    override val value: List<Int>
) : MultiValueLiteral<Int, IntLiteral> {
    override val type = ExprType.MULTI_INT_LIT
    override val valueType = ValueType.MULTI_INT

    override fun unwrap(): List<IntLiteral> {
        return value.map { IntLiteral(it) }
    }
}

data class LongLiteral(
    override val value: Long
) : ValueLiteral<Long> {
    override val type = ExprType.LONG_LIT
    override val valueType = ValueType.LONG

    companion object {
        val `0i` = LongLiteral(0L)
        val `1i` = LongLiteral(1L)
        val `-1i` = LongLiteral(-1L)
    }
}

data class MultiLongLiteral(
    override val value: List<Long>
) : MultiValueLiteral<Long, LongLiteral> {
    override val type = ExprType.MULTI_LONG_LIT
    override val valueType = ValueType.MULTI_LONG

    override fun unwrap(): List<LongLiteral> {
        return value.map { LongLiteral(it) }
    }
}

data class FloatLiteral(
    override val value: Float
) : ValueLiteral<Float> {
    override val type = ExprType.FLOAT_LIT
    override val valueType = ValueType.FLOAT

    companion object {
        val `0f` = FloatLiteral(0.0f)
        val `1f` = FloatLiteral(1.0f)
        val `-1f` = FloatLiteral(-1.0f)
    }
}

data class MultiFloatLiteral(
    override val value: List<Float>
) : MultiValueLiteral<Float, FloatLiteral> {
    override val type = ExprType.MULTI_FLOAT_LIT
    override val valueType = ValueType.MULTI_FLOAT

    override fun unwrap(): List<FloatLiteral> {
        return value.map { FloatLiteral(it) }
    }
}

data class AnyLiteral(
    override val value: Any
) : ValueLiteral<Any> {
    override val type = ExprType.ANY_LIT
    override val valueType = ValueType.ANY
}

data class MultiAnyLiteral(
    override val value: List<Any>
) : MultiValueLiteral<Any, AnyLiteral> {
    override val type = ExprType.MULTI_ANY_LIT
    override val valueType = ValueType.MULTI_ANY

    override fun unwrap(): List<AnyLiteral> {
        return value.map { AnyLiteral(it) }
    }

}

data class DoubleLiteral(
    override val value: Double
) : ValueLiteral<Double> {
    override val type = ExprType.DOUBLE_LIT
    override val valueType = ValueType.DOUBLE

    companion object {
        val `0d` = DoubleLiteral(0.0)
        val `1d` = DoubleLiteral(1.0)
        val `-1d` = DoubleLiteral(-1.0)
    }
}

data class MultiDoubleLiteral(
    override val value: List<Double>
) : MultiValueLiteral<Double, DoubleLiteral> {
    override val type = ExprType.MULTI_DOUBLE_LIT
    override val valueType = ValueType.MULTI_DOUBLE

    override fun unwrap(): List<DoubleLiteral> {
        return value.map { DoubleLiteral(it) }
    }
}

data class BooleanLiteral private constructor(
    override val value: Boolean
) : ValueLiteral<Boolean> {
    override val type = ExprType.BOOLEAN_LIT
    override val valueType = ValueType.BOOLEAN

    companion object {
        @JvmStatic
        val True = BooleanLiteral(true)
        @JvmStatic
        val False = BooleanLiteral(false)

        fun of(b: Boolean): BooleanLiteral = if (b) True else False
    }
}

data class MultiBooleanLiteral(
    override val value: List<Boolean>
) : MultiValueLiteral<Boolean, BooleanLiteral> {
    override val type = ExprType.MULTI_BOOLEAN_LIT
    override val valueType = ValueType.MULTI_BOOLEAN

    override fun unwrap(): List<BooleanLiteral> {
        return value.map { BooleanLiteral.of(it) }
    }
}

data class TimestampLiteral(
    override val value: ZonedDateTime
) : ValueLiteral<ZonedDateTime> {
    override val type = ExprType.TIMESTAMP_LIT
    override val valueType = ValueType.TIMESTAMP

    companion object {
        val Epoch = TimestampLiteral(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
    }
}

data class MultiTimestampLiteral(
    override val value: List<ZonedDateTime>
) : MultiValueLiteral<ZonedDateTime, TimestampLiteral> {
    override val type = ExprType.MULTI_TIMESTAMP_LIT
    override val valueType = ValueType.MULTI_TIMESTAMP

    override fun unwrap(): List<TimestampLiteral> {
        return value.map { TimestampLiteral(it) }
    }
}

// TODO in some (rare) cases we might want a ZonedDate but there's no such thing in JDK; this will effectively always be
//  interpreted as UTC
data class DateLiteral(
    override val value: LocalDate
) : ValueLiteral<LocalDate> {
    override val type = ExprType.DATE_LIT
    override val valueType = ValueType.DATE

    companion object {
        val Epoch = DateLiteral(LocalDate.ofInstant(Instant.EPOCH, ZoneOffset.UTC))
    }
}

data class MultiDateLiteral(
    override val value: List<LocalDate>
) : MultiValueLiteral<LocalDate, DateLiteral> {
    override val type = ExprType.MULTI_DATE_LIT
    override val valueType = ValueType.MULTI_DATE

    override fun unwrap(): List<DateLiteral> {
        return value.map { DateLiteral(it) }
    }
}

/**
 * Note that there's no MultiIntervalLiteral (yet?)
 * @param count the number of `unit` values in this interval
 * @param unit the time unit for this interval. TODO it would be better if this were an enum, but we need to mix `IsoFields` and `ChronoField`
 */
data class IntervalLiteral(
    val count: Long,
    val unit: String
) : Literal<Pair<Long, String>> {
    override val type = ExprType.INTERVAL_LIT
    override val valueType = ValueType.ANY // TODO maybe struct/other?
}
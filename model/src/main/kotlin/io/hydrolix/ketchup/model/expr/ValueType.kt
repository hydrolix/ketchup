package io.hydrolix.ketchup.model.expr

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * @param cardinality 0 for null, 1 for single-valued, 2 for multi-valued
 */
enum class ValueType(val cardinality: Int) {
    NULL(0),
    ANY(1),
    STRING(1),
    INT(1),
    LONG(1),
    FLOAT(1),
    DOUBLE(1),
    BOOLEAN(1),
    DATE(1),
    TIMESTAMP(1),
    // note no MULTI_NULL
    MULTI_ANY(2),
    MULTI_STRING(2),
    MULTI_INT(2),
    MULTI_LONG(2),
    MULTI_FLOAT(2),
    MULTI_DOUBLE(2),
    MULTI_BOOLEAN(2),
    MULTI_DATE(2),
    MULTI_TIMESTAMP(2),
    ;

    val isSingle = cardinality == 1
    val isMulti = cardinality == 2

    fun toMulti(): ValueType? {
        return when (this) {
            NULL      -> NULL
            ANY       -> MULTI_ANY
            STRING    -> MULTI_STRING
            INT       -> MULTI_INT
            LONG      -> MULTI_LONG
            FLOAT     -> MULTI_FLOAT
            DOUBLE    -> MULTI_DOUBLE
            BOOLEAN   -> MULTI_BOOLEAN
            DATE      -> MULTI_DATE
            TIMESTAMP -> MULTI_TIMESTAMP
            else      -> null
        }
    }

    fun toSingle(): ValueType? {
        return when (this) {
            NULL            -> NULL
            MULTI_ANY       -> ANY
            MULTI_STRING    -> STRING
            MULTI_INT       -> INT
            MULTI_LONG      -> LONG
            MULTI_FLOAT     -> FLOAT
            MULTI_DOUBLE    -> DOUBLE
            MULTI_BOOLEAN   -> BOOLEAN
            MULTI_DATE      -> DATE
            MULTI_TIMESTAMP -> TIMESTAMP
            else            -> null
        }
    }

    fun toJSON(value: Any?): JsonNode {
        if (value == null) return NullNode.instance

        return when (this) {
            NULL -> NullNode.instance
            ANY -> TextNode(value.toString()) // TODO this could be better
            STRING -> TextNode.valueOf(value as String)
            INT -> IntNode.valueOf(value as Int)
            LONG -> LongNode.valueOf(value as Long)
            FLOAT -> FloatNode.valueOf(value as Float)
            DOUBLE -> DoubleNode.valueOf(value as Double)
            BOOLEAN -> BooleanNode.valueOf(value as Boolean)
            DATE -> TextNode.valueOf(DateTimeFormatter.ISO_DATE.format(value as LocalDate))
            TIMESTAMP -> TextNode.valueOf(DateTimeFormatter.ISO_DATE_TIME.format(value as Instant))
            else -> TODO("toJSON for $value of type $this")
        }
    }
}
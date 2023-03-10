package io.hydrolix.ketchup.model.expr

enum class ExprType(val sym: String? = null) {
    GET_FIELD, GET_ALL_FIELDS, GET_SOME_FIELDS, NESTED, IS_NULL, IS_NOT_NULL, CAST, CALL1, CALL2, CALL3, IN,
    AND, OR, NOT,
    EQUAL, NOT_EQUAL, GREATER_EQUAL, LESS_EQUAL, GREATER, LESS, BETWEEN, CASE,
    NULL_LIT, ANY_LIT, STRING_LIT, BOOLEAN_LIT, DOUBLE_LIT, FLOAT_LIT, INT_LIT, LONG_LIT, DATE_LIT, TIMESTAMP_LIT, INTERVAL_LIT,
    MULTI_ANY_LIT, MULTI_STRING_LIT, MULTI_BOOLEAN_LIT, MULTI_DOUBLE_LIT, MULTI_FLOAT_LIT, MULTI_INT_LIT, MULTI_LONG_LIT, MULTI_DATE_LIT, MULTI_TIMESTAMP_LIT,
    LIKE, CONTAINS_TERM, STARTS_WITH, ENDS_WITH,
    FIELD_MATCH,
    SINGLE_VALUES, AND_VALUES, OR_VALUES, NOT_VALUES, BETWEEN_VALUES, ANY_VALUE,
    MIN_AGG, MAX_AGG, COUNT_AGG, COUNT_ALL_AGG, SUM_AGG, AVG_AGG, COUNT_DISTINCT_AGG, TOP_K_AGG, MOVING_AVG_AGG, MOVING_SUM_AGG, MOVING_MIN_AGG, MOVING_MAX_AGG,
    ADD, SUB, MUL, DIV, INT_DIV, MOD, ABS
}
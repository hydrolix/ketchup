package io.hydrolix.ketchup.model.kql

import io.hydrolix.ketchup.model.expr.Expr

data class KqlTestCase(
    val name: String,
    val kql: String,
    val parsed: Expr<Boolean>?,
    val comment: String?,
)
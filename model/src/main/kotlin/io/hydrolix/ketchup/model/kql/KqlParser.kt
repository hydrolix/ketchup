package io.hydrolix.ketchup.model.kql

import arrow.core.Either
import io.hydrolix.ketchup.model.expr.Expr

interface KqlParser<E> {
    fun parseKql(s: String): Either<E, Expr<Boolean>>
}

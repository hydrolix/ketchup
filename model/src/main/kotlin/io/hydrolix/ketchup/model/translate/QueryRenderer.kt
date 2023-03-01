package io.hydrolix.ketchup.model.translate

import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.kql.*
import io.hydrolix.ketchup.model.query.IRQuery
import org.slf4j.Logger
import java.time.ZoneOffset

object QueryRenderer {
    /**
     * Render this query to Clickhouse-flavoured SQL
     *
     * @param indent a string (normally spaces) to prepend to every line of output; mainly for logging
     * @param ctx    a context object that's carried through the rendering process
     */
    fun IRQuery.render(indent: String = "", ctx: RenderContext, logger: Logger): String {
        val spaces = "$indent$indent"
        val sql = StringBuilder("${indent}SELECT \n$spaces  ")

        var positional = false

        val projIndices = mutableMapOf<Pair<Expr<*>, String>, Int>()
        var ord = 0

        if (projections.isNotEmpty()) {
            val projLines = mutableListOf<String>()
            for ((expr, alias) in projections) {
                ord += 1
                projIndices[expr to alias] = ord
                val projSyntax = when (expr) {
                    is GetAllFields -> "*"
                    is GetField<*> -> {
                        val fld = renderExpr(expr, spaces, ctx, logger)
                        if (aliases.contains(expr.fieldName)) {
                            fld
                        } else {
                            "$fld AS $alias"
                        }
                    }
                    else -> "${renderExpr(expr, spaces, ctx, logger)} AS $alias"
                }

                projLines += projSyntax
            }
            sql.append(projLines.joinToString(",\n$spaces  ", postfix = "\n$spaces"))
        }

        sql.append("FROM ${key.table}\n$spaces")
        val groupPreds = key.groupBys.mapNotNull {
            if (it.second == BooleanLiteral.True) null else it.second
        }

        val predWithGroups = And(groupPreds + key.predicate).simplify()

        if (key.predicate != BooleanLiteral.True) {
            sql.append("WHERE ")
            sql.append(renderExpr(predWithGroups, spaces, ctx, logger))
        }

        if (key.groupBys.isNotEmpty()) {
            sql.append("\n${spaces}GROUP BY ")
            val it = key.groupBys.iterator()

            for ((expr, _) in it) {
                val alias = projections[expr] ?: error("No projection for GROUP BY expression $expr")
                val pos = projIndices[expr to alias]
                if (pos != null) {
                    positional = true
                    sql.append(pos)
                } else {
                    sql.append(renderExpr(expr, spaces, ctx, logger))
                }
                if (it.hasNext()) sql.append(", ")
            }
        }

        if (key.orderBys.isNotEmpty()) {
            sql.append("\n${spaces}ORDER BY ")

            val it = key.orderBys.iterator()
            for ((expr, asc) in it) {
                val alias = projections[expr] ?: error("No projection for ORDER BY expression $expr")
                val pos = projIndices[expr to alias]

                if (pos != null) {
                    positional = true
                    sql.append(pos)
                } else {
                    sql.append(renderExpr(expr, spaces, ctx, logger))
                }

                if (!asc) sql.append(" DESC")

                if (it.hasNext()) sql.append(", ")
            }
        }

        if (!isAggOnly) {
            val limit = key.limit ?: 10
            sql.append("\n${spaces}LIMIT $limit")
        }

        if (positional) {
            // TODO clickhouse-ism
            sql.append("\n${spaces}SETTINGS enable_positional_arguments = 1")
        }

        return sql.toString()
    }

    /**
     * Render a single expression to Clickhouse-flavoured SQL.
     */
    private fun renderExpr(expr: Expr<Any>, indent: String, ctx: RenderContext, logger: Logger): String {
        return when (expr) {
            is And -> {
                val kids = expr.children.map { renderExpr(it, indent, ctx, logger) }
                kids.joinToString(separator = " AND ", prefix = "(", postfix = ")")
            }
            is Or -> {
                val kids = expr.children.map { renderExpr(it, indent, ctx, logger) }
                kids.joinToString(separator = " OR ", prefix = "(", postfix = ")")
            }
            is Not -> {
                // TODO normalize negated comparisons, e.g. NOT(x = y) => x <> y, but beware of null semantics!
                "NOT (${renderExpr(expr.operand, indent, ctx, logger)})"
            }
            is ComparisonOp<*> -> {
                val left = renderExpr(expr.left, indent, ctx, logger)
                val right = renderExpr(expr.right, indent, ctx, logger)

                val op = when (expr.type) {
                    ExprType.EQUAL -> "="
                    ExprType.LESS -> "<"
                    ExprType.GREATER -> ">"
                    ExprType.LESS_EQUAL -> "<="
                    ExprType.GREATER_EQUAL -> ">="
                    ExprType.NOT_EQUAL -> "!="
                    else -> error("Unknown comparison operator ${expr.type}")
                }

                "$left $op $right"
            }
            is Between<*> -> {
                val op = renderExpr(expr.operand, indent, ctx, logger)
                val lower = renderExpr(expr.lower, indent, ctx, logger)
                val upper = renderExpr(expr.upper, indent, ctx, logger)

                if (expr.lowerInclusive && expr.upperInclusive) {
                    "$op BETWEEN $lower AND $upper"
                } else if (expr.lowerInclusive) {
                    "$op >= $lower AND $op < $upper"
                } else {
                    "$op > $lower AND $op <= $upper"
                }
            }
            is IsNull -> {
                val kid = renderExpr(expr.operand, indent, ctx, logger)
                "$kid IS NULL"
            }
            is IsNotNull -> {
                val kid = renderExpr(expr.operand, indent, ctx, logger)
                "$kid IS NOT NULL"
            }
            is TimestampLiteral -> {
                // TODO maybe move clickhouse-specific conversion syntax elsewhere
                if (expr.value.zone.normalized() == ZoneOffset.UTC) {
                    """parseDateTime64BestEffort('${expr.value.toInstant()}')"""
                } else {
                    """parseDateTime64BestEffort('${expr.value.toInstant()}', '${expr.value.zone.id}')"""
                }
            }
            is DateLiteral -> {
                // TODO maybe move clickhouse-specific conversion syntax elsewhere
                """toDate('${expr.value}'"""
            }
            is StringLiteral -> "'${expr.value.replace("'", "''")}'"
            is NullLiteral -> "NULL"
            is ValueLiteral<*> -> expr.value.toString()
            is GetAllFields -> "*"
            is GetField<*> -> {
                // TODO get alias from context
                // TODO qualify if not primary table
                if (expr.fieldName.contains('.')) {
                    val matchingMeta = ctx.elasticMetadatas.find { meta ->
                        // TODO we need to match wildcards here too... Or maybe resolve them earlier
                        (ctx.indices.contains(meta.indexName) || meta.indexAliases.orEmpty().any { alias -> ctx.indices.contains(alias) })
                            &&
                        (meta.columnsByPath.containsKey(expr.fieldName) || meta.aliasesByPath.containsKey(expr.fieldName))
                    }

                    if (matchingMeta == null) {
                        logger.warn("Found a nested field reference `${expr.fieldName}` but we couldn't find an elasticMetadata file to tell us how to translate it")
                        "\"${expr.fieldName}\""
                    } else {
                        val col = matchingMeta.columnsByPath[expr.fieldName]
                        val alias = matchingMeta.aliasesByPath[expr.fieldName]

                        if (col != null) {
                            val (head, tail) = if (matchingMeta.nestedFieldsConfig.topLevelFields.contains(col.path.first())) {
                                col.path.first() to col.path.drop(1).joinToString(matchingMeta.nestedFieldsConfig.pathSeparator)
                            } else {
                                matchingMeta.nestedFieldsConfig.catchAllField to col.path.joinToString(matchingMeta.nestedFieldsConfig.pathSeparator)
                            }

                            "$head['$tail']"
                        } else if (alias != null) {
                            "\"${alias.target}\"" // TODO is this right?
                        } else {
                            logger.warn("GetField '${expr.fieldName}' contained a '.' but we have no mapping for it; using it as-is 😅")
                            "\"${expr.fieldName}\""
                        }
                    }
                } else {
                    "\"${expr.fieldName}\""
                }
            }
            is CountAll -> "COUNT(*)"
            is Count -> "COUNT(${renderExpr(expr.source, indent, ctx, logger)})"
            is CountDistinct -> "COUNT(DISTINCT ${renderExpr(expr.source, indent, ctx, logger)})"
            is Sum -> "SUM(${renderExpr(expr.source, indent, ctx, logger)})"
            is Avg -> "AVG(${renderExpr(expr.source, indent, ctx, logger)})"
            is Min -> "MIN(${renderExpr(expr.source, indent, ctx, logger)})"
            is Max -> "MAX(${renderExpr(expr.source, indent, ctx, logger)})"
            is IntervalLiteral -> "INTERVAL ${expr.count} ${expr.unit}"
            is Call -> {
                expr.params.joinToString(prefix = "${expr.name}(", separator = ", ", postfix =  ")") {
                    renderExpr(it, indent, ctx, logger)
                }
            }
            is FieldMatchPred -> {
                val fieldNames = when (val t = expr.target) {
                    is SelectedFields -> t.fieldNames
                    is OneField -> listOf(t.fieldName)
                    is DefaultFields -> ctx.defaultFields[ctx.primaryTable].orEmpty()
                    is FieldNameWildcard -> {
                        // TODO lookup matching fields from metadata
                        // TODO make this an OR with the matching fields
                        TODO("Can't get wildcard fields, no metadata!")
                    }
                }

                return renderExpr(renderListOfValues(fieldNames, expr.value), indent, ctx, logger)
            }
            is StartsWith -> {
                if (expr.needle !is StringLiteral) error("STARTS_WITH operator: `needle` must be a String Literal")
                "${renderExpr(expr.haystack, indent, ctx, logger)} LIKE '${expr.needle.value.replace("'", "''")}%'"
            }
            is EndsWith -> {
                if (expr.needle !is StringLiteral) error("ENDS_WITH operator: `needle` must be a String Literal")
                "${renderExpr(expr.haystack, indent, ctx, logger)} LIKE '%${expr.needle.value.replace("'", "''")}'"
            }
            is ContainsTerm -> {
                if (expr.needle !is StringLiteral) error("CONTAINS operator: `needle` must be a String Literal")
                "${renderExpr(expr.haystack, indent, ctx, logger)} LIKE '%${expr.needle.value.replace("'", "''")}%'"
            }
            is In<*> -> {
                // TODO stringify and quote according to value type, not just .toString()
                val rhs = when (expr.haystack) {
                    is MultiValueLiteral<*, *> -> {
                        expr.haystack.unwrap().joinToString(separator = ", ") { renderExpr(it, indent, ctx, logger) }
                    }
                    is TopK<*> -> {
                        // TODO contorting the query into what's needed for the "other" buckets here would be an
                        //  egregious layering violation, so not yet

                        val k = expr.haystack.k
                        val expr = expr.haystack.expr

                        """SELECT _key FROM (
                           |$indent    SELECT 
                           |$indent      ${renderExpr(expr, indent, ctx, logger)} as _key, 
                           |$indent      COUNT(*) as _count 
                           |$indent    FROM ${ctx.primaryTable}
                           |$indent    GROUP BY _key
                           |$indent    ORDER BY _count DESC
                           |$indent    LIMIT $k
                           |$indent  )""".trimMargin()
                    }
                    else -> error("In.haystack must be a MultiValueLiteral or TopK (for now?)")
                }

                "${renderExpr(expr.needle, indent, ctx, logger)} IN ($rhs)"
            }
            is SymOp<*> -> {
                "${renderExpr(expr.left, indent, ctx, logger)} ${expr.sym} ${renderExpr(expr.right, indent, ctx, logger)}"
            }
            is IntDiv<*> -> {
                // TODO ClickHouse-ism
                "IntDiv(${renderExpr(expr.left, indent, ctx, logger)}, ${renderExpr(expr.right, indent, ctx, logger)})"
            }
            is Case<*> -> {
                val spaces = "$indent$indent"
                val tests = expr.tests
                val results = expr.results

                val clauses = tests.zip(results).mapIndexed { i, (test, result) ->
                    val resultClause = renderExpr(result, indent, ctx, logger)

                    // TODO check that the ELSE is last?
                    if (test == BooleanLiteral.True && i != 0) {
                        "ELSE $resultClause"
                    } else {
                        val testClause = renderExpr(test, indent, ctx, logger)
                        "WHEN $testClause THEN $resultClause"
                    }
                }

                "CASE${clauses.joinToString(prefix="\n$spaces", separator="\n$spaces")}\n${spaces}END"
            }

            is Like -> {
                if (expr.match !is StringLiteral) error("LIKE clause must be a String literal")

                val match = expr.match.value.replace("'", "''")

                "${renderExpr(expr.operand, indent, ctx, logger)} LIKE '$match'"
            }

            is MovingAgg<*> -> {
                val src = renderExpr(expr.source, indent, ctx, logger)

                "${expr.sqlName}($src) OVER (ROWS BETWEEN ${expr.window} PRECEDING AND CURRENT ROW)"
            }
            else -> TODO("TODO render expression $expr")
        }
    }

    // TODO I think sometimes the outer context can send a hint about whether this should be AND or OR:
    //  https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-multi-match-query.html#operator-min
    private fun renderListOfValues(fieldNames: List<String>, lov: ListOfValues): Expr<Boolean> {
        return when (lov) {
            is AnyValue -> {
                or(fieldNames, ValueType.ANY) { IsNotNull(it) }
            }
            is SingleValue -> {
                // TODO can comparisons show up here?
                when (lov.value) {
                    is StringLiteral -> renderWildcard(fieldNames, lov.value)
                    is DoubleLiteral -> or(fieldNames, ValueType.DOUBLE)    { Equal(it, lov.value) }
                    is FloatLiteral -> or(fieldNames, ValueType.FLOAT)     { Equal(it, lov.value) }
                    is IntLiteral -> or(fieldNames, ValueType.INT)       { Equal(it, lov.value) }
                    is LongLiteral -> or(fieldNames, ValueType.LONG)      { Equal(it, lov.value) }
                    is BooleanLiteral -> or(fieldNames, ValueType.BOOLEAN)   { Equal(it, lov.value) }
                    is TimestampLiteral -> or(fieldNames, ValueType.TIMESTAMP) { Equal(it, lov.value) }
                    is DateLiteral -> or(fieldNames, ValueType.DATE)      { Equal(it, lov.value) }
                    else -> error("Unsupported literal ${lov.value}")
                }
            }
            is NotValues -> {
                Not(renderListOfValues(fieldNames, lov.value))
            }
            is OrValues -> {
                Or(lov.values.map { renderListOfValues(fieldNames, it) })
            }
            is AndValues -> {
                And(lov.values.map { renderListOfValues(fieldNames, it) })
            }
            is BetweenValues<*> -> {
                or(fieldNames, lov.valueType) {
                    Between(it, lov.lower, lov.upper)
                }
            }
        }
    }

    private fun renderWildcard(fieldNames: List<String>, sl: StringLiteral): Expr<Boolean> {
        val value = sl.value.trim()
        return if (value == "*") {
            or(fieldNames, ValueType.STRING) { IsNotNull(it) }
        } else if (value.startsWith("*") && value.endsWith("*")) {
            or(fieldNames, ValueType.STRING) { ContainsTerm(it, StringLiteral(value.drop(1).dropLast(1))) }
        } else if (value.endsWith("*")) {
            or(fieldNames, ValueType.STRING) { StartsWith(it, StringLiteral(value.dropLast(1))) }
        } else if (value.startsWith("*")) {
            or(fieldNames, ValueType.STRING) { EndsWith(it, StringLiteral(value.drop(1))) }
        } else {
            or(fieldNames, ValueType.STRING) { Equal(it, sl) }
        }
    }

    private fun <T : Any> or(fieldNames: List<String>, vt: ValueType, f: (GetField<T>) -> Expr<Boolean>): Or {
        val getFields = fieldNames.map { GetField<T>(it, vt) }
        val notNulls = getFields.map(f)
        return Or(notNulls)
    }
}
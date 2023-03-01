package io.hydrolix.ketchup.model.query

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import io.hydrolix.ketchup.model.expr.*
import kotlin.reflect.KClass

/**
 * This is intended to contain everything that, if anything differed, would make a pair of queries incompatible.
 * Everything that's not thought to be so essential (in practice, just projections) is in the containing [IRQuery].
 *
 * @param table     the name of the table being queried
 * @param groupBys  pairs of (group by expression, predicate to filter group-by values)
 * @param orderBys  pairs of (order-by expression, true if ascending)
 * @param predicate WHERE clause logic for the query as a whole
 * @param limit     LIMIT clause (TODO offsets?)
 */
data class IRQueryKey(
    val table: String,
    val groupBys: List<Pair<Expr<*>, Expr<Boolean>>>,
    val orderBys: List<Pair<Expr<*>, Boolean>>, // `true` means ascending
    val predicate: Expr<Boolean>,
    val limit: Int? = null,
    // TODO anything else that makes queries mutually incompatible?
)

/**
 * Information about an aggregate projection from query planning time that will be needed to generate output
 * for it correctly
 *
 * @param alias the unique alias for this aggregate projection
 * @param valueType the value type for this projection
 * @param agg the original elastic Aggregation for this aggregate projection
 */
data class AggInfo(
    val alias: String,
    val valueType: ValueType,
    val agg: Aggregation?,
)

/**
 * A homegrown intermediate representation for SQL-ish queries. Probably wrong.
 *
 * @param key the part of the query that makes it essentially distinct from other queries (i.e. everything but projections)
 * @param projections all the projections in this query; values are the aliases. Keyed by expression for dedupe purposes.
 * @param aggProjs lookup table from Elastic aggregation paths to [AggInfo] wrappers
 */
data class IRQuery(
    val key: IRQueryKey,
    val projections: Map<Expr<*>, String>, // TODO it might be nice to preserve order here but there's no good ImmutableLinkedMap
    val aggProjs: Map<List<String>, AggInfo>,
) {
    val projsByAlias = projections.map { (k, v) -> v to k }.toMap()
    val aliases = (projections.values.toSet() + aggProjs.values.map { it.alias }).toSet()
    val isAggOnly = key.groupBys.isNotEmpty() || projections.keys.all { it is AggExpr<*> }

    private fun uniq(preferredName: String): String {
        var name = preferredName
        var i = 1 // 1-based because ['foo', 'foo2'] smells nicer than ['foo', 'foo1']
        while (aliases.contains(name)) {
            i += 1
            name = "$preferredName$i"
        }
        return name
    }

    /**
     * Adds a projection to this query, returning the updated query plus the unique name for the projection
     *
     * @param expr the expression to be projected (e.g. a [GetField])
     * @param preferredName a preferred alias; will be used as-is if unique, or appended with numbers until it is
     */
    fun withProjection(expr: Expr<*>, preferredName: String): Pair<IRQuery, String> {
        val existing = projections[expr]

        if (existing != null) {
            return this to existing
        }

        val name = uniq(preferredName)

        return this.copy(
            projections = this.projections + (expr to name)
        ) to name
    }

    /**
     * Adds an aggregate to this query, returning the updated query plus the unique name for the projection
     *
     * @param expr the aggregation expression to be projected (e.g. a [Sum])
     * @param agg the Elastic [Aggregation] that this projection is for, or null if it's the special case where `aggs: {}, size: 0`
     * @param path the original path of this aggregation in the incoming request (e.g. [["1", "2"]])
     * @param preferredName a preferred alias; will be used as-is if unique, or appended with numbers until it is
     */
    fun withAgg(
        expr: Expr<*>,
        agg: Aggregation?,
        path: List<String>,
        preferredName: String
    ): Pair<IRQuery, String> {
        val existing = projections[expr]

        if (existing != null) {
            // TODO is this always correct?
            return this to existing
        }

        val name = uniq(preferredName)

        val existingPath = aggProjs[path]
        if (existingPath != null) {
            // TODO what if the path matches but the expression doesn't? Should be an error?
            return this to existingPath.alias
        }

        val newAliases = projections[expr].orEmpty() + name

        return this.copy(
            projections = this.projections + (expr to newAliases),
            aggProjs = this.aggProjs + (path to AggInfo(name, expr.valueType, agg))
        ) to name
    }

    /**
     * Adds a sort key to this query
     *
     * @param expr the expression to sort by, e.g. a [GetField]
     * @param ascending `true` if the sort is in ascending order, or `false` if it's descending
     */
    fun withOrderBy(expr: Expr<Any>, ascending: Boolean, preferredName: String): IRQuery {
        return this.copy(
            key = this.key.copy(
               orderBys = this.key.orderBys + listOf(expr to ascending)
            )
        ).withProjection(expr, preferredName).first
    }

    /**
     * Adds a GROUP BY clause to this query
     *
     * @param expr          the expression to group by, e.g. a [GetField]
     * @param preferredName the preferred name for the projection; if not unique will have numbers appended to it.
     */
    fun withGroupBy(expr: Expr<Any>, preferredName: String, cond: Expr<Boolean>): IRQuery {
        return this.copy(key = key.copy(
            groupBys = key.groupBys + listOf(expr to cond)
        )).withProjection(expr, preferredName).first
    }

    @Suppress("UNCHECKED_CAST")
    fun <E : Expr<*>> go(expr: Expr<*>, cls: KClass<*>, f: (E) -> Unit) {
        if (cls.isInstance(expr)) {
            f(expr as E)
        }
    }

    inline fun <reified E : Expr<*>> visitExprs(noinline f: (E) -> Unit) {
        val cls = E::class

        go(key.predicate, cls, f)

        key.predicate.children().forEach {
            go(it, cls, f)
        }

        key.groupBys.forEach { (k, v) ->
            go(k, cls, f)
            k.children().forEach {
                go(it, cls, f)
            }

            go(v, cls, f)
            v.children().forEach {
                go(it, cls, f)
            }
        }
    }
}

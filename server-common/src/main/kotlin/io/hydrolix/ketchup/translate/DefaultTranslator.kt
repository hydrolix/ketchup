package io.hydrolix.ketchup.translate

import arrow.core.Either
import co.elastic.clients.elasticsearch._types.SortOptions
import co.elastic.clients.elasticsearch._types.SortOrder
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval
import co.elastic.clients.elasticsearch._types.query_dsl.FieldAndFormat
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.util.NamedValue
import io.hydrolix.ketchup.kql.scalaparsing.FastparseKql
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.kql.*
import io.hydrolix.ketchup.model.opensearch.NamedIndexPattern
import io.hydrolix.ketchup.model.transform.TimeFormatType
import io.hydrolix.ketchup.model.query.IRQuery
import io.hydrolix.ketchup.model.query.IRQueryKey
import io.hydrolix.ketchup.model.translate.QueryTranslator
import io.hydrolix.ketchup.model.translate.TranslationResult
import io.hydrolix.ketchup.server.ProxyConfig
import io.hydrolix.ketchup.server.identifyDispatchMapping
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2

enum class UntranslatableReason {
    KqlParse,
    UnsupportedAgg,
    UnsupportedSortKey,
    UnsupportedQuery,
    Other
}

class Untranslatable(
    val reason: UntranslatableReason,
    val info: String? = null,
    cause: Throwable? = null
) : RuntimeException("Untranslatable because $reason${info?.let { ": $it" } ?: ""}", cause)

class DefaultTranslator(
    val config: ProxyConfig,
) : QueryTranslator {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun translate(reqs: LinkedHashMap<UUID, Pair<SearchRequest, String?>>): List<TranslationResult> {
        val res = mutableListOf<TranslationResult>()

        for ((reqId, reqAndIndex) in reqs) {
            val (req, indexOverride) = reqAndIndex
            val indices = req.index().toSet() + indexOverride
            require (indices.size == 1) { "TODO search requests must query a single index for now (got ${indices})" }
            val index = indices.first()!!
            // TODO refactor this so the caller decides how to dispatch the requests
            val mapping = identifyDispatchMapping(config.dbMappingsByIndex, config.dbMappingsById, NamedIndexPattern(index), logger) ?: throw Untranslatable(UntranslatableReason.Other, "Couldn't identify DBMapping for index $index")

            val pred = handleQuery(req.query(), mapping).simplify()
            if (req.searchAfter().isNotEmpty()) throw Untranslatable(UntranslatableReason.UnsupportedQuery, "search_after is not supported yet")

            var nullQuery = IRQuery(
                IRQueryKey(
                    mapping.clickHouseTableName,
                    emptyList(),
                    emptyList(),
                    pred,
                    null
                ),
                emptyMap(),
                emptyMap()
            )

            val orderBys = req.sort().mapNotNull {
                when (it._kind()) {
                    SortOptions.Kind.Field -> {
                        val field = it.field()
                        GetField<Any>(field.field(), ValueType.ANY) to (field.order() == null || field.order() == SortOrder.Asc)
                    }
                    SortOptions.Kind.Doc -> null // No-op
                    else -> throw Untranslatable(UntranslatableReason.UnsupportedSortKey, "${it._kind()}: $it")
                }
            }

            for ((expr, asc) in orderBys) {
                nullQuery = nullQuery.withOrderBy(expr, asc, expr.fieldName)
            }

            var key: IRQueryKey? = null
            var query: IRQuery? = null

            // TODO do we want to distinguish between fields and docvalueFields here?
            val sourceFields = req.source()?.let { source ->
                if (source.isFetch) {
                    if (source.fetch()) listOf(FieldAndFormat.of {
                        it.field("*")
                    }) else emptyList()
                } else if (source.isFilter) {
                    source.filter().includes().map { field ->
                        FieldAndFormat.of {
                            it.field(field)
                        }
                    }
                } else error("_source was neither fetch nor filter?!")
            }

            val projs = (sourceFields.orEmpty() + req.fields().orEmpty() + req.docvalueFields().orEmpty()).map {
                // TODO it would be better to keep this special case in renderExpr, maybe pass an alias in there?
                if (it.field() == "*") {
                    GetAllFields to it.field()
                } else {
                    GetField<Any>(it.field(), ValueType.ANY) to it.field()
                }
            }

            val aggs = req.aggregations()
            when {
                aggs.isEmpty() && req.size() == 0 -> {
                    // Special-case COUNT(*) when aggs:{} and size:0 -- also special-cased in result rendering
                    key = IRQueryKey(mapping.clickHouseTableName, emptyList(), orderBys, pred, null)
                    query = IRQuery(
                        key,
                        emptyMap(),
                        emptyMap()
                    ).withAgg(
                        CountAll,
                        null,
                        listOf("1"),
                        "count"
                    ).first
                }
                aggs.isEmpty() -> {
                    // Always add a projection for the ID column if there are no aggs
                    req.size()?.also {
                        if (it > 10000) throw Untranslatable(UntranslatableReason.UnsupportedQuery, "size was greater than 10,000")
                    }

                    key = IRQueryKey(mapping.clickHouseTableName, emptyList(), orderBys, pred, req.size())
                    query = IRQuery(key, linkedMapOf(*projs.toTypedArray()), emptyMap())
                        .withProjection(
                            GetField<String>(mapping.idFieldName, ValueType.STRING), // TODO always string? Use metadata!
                            mapping.idFieldName
                        ).first
                }
                else -> {
                    for ((name, agg) in aggs) {
                        val aggQ = handleAgg(mapping, listOf(name), agg, nullQuery)

                        if (key == null || query == null) {
                            key = aggQ.key
                            query = aggQ
                        } else if (aggQ.key == key) {
                            // TODO merge the aggQs with our WIP query
                            query = query.copy(
                                projections = query.projections + aggQ.projections,
                                aggProjs = query.aggProjs + aggQ.aggProjs
                            )
                        } else {
                            TODO("Need to fork this query?")
                        }
                    }
                }
            }

            res += TranslationResult(
                mapping.clickHouseConnectionId,
                query!!,
                index,
                setOf(reqId)
            )
        }

        return res
    }

    internal fun handleAgg(
        mapping: DBMapping,
        path: List<String>,
        agg: Aggregation,
        parent: IRQuery
    ): IRQuery {
        return when (agg._kind()) {
            Aggregation.Kind.DateHistogram -> {
                DateHistogramTranslator.translate(path, mapping, agg.dateHistogram(), agg.aggregations(), parent, this, logger)
            }
            Aggregation.Kind.Terms -> {
                TermsTranslator.translate(path, mapping, agg.terms(), agg.aggregations(), parent, this, logger)
            }
            Aggregation.Kind.Filters -> {
                FiltersAggregationTranslator.translate(path, mapping, agg.filters(), agg.aggregations(), parent, this, logger)
            }
            Aggregation.Kind.MovingFn -> {
                MovingFnAggregationTranslator.translate(path, mapping, agg.movingFn(), agg.aggregations(), parent, this, logger)
            }
            else -> {
                val (expr, prefName) = handleSimpleAgg(agg)
                parent.withAgg(expr, agg, path, prefName).first
            }
        }
    }

    internal fun processOrderBy(
        orders: List<NamedValue<SortOrder>>,
        keyExpr: Expr<*>,
        parent: IRQuery,
        path: List<String>
    ): IRQuery {
        val orderBysOut = mutableListOf<Triple<Expr<*>, Boolean, String>>()

        for (ordEl in orders) {
            val name = ordEl.name()
            val asc = ordEl.value() == SortOrder.Asc

            orderBysOut += when (name) {
                "_key" -> Triple(keyExpr, asc, "key")
                "_count" -> Triple(CountAll, asc, "count")
                else -> {
                    require(!name.contains('.')) { "Terms aggregation wants to sort by a >1 element path" }
                    val info = parent.aggProjs[path + name] ?: error("Terms aggregation refers to nonexistent aggregation $path.$name")
                    val expr = parent.projsByAlias[info.alias] ?: error("Terms aggregation refers to unknown alias $info")

                    Triple(expr, asc, name)
                }
            }
        }

        var out = parent
        for ((expr, asc, name) in orderBysOut) {
            out = out.withOrderBy(expr, asc, name)
        }

        return out
    }

    internal fun handleSimpleAgg(
        agg: Aggregation
    ): Pair<Expr<*>, String> {
        // TODO check the field type, sometimes these will be radically different... e.g. value_count over a histogram is the sum of the counts

        return when (agg._kind()) {
            Aggregation.Kind.Min -> Min(GetField(agg.min().field()!!, ValueType.ANY)) to "min_${agg.min().field()}"
            Aggregation.Kind.Max -> Max(GetField(agg.max().field()!!, ValueType.ANY)) to "max_${agg.max().field()}"
            Aggregation.Kind.Avg -> Avg(GetField(agg.avg().field()!!, ValueType.ANY)) to "avg_${agg.avg().field()}"
            Aggregation.Kind.Sum -> Sum(GetField(agg.sum().field()!!, ValueType.ANY)) to "sum_${agg.sum().field()}"
            Aggregation.Kind.ValueCount -> Count(GetField<Any>(agg.valueCount().field()!!, ValueType.ANY)) to "count_${agg.valueCount().field()}"
            Aggregation.Kind.Cardinality -> {
                // TODO handle precisionThreshold or just always be precise?
                CountDistinct(GetField<Any>(agg.cardinality().field()!!, ValueType.ANY)) to "distinct_${agg.cardinality().field()}"
            }
            else -> {
                throw Untranslatable(UntranslatableReason.UnsupportedAgg, agg._kind().name)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultTranslator::class.java)

        internal fun handleQuery(query: Query?, mapping: DBMapping?): Expr<Boolean> {
            return when (query?._kind()) {
                null -> BooleanLiteral.True
                Query.Kind.MatchAll -> BooleanLiteral.True
                Query.Kind.MatchNone -> BooleanLiteral.False
                Query.Kind.Exists -> Not(IsNull(GetField(query.exists().field(), ValueType.ANY)))

                Query.Kind.Bool -> {
                    val bool = query.bool()
                    val filters = bool.filter().map { handleQuery(it, mapping) }
                    val musts = bool.must().map { handleQuery(it, mapping) }
                    val mustNots = bool.mustNot().map { handleQuery(it, mapping) }
                    val shoulds = bool.should().map { handleQuery(it, mapping) }

                    And(
                        filters + musts + mustNots.map { Not(it) } + Or(shoulds)
                    ).simplify()
                }

                Query.Kind.Range -> {
                    val range = query.range()
                    val fmt = range.format()

                    val fromS = range.from()
                    val toS = range.to()
                    val gtS = range.gt()?.to(String::class.java)
                    val gteS = range.gte()?.to(String::class.java)
                    val ltS = range.lt()?.to(String::class.java)
                    val lteS = range.lte()?.to(String::class.java)

                    val get = GetField<Any>(range.field(), ValueType.ANY)

                    val tft = fmt?.let { TimeFormatType.formats[it] }

                    if (tft != null && fromS != null && toS != null) {
                        val loExpr = timestampLiteral(tft, fromS)
                        val hiExpr = timestampLiteral(tft, toS)

                        Between(get, loExpr, hiExpr)
                    } else if (tft != null) {
                        mkRange(gtS, get, gteS, ltS, lteS) { timestampLiteral(tft, it) }
                    } else {
                        mkRange(gtS, get, gteS, ltS, lteS) { Literal.of(it) }
                    }
                }

                Query.Kind.MatchPhrase -> {
                    val matchPhrase = query.matchPhrase()
                    val lit = Literal.of(matchPhrase.query())
                    Equal(
                        GetField(matchPhrase.field(), lit.valueType),
                        lit
                    )
                }

                Query.Kind.Match -> {
                    val match = query.match()
                    require (match.analyzer() == null) { throw Untranslatable(UntranslatableReason.Other, "match query with analyzer ${match.analyzer()}") }
                    require (match.fuzziness() == null || match.fuzziness() == "0") { throw Untranslatable(UntranslatableReason.Other, "match query with fuzziness ${match.fuzziness()}") }
                    match.operator() // TODO
                    val lit = Literal.of(match.query())
                    Equal(
                        GetField(match.field(), lit.valueType),
                        lit
                    )
                }

                Query.Kind.QueryString -> {
                    val queryString = query.queryString()
                    return if (queryString.query() == "*") {
                        // TODO check for other disqualifying features too
                        BooleanLiteral.True
                    } else {
                        when (val res = FastparseKql.parseKql(queryString.query())) {
                            is Either.Left -> throw Untranslatable(UntranslatableReason.KqlParse, queryString.query())
                            is Either.Right -> {
                                val fields = listOfNotNull(queryString.defaultField()) + queryString.fields()

                                populateDefaultFields(res.value, fields)
                            }
                        }
                    }
                }

                Query.Kind.MultiMatch -> {
                    val multiMatch = query.multiMatch()
                    // TODO there will almost certainly be more complexity here, e.g. prefix matches
                    // TODO there will almost certainly be more complexity here, e.g. prefix matches
                    // TODO there will almost certainly be more complexity here, e.g. prefix matches
                    val queryValues = multiMatch.query()?.let {
                        SingleValue(Literal.of(it))
                    } ?: AnyValue

                    // TODO handle this
                    // TODO handle this
                    // TODO handle this
                    when (multiMatch.type()) {
                        TextQueryType.BestFields -> {}
                        TextQueryType.MostFields -> {}
                        TextQueryType.BoolPrefix -> {}
                        TextQueryType.CrossFields -> {}
                        TextQueryType.Phrase -> {}
                        TextQueryType.PhrasePrefix -> {}
                        else -> {}
                    }

                    // TODO handle this
                    // TODO handle this
                    // TODO handle this
                    when (multiMatch.operator()) {
                        Operator.And -> {}
                        Operator.Or -> {}
                        else -> {} // TODO should it default to And or Or?
                    }

                    val fields = multiMatch.fields()
                    val target = when (fields.size) {
                        0 -> DefaultFields
                        1 -> {
                            val fieldName = fields.first()
                            if (fieldName.contains("*")) {
                                logger.warn("TODO `multi_match` query used a field name with a wildcard in it, we need to look at the Clickhouse schema: $fieldName")
                            }
                            OneField(fields.first())
                        }
                        else -> {
                            if (fields.any { it.contains("*") }) {
                                logger.warn("TODO `multi_match` query used a field name with a wildcard in it, we need to look at the Clickhouse schema: $fields")
                            }

                            SelectedFields(fields)
                        }
                    }

                    FieldMatchPred(target, queryValues)
                }

                Query.Kind.Ids -> {
                    val ids = query.ids()

                    val idField = mapping?.idFieldName ?: throw Untranslatable(UntranslatableReason.Other, "Can't use an `ids` query without a mapping")
                    In(GetField(idField, ValueType.ANY), MultiStringLiteral(ids.values()))
                }

                Query.Kind.Wildcard -> {
                    val wildcard = query.wildcard()

                    if (wildcard.rewrite() != null) throw Untranslatable(UntranslatableReason.Other, "Wildcard query had `rewrite` parameter")
                    if (wildcard.boost() != null) throw Untranslatable(UntranslatableReason.Other, "Wildcard query had `boost` parameter")
                    if (wildcard.caseInsensitive() == true) throw Untranslatable(UntranslatableReason.Other, "Wildcard query had `case_insensitive` parameter")

                    val fieldName = wildcard.field()
                    val value = wildcard.value() ?: wildcard.wildcard() ?: throw Untranslatable(UntranslatableReason.Other, "Wildcard query had neither `value` nor `wildcard`")
                    if (value.contains('\\')) {
                        // TODO consider supporting these, but it's painful enough that we should just not attempt it for now
                        //  e.g. https://lucene.apache.org/core/3_4_0/queryparsersyntax.html#Escaping%20Special%20Characters
                        throw Untranslatable(UntranslatableReason.Other, "TODO wildcard value contains a `\\`: $value")
                    }

                    val likeClause = StringBuffer()

                    for (c in value) {
                        when (c) {
                            '*' -> likeClause.append('%') // Elastic `*` to SQL `%`
                            '?' -> likeClause.append('_') // Elastic `?` to SQL `_`
                            '%' -> likeClause.append("\\%") // Natural `%` escaped for SQL
                            '_' -> likeClause.append("\\_") // Natural `_` escaped for SQL
                            else -> likeClause.append(c) // All other characters are A-OK! Trust me!
                        }
                    }

                    Like(GetField(fieldName, ValueType.STRING), StringLiteral(likeClause.toString()))
                }

                Query.Kind.ConstantScore -> {
                    val cs = query.constantScore()
                    // TODO we discard `boost` here
                    handleQuery(cs.filter(), mapping)
                }

                else -> throw Untranslatable(UntranslatableReason.UnsupportedQuery, query._kind().name)
            }
        }

        /**
         * Update the anonymous predicates coming from a purely local KQL/Lucene query string with whatever field
         * names are available from context (`default_field` and/or `fields`)
         */
        private fun populateDefaultFields(expr: Expr<Boolean>, fields: List<String>): Expr<Boolean> {
            if (fields.isEmpty()) return expr

            return when (expr) {
                is And -> And(expr.children.map { populateDefaultFields(it, fields) })
                is Or -> Or(expr.children.map { populateDefaultFields(it, fields) })
                is Not -> Not(populateDefaultFields(expr.operand, fields))
                is FieldMatchPred -> {
                    val newTarget = when (expr.target) {
                        is DefaultFields -> SelectedFields(fields)
                        else -> expr.target
                    }
                    FieldMatchPred(newTarget, expr.value)
                }
                else -> expr
            }
        }

        internal fun CalendarInterval.toClickHouse(): String =
            when (this) {
                CalendarInterval.Year -> "year"
                CalendarInterval.Quarter -> "quarter"
                CalendarInterval.Month -> "month"
                CalendarInterval.Day -> "day"
                CalendarInterval.Week -> "week"
                CalendarInterval.Hour -> "hour"
                CalendarInterval.Minute -> "minute"
                CalendarInterval.Second -> "second"
                else -> throw Untranslatable(UntranslatableReason.Other, "Unsupported calendar interval unit: $this")
            }

        internal fun TimeUnit.toClickHouse(): String {
            return when (this) {
                TimeUnit.SECONDS -> "second"
                TimeUnit.MINUTES -> "minute"
                TimeUnit.HOURS -> "hour"
                TimeUnit.DAYS -> "day"
                else -> throw Untranslatable(UntranslatableReason.Other, "No ClickHouse interval unit for $this")
            }
        }

        private fun mkRange(
            gtS: String?,
            get: GetField<Any>,
            gteS: String?,
            ltS: String?,
            lteS: String?,
            f: (String) -> Expr<*>
        ): And {
            val left1 = gtS?.let { Greater(get, f(it)) }
            val left2 = gteS?.let { GreaterEqual(get, f(it)) }
            val lefts = listOfNotNull(left1, left2)
            val right1 = ltS?.let { Less(get, f(it)) }
            val right2 = lteS?.let { LessEqual(get, f(it)) }
            val rights = listOfNotNull(right1, right2)

            if (lefts.size > 1) throw Untranslatable(UntranslatableReason.Other, "Range query had both `gt` and `gte`?!")
            if (rights.size > 1) throw Untranslatable(UntranslatableReason.Other, "Range query had both `lt` and `lte`?!")
            if (lefts.isEmpty() && rights.isEmpty()) throw Untranslatable(UntranslatableReason.Other, "Range query had neither lower nor upper bound?!")

            return And(
                listOfNotNull(
                    lefts.singleOrNull(),
                    rights.singleOrNull()
                )
            )
        }

        private fun timestampLiteral(
            tft: TimeFormatType,
            fromS: String
        ) = TimestampLiteral(ZonedDateTime.ofInstant(tft.toInstant(fromS)!!, ZoneOffset.UTC))

        private fun <A : Any> comparison(field: GetField<A>, lo: Expr<A>, hi: Expr<A>, includeUpper: Boolean, includeLower: Boolean): Expr<Boolean> {
            return if (includeUpper && includeLower) {
                Between(field, lo, hi)
            } else if (includeLower) {
                And(listOf(
                    GreaterEqual(field, lo),
                    Less(field, hi)
                ))
            } else {
                And(listOf(
                    Greater(field, lo),
                    LessEqual(field, hi)
                ))
            }
        }
    }
}

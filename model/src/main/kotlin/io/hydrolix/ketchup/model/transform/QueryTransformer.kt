package io.hydrolix.ketchup.model.transform

import co.elastic.clients.elasticsearch._types.query_dsl.*
import org.slf4j.LoggerFactory
import kotlin.reflect.full.allSuperclasses

interface QueryTransformer {
    /** return true if this transformer wants to handle this query */
    fun qualify(query: Query): Boolean
    /** return the transformed query, or throw I guess */
    fun transform(query: Query): Query

    /** Avoids callers needing to cast to Q. Leaves `query` unchanged if not qualified. */
    fun qualifyAndTransform(query: Query): Query {
        if (!qualify(query)) return query
        return transform(query)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(QueryTransformer::class.java)

        /**
         * A transformer that matches everything, and leaves it unchanged
         */
        object Identity : QueryTransformer {
            override fun qualify(query: Query): Boolean = true

            override fun transform(query: Query): Query = query
        }

        // TODO the order of these probably needs to be micromanaged... most to least specific?
        // TODO discover these with ServiceLoader instead of hardcoding the list, but then how to order by specificity?
        val DefaultTransformers = listOf(Identity)

        fun transform(query: Query, transformers: List<QueryTransformer> = DefaultTransformers): Query {
            return transform0(query, transformers)
        }

        private fun transform0(
            query: Query,
            transformers: List<QueryTransformer>
        ): Query {
            // First run any eligible in-place transformers
            var xq = query

            // TODO try to sort the superclasses so progressive transforms behave more predictably
            for (clazz in listOf(query::class) + query::class.allSuperclasses) {
                for (xf in transformers) {
                    xq = xf.qualifyAndTransform(xq)
                }
            }

            // Now handle children recursively
            return when (xq._kind()) {
                Query.Kind.Bool -> {
                    val bool = xq.bool()

                    val xFilter = bool.filter().map { transform0(it, transformers) }
                    val xMust = bool.must().map { transform0(it, transformers) }
                    val xMustNot = bool.mustNot().map { transform0(it, transformers) }
                    val xShould = bool.should().map { transform0(it, transformers) }

                    Query(BoolQuery.Builder().apply {
                        boost(bool.boost())
                        filter(xFilter)
                        must(xMust)
                        mustNot(xMustNot)
                        should(xShould)
                        minimumShouldMatch(bool.minimumShouldMatch())
                    }.build())
                }
                Query.Kind.ConstantScore -> {
                    val csq = xq.constantScore()

                    Query(ConstantScoreQuery.Builder().apply {
                        filter(transform0(csq.filter(), transformers))
                        boost(csq.boost())
                    }.build())
                }
                Query.Kind.DisMax -> {
                    val dmq = xq.disMax()

                    val xInner = dmq.queries().map { transform0(it, transformers) }

                    Query(DisMaxQuery.Builder().apply {
                        dmq.boost()?.also { boost(it) }
                        dmq.tieBreaker()?.also { tieBreaker(it) }
                        queries(xInner)
                    }.build())
                }
                Query.Kind.Nested -> {
                    val nq = xq.nested()

                    Query(NestedQuery.Builder().apply {
                        path(nq.path())
                        query(transform0(nq.query(), transformers))
                        boost(nq.boost())
                        ignoreUnmapped(nq.ignoreUnmapped())
                        innerHits(nq.innerHits())
                    }.build())
                }
                Query.Kind.Boosting -> {
                    val bq = xq.boosting()

                    val xPositive = transform0(bq.positive(), transformers)
                    val xNegative = transform0(bq.negative(), transformers)

                    Query(BoostingQuery.Builder().apply {
                        positive(xPositive)
                        negative(xNegative)
                        boost(bq.boost())
                        negativeBoost(bq.negativeBoost())
                    }.build())
                }
                else -> {
                    val variant = xq._get() as QueryVariant
                    if (needParsing.contains(variant.javaClass)) {
                        logger.info("TODO we might actually want to transform a ${xq.javaClass.simpleName} but it would need to be parsed first; leaving it alone for now")
                    }

                    // TODO what other query types can have children?

                    // TODO more flavours of SpanQuery?

                    xq
                }
            }
        }

        private val needParsing = setOf<Class<out QueryVariant>>(
            ScriptQuery::class.java,
            QueryStringQuery::class.java,
            WrapperQuery::class.java,
        )
    }
}

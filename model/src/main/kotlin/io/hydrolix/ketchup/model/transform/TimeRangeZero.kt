package io.hydrolix.ketchup.model.transform

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery
import io.hydrolix.ketchup.model.transform.TimeFormatType.Companion.formats
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * A QueryTransformer that finds time ranges and resets their upper and lower bounds to [Instant.EPOCH].
 */
object TimeRangeZero : QueryTransformer {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun qualify(query: Query): Boolean {
        return query._kind() == Query.Kind.Range
    }

    override fun transform(query: Query): Query {
        val range = query.range()
        val truncator = when (val fmt = formats[range.format()]) {
            null -> {
                logger.warn("Don't know how to zero format ${range.format()}; leaving query unchanged")
                return query
            }
            else -> fmt.toZero
        }

        val xFrom = truncator(range.from()).toString()
        val xTo = truncator(range.to()).toString()

        // TODO fromInclusive/toInclusive become gte/lte?
        return Query(RangeQuery.Builder().apply {
            field(range.field())
            format(range.format())
            from(xFrom)
            to(xTo)
            boost(range.boost())
            range.timeZone()?.also { timeZone(it) }
        }.build())
    }
}

package io.hydrolix.ketchup.translate

import co.elastic.clients.elasticsearch._types.Time
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramAggregation
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.model.expr.*
import io.hydrolix.ketchup.model.query.IRQuery
import io.hydrolix.ketchup.server.parseTime
import io.hydrolix.ketchup.translate.DefaultTranslator.Companion.toClickHouse
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.TimeUnit

object DateHistogramTranslator : AggregationTranslator<DateHistogramAggregation> {
    override fun translate(
        path: List<String>,
        mapping: DBMapping,
        agg: DateHistogramAggregation,
        children: Map<String, Aggregation>,
        parent: IRQuery,
        translator: DefaultTranslator,
        logger: Logger
    ): IRQuery {
        val sqlGroupBys = linkedSetOf<Expr<*>>()
        val sqlOrderBys = linkedSetOf<Pair<Expr<*>, Boolean>>()

        val field = agg.field() ?: throw Untranslatable(UntranslatableReason.Other, "date_histogram aggregation didn't specify a field") // TODO default or config?
        val format = agg.format()
        val script = agg.script()
        val missing = agg.missing()
        val calInterval = agg.calendarInterval()
        val fixedInterval = agg.fixedInterval()
        val zoneId = agg.timeZone()
        val extBounds = agg.extendedBounds() // TODO handle this (maybe a post-process to append/prepend empty buckets)
        val hardBounds = agg.hardBounds() // TODO handle this (maybe a post-process to drop early/late buckets?)
        val keyed = agg.keyed() // TODO handle this
        val minDocCount = agg.minDocCount() // TODO does this translate to a HAVING?
        val offset = agg.offset()
        val order = agg.order()

        var out = parent

        out = out.withAgg(CountAll, agg._toAggregation(), path, "count").first // needed for `doc_count` per bucket; TODO `*`?

        val timeChunk = if (calInterval != null) {
            Call3<Any, Any, Any, Any>(
                "toStartOfInterval", // TODO CH-ism
                GetField(field, ValueType.ANY),
                IntervalLiteral(1, calInterval.toClickHouse()),
                StringLiteral(zoneId ?: "UTC"),
                ValueType.STRING
            )
        } else if (fixedInterval != null) {
            val (count, unit) = when (fixedInterval._kind()) {
                Time.Kind.Time -> fixedInterval.time().parseTime()
                Time.Kind.Offset -> throw Untranslatable(UntranslatableReason.Other, "TODO support fixed_interval of type `offset`?")
            }

            when (unit) {
                TimeUnit.MILLISECONDS -> {
                    val bucketSizeMillis = unit.toMillis(1L) * count

                    val timestampMillis = Call1<Long, Instant>(
                        "toUnixTimestamp64Milli", // TODO CH-ism
                        GetField(field, ValueType.TIMESTAMP),
                        ValueType.LONG
                    )

                    val div = IntDiv(timestampMillis, LongLiteral(bucketSizeMillis))
                    val mul = Mul(div, LongLiteral(bucketSizeMillis))

                    Call1<Instant, Long>("fromUnixTimestamp64Milli", mul, ValueType.TIMESTAMP) // TODO CH-ism
                }
                in setOf(TimeUnit.SECONDS, TimeUnit.MINUTES, TimeUnit.HOURS, TimeUnit.DAYS) -> {
                    Call3<Any, Any, Any, Any>(
                        "toStartOfInterval", // TODO CH-ism
                        GetField(field, ValueType.ANY),
                        IntervalLiteral(count.toLong(), unit.toClickHouse()),
                        StringLiteral(zoneId ?: "UTC"),
                        ValueType.TIMESTAMP
                    )
                }
                else -> throw Untranslatable(UntranslatableReason.Other, "Unsupported time unit $unit for fixed_interval")
            }

        } else throw Untranslatable(UntranslatableReason.Other, "DateHistogram had neither calendar_interval nor fixed_interval?!")

        val withOffset = when {
            offset == null -> timeChunk
            offset.isTime -> {
                val (count, unit) = offset.time().parseTime()

                val s = unit.toSeconds(count.toLong())
                require (s >= 0 && s != Long.MAX_VALUE) { "Conversion of ${offset.time()} to seconds failed: $s"}
                Call3("timestamp_add", timeChunk, LongLiteral(s), StringLiteral("second"), ValueType.TIMESTAMP)
            }
            offset.isOffset -> {
                logger.warn("TODO What to do when `offset` is an offset? Is it relative to the calendar unit?")
                timeChunk
            }
            else -> throw Untranslatable(UntranslatableReason.Other, "Unsupported offset: $offset")
        }

        out = out.withGroupBy(withOffset, "time", BooleanLiteral.True)

        children.forEach { (name, kid) ->
            out = translator.handleAgg(mapping, path + name, kid, out)
        }

        // TODO check if parent.key is compatible with ours
        return when {
            order.isEmpty() -> {
                if (out.key.orderBys.isEmpty()) {
                    // Sort by timestamp by default
                    out.withOrderBy(withOffset, true, "time")
                } else {
                    out
                }
            }
            else -> translator.processOrderBy(order, withOffset, out, path)
        }
    }
}
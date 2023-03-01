package io.hydrolix.ketchup.model.transform

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

private val epochDate = Date.from(Instant.EPOCH)
private val epochStr = Instant.EPOCH.toString()

enum class TimeFormatType(val toZero: (Any?) -> (Any?), val toInstant: (Any?) -> Instant?) {
    EpochLikeFormat(
        {
            when (it) {
                null -> null
                is Long -> 0L
                is Int -> 0
                is String -> "0"
                is Double -> 0.0
                is Float -> 0.0f
                else -> error("Can't zero an epoch time of type ${it.javaClass}: $it")
            }
        },
        {
            when (it) {
                null -> null
                is Long -> Instant.ofEpochMilli(it)
                is Int -> Instant.ofEpochMilli(it.toLong())
                is String -> Instant.ofEpochMilli(it.toLong())
                is Double -> Instant.ofEpochMilli(it.toLong())
                is Float -> Instant.ofEpochMilli(it.toLong())
                else -> error("Can't convert an epoch time of type ${it.javaClass}: $it")
            }
        }
    ),
    Iso8601LikeFormat(
        {
            when (it) {
                null -> null
                is String -> epochStr
                is Instant -> Instant.EPOCH
                is Date -> epochDate
                else -> error("Can't zero an ISO time of type ${it.javaClass}: $it")
            }
        },
        {
            when (it) {
                null -> null
                is String -> Instant.from(DateTimeFormatter.ISO_INSTANT.parse(it)) // TODO other formats certainly needed
                is Instant -> it
                is Date -> it.toInstant()
                else -> error("Can't convert an iso8601 time of type ${it.javaClass}: $it")
            }
        }
    );

    companion object {
        val formats = mapOf(
            "epoch_millis" to TimeFormatType.EpochLikeFormat,
            "epoch_second" to TimeFormatType.EpochLikeFormat,
            "date_optional_time" to TimeFormatType.Iso8601LikeFormat,
            "strict_date_optional_time" to TimeFormatType.Iso8601LikeFormat,
            "basic_date_time" to TimeFormatType.Iso8601LikeFormat,
            // TODO lots of others will undoubtedly be needed; we'll log unrecognized ones at runtime
        )
    }
}

package io.hydrolix.ketchup.model

import org.slf4j.Logger
import java.sql.JDBCType

enum class TimeResolution {
    ns, us, ms, cs, s
}

private const val StrictPrefix = "strict_"
private val TimeGrainHierarchy = listOf(
    JDBCType.TIMESTAMP_WITH_TIMEZONE, // Most specific
    JDBCType.TIMESTAMP,               // Almost as specific
    JDBCType.DATE,                    // At least we have a YMD!
    JDBCType.TIME                     // Super vague but whaddya gonna do
)

/**
 * Tries to find the closest single [JDBCType] for the provided Elastic date format string.
 *
 * If the string contains multiple formats, like `strict_date_optional_time || year` we take the finest-grained.
 *
 * Note that this completely discards any information about the specific format string; anything that wants to deal
 * with actual data, not just metadata, will need to pay closer attention.
 */
fun parseElasticTimeFormat(format: String, logger: Logger): JDBCType {
    val fmts = format.split("||").map {
        val fmt = it.trim().lowercase()

        // Strictness is irrelevant for metadata mapping
        if (fmt.startsWith(StrictPrefix) && fmt != StrictPrefix) {
            fmt.drop(StrictPrefix.length)
        } else {
            fmt
        }
    }

    val jdbcTypes = fmts.map {
        when (it) {
            in setOf(
                "epoch_millis",
                "epoch_second",
                "date_optional_time",
                "basic_date_time",
                "basic_date_time_no_millis",
                "basic_ordinal_date_time_no_millis",
                "basic_week_date_time",
                "basic_week_date_time_no_millis",
                "date_hour",
                "date_hour_minute",
                "date_hour_minute_second",
                "date_hour_minute_second_fraction",
                "date_hour_minute_second_millis",
                "date_time",
                "date_time_no_millis",
                "week_date_time",
                "week_date_time_no_millis",
            )  -> JDBCType.TIMESTAMP

            in setOf(
                "date",
                "basic_date",
                "basic_ordinal_date",
                "basic_week_date",
                "ordinal_date",
                "week_date",
                "weekyear",      // Should probably go to 01-01
                "weekyear_week", // Should probably go to Sunday of that week
                "weekyear_week_day",
                "year",
                "year_month",
                "year_month_day",
            ) -> JDBCType.DATE

            in setOf(
                "basic_time",
                "basic_time_no_millis",
                "basic_t_time",
                "basic_t_time_no_millis",
                "hour", // should probably go to 00:00.000
                "hour_minute",
                "hour_minute_second",
                "hour_minute_second_fraction",
                "t_time",
                "t_time_no_millis",
            ) -> JDBCType.TIME

            else -> {
                // TODO it can be a DateTimeFormatter string, so this won't always be strictly correct--does it matter?
                logger.warn("Don't know how to map date format $format; defaulting to JDBCType.TIMESTAMP")
                JDBCType.TIMESTAMP
            }
        }
    }.toSet()

    return if (jdbcTypes.size > 1) {
        val mostSpecific = TimeGrainHierarchy.find { jdbcTypes.contains(it) }
        logger.warn("Found multiple JDBC types for format string $format; picking the finest-grained one $mostSpecific")
        mostSpecific!!
    } else {
        jdbcTypes.first()
    }
}

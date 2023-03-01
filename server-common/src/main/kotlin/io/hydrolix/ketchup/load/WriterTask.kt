package io.hydrolix.ketchup.load

import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.sql.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class WriterTask(
    private val ordinal: Int,
    private val inputQ: BlockingQueue<ObjectNode>,
    private val batchSize: Int,
    private val doneSentinel: ObjectNode,
    private val schema: Map<String, ClickhouseBenchLoader.TypeFamily>,
    private val connect: () -> Connection
) : Runnable {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val qs = Array(schema.size) { "?" }.joinToString(",")
    private val timeParser = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateParser = DateTimeFormatter.ISO_DATE

    override fun run() {
        logger.info("Writer #$ordinal starting...")

        var writeCount = 0
        var batchCount = 0
        var blockingTime = 0L

        val conn = connect()
        logger.info("Writer #$ordinal connected to ClickHouse")

        val start = System.currentTimeMillis()

        conn.prepareStatement("INSERT INTO hits VALUES ($qs)").use { ps ->
            while (true) {
                val obj = inputQ.poll(100, TimeUnit.MILLISECONDS)

                if (obj == null) {
                    blockingTime += 100
                    continue
                } else if (obj === doneSentinel) {
                    logger.info("Writer #$ordinal done, flushing $batchCount remaining records")
                    flush(ps, batchCount)
                    break
                }

                for ((i, field) in obj.fieldNames().withIndex()) {
                    when (schema[field] ?: error("No type for $field")) {
                        ClickhouseBenchLoader.TypeFamily.Text -> {
                            obj[field]?.let {
                                ps.setString(i + 1, it.textValue())
                            } ?: ps.setNull(i + 1, Types.VARCHAR)
                        }

                        ClickhouseBenchLoader.TypeFamily.Int -> {
                            obj[field]?.let {
                                ps.setLong(i + 1, it.longValue())
                            } ?: ps.setNull(i + 1, Types.BIGINT)
                        }

                        ClickhouseBenchLoader.TypeFamily.Time -> {
                            obj[field]?.let {
                                ps.setTimestamp(
                                    i + 1,
                                    Timestamp.valueOf(LocalDateTime.from(timeParser.parse(it.textValue())))
                                )
                            }
                        }

                        ClickhouseBenchLoader.TypeFamily.Date -> {
                            obj[field]?.let {
                                ps.setDate(i + 1, Date.valueOf(LocalDate.from(dateParser.parse(it.textValue()))))
                            }
                        }
                    }
                }

                ps.addBatch()

                writeCount += 1
                batchCount += 1

                if (batchCount == batchSize) {
                    flush(ps, batchCount)
                    batchCount = 0
                }

                if (writeCount % 100000 == 0) {
                    report(writeCount, start, logger, ordinal, blockingTime)
                }
            }

            report(writeCount, start, logger, ordinal, blockingTime)
        }
    }

    private fun flush(ps: PreparedStatement, batchCount: Int) {
        val done = ps.executeBatch().sum()
        if (done != batchCount) {
            logger.warn("Batch count was $batchCount; update only processed $done")
        }
        ps.clearBatch()
    }

    private fun report(writeCount: Int, start: Long, logger: Logger, ordinal: Int, blockingTime: Long) {
        val elapsed = System.currentTimeMillis() - start
        val rate = writeCount.toDouble() / elapsed * 1000
        logger.info("Writer #$ordinal completed $writeCount rows. ${elapsed/1000.0}s elapsed; rate $rate/s, blocked ${blockingTime/1000.0}s")
    }
}

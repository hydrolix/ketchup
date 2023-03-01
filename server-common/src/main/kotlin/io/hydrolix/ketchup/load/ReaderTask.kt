package io.hydrolix.ketchup.load

import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.util.JSON
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class ReaderTask(
    private val ordinal: Int,
    private val readQ: BlockingQueue<Path>,
    private val writerQueues: List<BlockingQueue<ObjectNode>>,
    private val limit: Int?
) : Runnable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val rng = SecureRandom()

    override fun run() {
        logger.info("Reader #$ordinal starting...")

        while (readQ.isNotEmpty()) {
            val path = readQ.poll() ?: break // Someone else might have claimed the last path before we got here

            logger.info("Reader #$ordinal processing $path")

            val fileStartTime = System.currentTimeMillis()
            var readCount = 0L
            var blockingTime = 0L

            GZIPInputStream(BufferedInputStream(FileInputStream(path.toFile()), 16384)).use { stream ->
                val parser = JSON.objectMapper.createParser(stream)

                for (obj in JSON.objectMapper.readValues(parser, ObjectNode::class.java)) {
                    // Pick a writable output queue
                    while (true) {
                        val qi = rng.nextInt(writerQueues.size)
                        val q = writerQueues[qi]

                        if (q.offer(obj, 100L, TimeUnit.MILLISECONDS)) {
                            readCount += 1
                            break
                        } else {
                            blockingTime += 100
                            continue
                        }
                    }

                    if (limit != null && readCount >= limit) {
                        logger.info("Reader #$ordinal reached the limit of $limit")
                        break
                    }

                    if (readCount % 100000L == 0L) {
                        report(readCount, fileStartTime, ordinal, path, blockingTime)
                    }
                }

                report(readCount, fileStartTime, ordinal, path, blockingTime)
            }
        }
    }

    private fun report(readCount: Long, start: Long, ordinal: Int, path: Path, blockingTime: Long) {
        val elapsed = System.currentTimeMillis() - start
        val rate = readCount.toDouble() / elapsed * 1000
        logger.info("Reader #$ordinal has dispatched $readCount rows from $path in ${elapsed/1000.0}s; rate $rate/s; blocked ${blockingTime/1000.0}s")
    }
}

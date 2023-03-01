package io.hydrolix.ketchup.load

import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.util.JSON
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.math.max

private fun String?.nullIfDash(): String? {
    return if (this.isNullOrEmpty() || this == "-") null else this
}

object ClickhouseBenchLoader {
    enum class TypeFamily {
        Int,
        Text,
        Date,
        Time
    }

    private val types = mapOf(
        "bigint" to TypeFamily.Int,
        "smallint" to TypeFamily.Int,
        "integer" to TypeFamily.Int,
        "text" to TypeFamily.Text,
        "varchar\\(\\d+\\)" to TypeFamily.Text,
        "char" to TypeFamily.Text,
        "date" to TypeFamily.Date,
        "timestamp" to TypeFamily.Time
    ).mapKeys { (k, _) -> k.toRegex() }

    @Suppress("SpellCheckingInspection")
    private val schemaByType = listOf( // TODO read this from a file
        "WatchID BIGINT NOT NULL",
        "JavaEnable SMALLINT NOT NULL",
        "Title TEXT NOT NULL",
        "GoodEvent SMALLINT NOT NULL",
        "EventTime TIMESTAMP NOT NULL",
        "EventDate Date NOT NULL",
        "CounterID INTEGER NOT NULL",
        "ClientIP INTEGER NOT NULL",
        "RegionID INTEGER NOT NULL",
        "UserID BIGINT NOT NULL",
        "CounterClass SMALLINT NOT NULL",
        "OS SMALLINT NOT NULL",
        "UserAgent SMALLINT NOT NULL",
        "URL TEXT NOT NULL",
        "Referer TEXT NOT NULL",
        "IsRefresh SMALLINT NOT NULL",
        "RefererCategoryID SMALLINT NOT NULL",
        "RefererRegionID INTEGER NOT NULL",
        "URLCategoryID SMALLINT NOT NULL",
        "URLRegionID INTEGER NOT NULL",
        "ResolutionWidth SMALLINT NOT NULL",
        "ResolutionHeight SMALLINT NOT NULL",
        "ResolutionDepth SMALLINT NOT NULL",
        "FlashMajor SMALLINT NOT NULL",
        "FlashMinor SMALLINT NOT NULL",
        "FlashMinor2 TEXT NOT NULL",
        "NetMajor SMALLINT NOT NULL",
        "NetMinor SMALLINT NOT NULL",
        "UserAgentMajor SMALLINT NOT NULL",
        "UserAgentMinor VARCHAR(255) NOT NULL",
        "CookieEnable SMALLINT NOT NULL",
        "JavascriptEnable SMALLINT NOT NULL",
        "IsMobile SMALLINT NOT NULL",
        "MobilePhone SMALLINT NOT NULL",
        "MobilePhoneModel TEXT NOT NULL",
        "Params TEXT NOT NULL",
        "IPNetworkID INTEGER NOT NULL",
        "TraficSourceID SMALLINT NOT NULL",
        "SearchEngineID SMALLINT NOT NULL",
        "SearchPhrase TEXT NOT NULL",
        "AdvEngineID SMALLINT NOT NULL",
        "IsArtifical SMALLINT NOT NULL",
        "WindowClientWidth SMALLINT NOT NULL",
        "WindowClientHeight SMALLINT NOT NULL",
        "ClientTimeZone SMALLINT NOT NULL",
        "ClientEventTime TIMESTAMP NOT NULL",
        "SilverlightVersion1 SMALLINT NOT NULL",
        "SilverlightVersion2 SMALLINT NOT NULL",
        "SilverlightVersion3 INTEGER NOT NULL",
        "SilverlightVersion4 SMALLINT NOT NULL",
        "PageCharset TEXT NOT NULL",
        "CodeVersion INTEGER NOT NULL",
        "IsLink SMALLINT NOT NULL",
        "IsDownload SMALLINT NOT NULL",
        "IsNotBounce SMALLINT NOT NULL",
        "FUniqID BIGINT NOT NULL",
        "OriginalURL TEXT NOT NULL",
        "HID INTEGER NOT NULL",
        "IsOldCounter SMALLINT NOT NULL",
        "IsEvent SMALLINT NOT NULL",
        "IsParameter SMALLINT NOT NULL",
        "DontCountHits SMALLINT NOT NULL",
        "WithHash SMALLINT NOT NULL",
        "HitColor CHAR NOT NULL",
        "LocalEventTime TIMESTAMP NOT NULL",
        "Age SMALLINT NOT NULL",
        "Sex SMALLINT NOT NULL",
        "Income SMALLINT NOT NULL",
        "Interests SMALLINT NOT NULL",
        "Robotness SMALLINT NOT NULL",
        "RemoteIP INTEGER NOT NULL",
        "WindowName INTEGER NOT NULL",
        "OpenerName INTEGER NOT NULL",
        "HistoryLength SMALLINT NOT NULL",
        "BrowserLanguage TEXT NOT NULL",
        "BrowserCountry TEXT NOT NULL",
        "SocialNetwork TEXT NOT NULL",
        "SocialAction TEXT NOT NULL",
        "HTTPError SMALLINT NOT NULL",
        "SendTiming INTEGER NOT NULL",
        "DNSTiming INTEGER NOT NULL",
        "ConnectTiming INTEGER NOT NULL",
        "ResponseStartTiming INTEGER NOT NULL",
        "ResponseEndTiming INTEGER NOT NULL",
        "FetchTiming INTEGER NOT NULL",
        "SocialSourceNetworkID SMALLINT NOT NULL",
        "SocialSourcePage TEXT NOT NULL",
        "ParamPrice BIGINT NOT NULL",
        "ParamOrderID TEXT NOT NULL",
        "ParamCurrency TEXT NOT NULL",
        "ParamCurrencyID SMALLINT NOT NULL",
        "OpenstatServiceName TEXT NOT NULL",
        "OpenstatCampaignID TEXT NOT NULL",
        "OpenstatAdID TEXT NOT NULL",
        "OpenstatSourceID TEXT NOT NULL",
        "UTMSource TEXT NOT NULL",
        "UTMMedium TEXT NOT NULL",
        "UTMCampaign TEXT NOT NULL",
        "UTMContent TEXT NOT NULL",
        "UTMTerm TEXT NOT NULL",
        "FromTag TEXT NOT NULL",
        "HasGCLID SMALLINT NOT NULL",
        "RefererHash BIGINT NOT NULL",
        "URLHash BIGINT NOT NULL",
        "CLID INTEGER NOT NULL"
    ).associate { line ->
        val (field, type, _, _) = line.split(" ")

        val identifiedType = types.entries.firstNotNullOfOrNull { (regex, typeFamily) ->
            val match = regex.matchEntire(type.lowercase())
            if (match != null) typeFamily else null
        } ?: error("Couldn't get type for $line")

        field to identifiedType
    }

    private val RecordDoneSentinel = JSON.objectMapper.nodeFactory.objectNode()
    private val logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val glob = args[1]
        val url = args[2]
        val user = args[3].nullIfDash()
        val pass = args[4].nullIfDash()

        val limit = System.getenv("READER_LIMIT")?.toInt()

        val inputPaths = Files.newDirectoryStream(dir.toPath(), glob).toList()

        val nprocs = Runtime.getRuntime().availableProcessors()

        val numReaders = max((nprocs / 3.0).toInt(), inputPaths.size) // TODO pick a better heuristic for 3.0
        val numWriters = (nprocs / 1.0).toInt() // TODO pick a better heuristic for 1.0

        val writerQueues = mutableListOf<BlockingQueue<ObjectNode>>()
        val writerThreads = mutableListOf<Thread>()

        for (ordinal in 0 until numWriters) {
            val q = ArrayBlockingQueue<ObjectNode>(1024) // TODO different capacity?

            val writerThread = Thread(WriterTask(ordinal, q, 500, RecordDoneSentinel, schemaByType) {
                DriverManager.getConnection(url, user, pass)
            })

            writerThread.name = "writer-$ordinal"
            writerThread.start()

            writerQueues += q
            writerThreads += writerThread
        }

        val inputPathsQ = ArrayBlockingQueue(inputPaths.size, true, inputPaths)

        val readerThreads = List(numReaders) { ordinal ->
            val readerThread = Thread(ReaderTask(ordinal, inputPathsQ, writerQueues, limit))
            readerThread.name = "reader-$ordinal"
            readerThread.start()
            readerThread
        }

        waitAll("Reader", readerThreads)

        // Tell the writer threads we're done
        for (q in writerQueues) {
            q.add(RecordDoneSentinel)
        }

        waitAll("Writer", writerThreads)
    }

    private fun waitAll(what: String, threads: List<Thread>) {
        val waitingIndices = threads.indices.toMutableSet()

        while (waitingIndices.isNotEmpty()) {
            waitingIndices.randomOrNull()?.also { ti ->
                val thread = threads[ti]
                thread.join(5000)
                if (!thread.isAlive) {
                    logger.info("$what #$ti is done!")
                    waitingIndices -= ti
                }
            }
        }
    }
}

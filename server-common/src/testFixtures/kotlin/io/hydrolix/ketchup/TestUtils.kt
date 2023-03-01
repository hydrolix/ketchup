package io.hydrolix.ketchup

import com.fasterxml.jackson.core.JsonParser
import io.hydrolix.ketchup.model.config.DBMapping
import io.hydrolix.ketchup.server.ProxyConfig
import io.hydrolix.ketchup.server.ServerJSON
import io.hydrolix.ketchup.translate.DefaultTranslator
import io.hydrolix.ketchup.util.JSON
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.GZIPInputStream
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.streams.toList

open class TestUtils {
    protected val logger = LoggerFactory.getLogger(javaClass)

    inline fun <reified T> load(stream: InputStream): T {
        return ServerJSON.objectMapper.readValue(stream, T::class.java)
    }

    inline fun <reified T> load(path: String): T {
        println("Loading $path")
        return load(javaClass.getResourceAsStream(path)!!)
    }

    private val dbMappings = findStuff("dbMapping").flatMap { stream ->
        stream.open().use { JSON.objectMapper.readValue(it, DBMapping.ListOfThese)}
    }

    protected val translator = DefaultTranslator(
        ProxyConfig(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            dbMappings + listOf(
                DBMapping("opensearch_dashboards_sample_data_flights", emptyList(), UUID.randomUUID(), "flights", UUID.randomUUID(), null, "id"),
                DBMapping("opensearch_dashboards_sample_data_ecommerce", emptyList(), UUID.randomUUID(), "ecommerce", UUID.randomUUID(), null, "id"),
                DBMapping("opensearch_dashboards_sample_data_logs", emptyList(), UUID.randomUUID(), "logs", UUID.randomUUID(), null, "id"),
                DBMapping("hits", emptyList(), UUID.randomUUID(), "hits", UUID.randomUUID(), null, "id"),
            ),
            emptyList(),
            logger
        )
    )

    protected val hex2 = """\\x([0-9a-fA-F]{2})""".toRegex()
    protected val mapper = JSON.objectMapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)

    data class TestCase(
        val publicOrPrivate: String,
        val tag: String,
        val type: String,
        val path: Path
    ) {
        val fail = path.name.lowercase().contains("fail")

        fun open(): InputStream = if (path.extension == "gz") {
            GZIPInputStream(path.inputStream())
        } else {
            path.inputStream()
        }
    }

    fun findStuff(type: String? = null): List<TestCase> {
        val testDataRoot = Path.of("../testdata")
        val paths = Files.find(testDataRoot, 9, { path, attrs ->
            true
        }, FileVisitOption.FOLLOW_LINKS).toList()

        return paths.mapNotNull { path ->
            val rel = testDataRoot.relativize(path)
            if (rel.nameCount > 3) {
                val publicOrPrivate = rel.getName(0).name
                val tag = rel.getName(1).name
                val pathType = rel.getName(2).name

                if (type == null || pathType == type) {
                    TestCase(publicOrPrivate, tag, pathType, path.toRealPath())
                } else null

            } else null
        }
    }

    protected inline fun <reified T> processCorpus(
        stream: InputStream,
        f: (Int, String, T?) -> Unit
    ) {
        for ((i, line) in stream.bufferedReader().lineSequence().withIndex()) {
            val clean = line
                .replace("True", "true") // Python spellings of booleans
                .replace("False", "false")
                .replace(hex2, "\\u00$1") // JSON doesn't support \x

            val req = try {
                mapper.readValue(clean, T::class.java)
            } catch (e: Exception) {
                throw RuntimeException("Couldn't deserialize line #${i+1}: $line", e)
            }

            f(i, line, req)
        }
    }
}

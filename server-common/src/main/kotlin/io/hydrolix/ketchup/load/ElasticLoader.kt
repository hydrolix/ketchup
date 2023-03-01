package io.hydrolix.ketchup.load

import io.hydrolix.ketchup.util.JSON
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.io.path.inputStream
import kotlin.streams.toList
import kotlin.system.exitProcess

object ElasticLoader {
    private val logger = LoggerFactory.getLogger(javaClass)

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = Path.of(args[0])
        val glob = args[1]
        val url = URI(args[2])
        val idField = args[3]
        val user = args[4]
        val pass = args[5]
        val basicCreds = Base64.getEncoder().encodeToString("$user:$pass".encodeToByteArray())

        val httpClient = HttpClient.newBuilder()
            .sslContext(insecureSslContext())
            .build()

        val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:$dir/$glob")
        val files = Files.find(dir, 1, { path, _ -> pathMatcher.matches(path) }).toList()

        var count = 0
        val batch = mutableListOf<String>()

        for (file in files) {
            GZIPInputStream(file.inputStream()).reader().useLines() {
                for (line in it) {
                    val obj = JSON.objectMapper.readTree(line)
                    val id = obj[idField].textValue()
                    batch += """{"index":{}, "_id":"$id"}"""
                    batch += line
                    count += 1

                    if (count % 1000 == 0) {
                        logger.info("Processed $count records")

                        flush(
                            url,
                            batch,
                            httpClient,
                            basicCreds
                        )
                    }
                }
            }
            flush(url, batch, httpClient, basicCreds)
        }
    }

    private fun flush(
        url: URI,
        batch: MutableList<String>,
        httpClient: HttpClient,
        basicCreds: String?
    ) {
        val req = HttpRequest.newBuilder(url)
            .POST(BodyPublishers.ofString(batch.joinToString("\n", postfix="\n")))
            .header("Content-Type", "application/json")
            .header("Authorization", "Basic $basicCreds")
            .build()

        val resp = httpClient.send(req, BodyHandlers.ofString())

        if (resp.statusCode() != 200) {
            logger.error(
                "Bulk POST response was not 200: ${resp.statusCode()}:\n  ${
                    resp.body().replace("\n", "\n  ")
                }"
            )
            exitProcess(1)
        } else {
            batch.clear()
        }
    }

    private fun insecureSslContext(): SSLContext {
        return SSLContext.getInstance("ssl").also {
            it.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
            }), null)
        }
    }
}
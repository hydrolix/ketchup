package io.hydrolix.ketchup

import io.hydrolix.ketchup.model.opensearch.BatchRequest
import io.hydrolix.ketchup.model.opensearch.BatchSingleResponse
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.*

class BatchSearchParsingTest : TestUtils() {
    @Test fun `random queries batch1`() = doIt("/scenarios/random/queries/batch1")
    @Test fun `random queries batch2`() = doIt("/scenarios/random/queries/batch2")
    @Test fun `random queries batch3`() = doIt("/scenarios/random/queries/batch3")
    @Test fun `random queries batch4`() = doIt("/scenarios/random/queries/batch4")
    @Test fun `random queries batch5`() = doIt("/scenarios/random/queries/batch5")
    @Test fun `random queries batch6`() = doIt("/scenarios/random/queries/batch6")

    private fun doIt(path: String) {
        val reqUrl = javaClass.getResource("$path/request.json")
        val respUrl = javaClass.getResource("$path/response.txt")

        val breq = reqUrl!!.openStream().use { load<BatchRequest>(it) }

        println(breq)

        val sreqs = breq.batch.map { UUID.randomUUID() to (it.request.params.body to it.request.params.index) }

        val tres = translator.translate(linkedMapOf(*sreqs.toTypedArray()))
        println(tres)

        val respStream = respUrl?.openStream()
        if (respStream == null) {
            println("<no response>")
        } else {
            val resps = respUrl.openStream().use { BatchSingleResponse.decodeMulti(it) }
            val baos = ByteArrayOutputStream(16384)
            BatchSingleResponse.encodeMulti(resps, baos)

            for ((i, resp) in resps.withIndex()) {
                println("decoded $i: $resp")
            }

            val respsJ = JSON.objectMapper.writeValueAsString(resps)

            println("All responses JSON: $respsJ")
        }
    }
}

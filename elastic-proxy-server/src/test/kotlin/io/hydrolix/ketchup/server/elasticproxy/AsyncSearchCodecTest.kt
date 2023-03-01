package io.hydrolix.ketchup.server.elasticproxy

import co.elastic.clients.elasticsearch.async_search.SubmitRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import io.hydrolix.ketchup.server.ServerJSON
import org.junit.jupiter.api.Test

class AsyncSearchCodecTest {

    @Test
    fun `try round-tripping SubmitRequest to SearchRequest`() {
        val submitReq = javaClass.getResourceAsStream("/asyncSubmitRequest.json").use {
            ServerJSON.objectMapper.readValue(it, SubmitRequest::class.java)
        }

        val submitReqBytes = ServerJSON.objectMapper.writeValueAsBytes(submitReq)

        val searchReq = ServerJSON.objectMapper.readValue(submitReqBytes, SearchRequest::class.java)

        println(searchReq)
    }
}
package io.hydrolix.ketchup

import co.elastic.clients.elasticsearch.core.SearchRequest
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.model.opensearch.*
import io.hydrolix.ketchup.server.ServerJSON
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * This parses ~all the saved requests and responses in src/test/resources/scenarios... at the moment
 * all it does is deserialize them and pass the test by not throwing exceptions. Some other test
 * will be responsible for actually interpreting and/or running the queries someday.
 */
class DeserTest : TestUtils() {
    @Test fun `random data1 request`() = testDataRequest("/scenarios/random/queries/data1/request.json")
    @Test fun `random data1 response`() = testDataResponse("/scenarios/random/queries/data1/response.json")

    @Test fun `logs data1 request`() = testDataRequest("/scenarios/logs/queries/data1/request.json")
    @Test fun `logs data2 request`() = testDataRequest("/scenarios/logs/queries/data2/request.json")
    @Test fun `ecommerce data1 request`() = testDataRequest("/scenarios/ecommerce/queries/data1/request.json")
    @Test fun `ecommerce data2 request`() = testDataRequest("/scenarios/ecommerce/queries/data2/request.json")
    @Test fun `flights data1 request`() = testDataRequest("/scenarios/flights/queries/data1/request.json")

    @Test fun `logs data1 response`() = testDataResponse("/scenarios/logs/queries/data1/response.json")
    @Test fun `logs data2 response`() = testDataResponse("/scenarios/logs/queries/data2/response.json")
    @Test fun `ecommerce data1 response`() = testDataResponse("/scenarios/ecommerce/queries/data1/response.json")
    @Test fun `ecommerce data2 response`() = testDataResponse("/scenarios/ecommerce/queries/data2/response.json")
    @Test fun `flights data1 response`() = testDataResponse("/scenarios/flights/queries/data1/response.json")

    @Test fun `logs search1 request`() = testSearchRequest("/scenarios/logs/queries/search1/request.json")
    @Test fun `logs search2 request`() = testSearchRequest("/scenarios/logs/queries/search2/request.json")
    @Test fun `logs search3 request`() = testSearchRequest("/scenarios/logs/queries/search3/request.json")
    @Test fun `logs search4 request`() = testSearchRequest("/scenarios/logs/queries/search4/request.json")
    @Test fun `logs search5 request`() = testSearchRequest("/scenarios/logs/queries/search5/request.json")
    @Test fun `logs search6 request`() = testSearchRequest("/scenarios/logs/queries/search6/request.json")
    @Test fun `logs search7 request`() = testSearchRequest("/scenarios/logs/queries/search7/request.json")
    @Test fun `logs search8 request`() = testSearchRequest("/scenarios/logs/queries/search8/request.json")
    @Test fun `logs search9 request`() = testSearchRequest("/scenarios/logs/queries/search9/request.json")
    @Test fun `logs search10 request`() = testSearchRequest("/scenarios/logs/queries/search10/request.json")
    @Test fun `ecommerce search1 request`() = testSearchRequest("/scenarios/ecommerce/queries/search1/request.json")
    @Test fun `ecommerce search2 request`() = testSearchRequest("/scenarios/ecommerce/queries/search2/request.json")
    @Test fun `ecommerce search3 request`() = testSearchRequest("/scenarios/ecommerce/queries/search3/request.json")
    @Test fun `ecommerce search4 request`() = testSearchRequest("/scenarios/ecommerce/queries/search4/request.json")
    @Test fun `ecommerce search5 request`() = testSearchRequest("/scenarios/ecommerce/queries/search5/request.json")
    @Test fun `ecommerce search6 request`() = testSearchRequest("/scenarios/ecommerce/queries/search6/request.json")
    @Test fun `ecommerce search7 request`() = testSearchRequest("/scenarios/ecommerce/queries/search7/request.json")
    @Test fun `ecommerce search8 request`() = testSearchRequest("/scenarios/ecommerce/queries/search8/request.json")
    @Test fun `ecommerce search9 request`() = testSearchRequest("/scenarios/ecommerce/queries/search9/request.json")
    @Test fun `ecommerce search10 request`() = testSearchRequest("/scenarios/ecommerce/queries/search10/request.json")
    @Test fun `ecommerce search11 request`() = testSearchRequest("/scenarios/ecommerce/queries/search11/request.json")
    @Test fun `flights search1 request`() = testSearchRequest("/scenarios/flights/queries/search1/request.json")
    @Test fun `flights search2 request`() = testSearchRequest("/scenarios/flights/queries/search2/request.json")
    @Test fun `flights search3 request`() = testSearchRequest("/scenarios/flights/queries/search3/request.json")
    @Test fun `flights search4 request`() = testSearchRequest("/scenarios/flights/queries/search4/request.json")
    @Test fun `flights search5 request`() = testSearchRequest("/scenarios/flights/queries/search5/request.json")
    @Test fun `flights search6 request`() = testSearchRequest("/scenarios/flights/queries/search6/request.json")
    @Test fun `flights search7 request`() = testSearchRequest("/scenarios/flights/queries/search7/request.json")
    @Test fun `flights search8 request`() = testSearchRequest("/scenarios/flights/queries/search8/request.json")
    @Test fun `flights search9 request`() = testSearchRequest("/scenarios/flights/queries/search9/request.json")
    @Test fun `flights search10 request`() = testSearchRequest("/scenarios/flights/queries/search10/request.json")
    @Test fun `flights search11 request`() = testSearchRequest("/scenarios/flights/queries/search11/request.json")
    @Test fun `flights search12 request`() = testSearchRequest("/scenarios/flights/queries/search12/request.json")
    @Test fun `flights search13 request`() = testSearchRequest("/scenarios/flights/queries/search13/request.json")
    @Test fun `flights search14 request`() = testSearchRequest("/scenarios/flights/queries/search14/request.json")
    @Test fun `flights search15 request`() = testSearchRequest("/scenarios/flights/queries/search15/request.json")
    @Test fun `flights search16 request`() = testSearchRequest("/scenarios/flights/queries/search16/request.json")
    @Test fun `flights search17 request`() = testSearchRequest("/scenarios/flights/queries/search17/request.json")

    @Test fun `flights search1 response`() = testSearchResponse("/scenarios/flights/queries/search1/response.json")
    @Test fun `flights search2 response`() = testSearchResponse("/scenarios/flights/queries/search2/response.json")
    @Test fun `flights search3 response`() = testSearchResponse("/scenarios/flights/queries/search3/response.json")
    @Test fun `flights search4 response`() = testSearchResponse("/scenarios/flights/queries/search4/response.json")
    @Test fun `flights search5 response`() = testSearchResponse("/scenarios/flights/queries/search5/response.json")
    @Test fun `flights search6 response`() = testSearchResponse("/scenarios/flights/queries/search6/response.json")
    @Test fun `flights search7 response`() = testSearchResponse("/scenarios/flights/queries/search7/response.json")
    @Test fun `flights search8 response`() = testSearchResponse("/scenarios/flights/queries/search8/response.json")
    @Test fun `flights search9 response`() = testSearchResponse("/scenarios/flights/queries/search9/response.json")
    @Test fun `flights search10 response`() = testSearchResponse("/scenarios/flights/queries/search10/response.json")
    @Test fun `flights search11 response`() = testSearchResponse("/scenarios/flights/queries/search11/response.json")
    @Test fun `flights search12 response`() = testSearchResponse("/scenarios/flights/queries/search12/response.json")
    @Test fun `flights search13 response`() = testSearchResponse("/scenarios/flights/queries/search13/response.json")
    @Test fun `flights search14 response`() = testSearchResponse("/scenarios/flights/queries/search14/response.json")
    @Test fun `flights search15 response`() = testSearchResponse("/scenarios/flights/queries/search15/response.json")
    @Test fun `flights search16 response`() = testSearchResponse("/scenarios/flights/queries/search16/response.json")
    @Test fun `flights search17 response`() = testSearchResponse("/scenarios/flights/queries/search17/response.json")
    @Test fun `ecommerce search1 response`() = testSearchResponse("/scenarios/ecommerce/queries/search1/response.json")
    @Test fun `ecommerce search2 response`() = testSearchResponse("/scenarios/ecommerce/queries/search2/response.json")
    @Test fun `ecommerce search3 response`() = testSearchResponse("/scenarios/ecommerce/queries/search3/response.json")
    @Test fun `ecommerce search4 response`() = testSearchResponse("/scenarios/ecommerce/queries/search4/response.json")
    @Test fun `ecommerce search5 response`() = testSearchResponse("/scenarios/ecommerce/queries/search5/response.json")
    @Test fun `ecommerce search6 response`() = testSearchResponse("/scenarios/ecommerce/queries/search6/response.json")
    @Test fun `ecommerce search7 response`() = testSearchResponse("/scenarios/ecommerce/queries/search7/response.json")
    // TODO fix this, the response.json file is missing
    // @Test fun `ecommerce search8 response`() = testSearchResponse("/scenarios/ecommerce/queries/search8/response.json")
    @Test fun `ecommerce search9 response`() = testSearchResponse("/scenarios/ecommerce/queries/search9/response.json")
    @Test fun `ecommerce search10 response`() = testSearchResponse("/scenarios/ecommerce/queries/search10/response.json")
    @Test fun `ecommerce search11 response`() = testSearchResponse("/scenarios/ecommerce/queries/search11/response.json")
    @Test fun `logs search1 response`() = testSearchResponse("/scenarios/logs/queries/search1/response.json")
    @Test fun `logs search2 response`() = testSearchResponse("/scenarios/logs/queries/search2/response.json")
    @Test fun `logs search3 response`() = testSearchResponse("/scenarios/logs/queries/search3/response.json")
    @Test fun `logs search4 response`() = testSearchResponse("/scenarios/logs/queries/search4/response.json")
    @Test fun `logs search5 response`() = testSearchResponse("/scenarios/logs/queries/search5/response.json")
    @Test fun `logs search6 response`() = testSearchResponse("/scenarios/logs/queries/search6/response.json")
    @Test fun `logs search7 response`() = testSearchResponse("/scenarios/logs/queries/search7/response.json")
    @Test fun `logs search8 response`() = testSearchResponse("/scenarios/logs/queries/search8/response.json")
    @Test fun `logs search9 response`() = testSearchResponse("/scenarios/logs/queries/search9/response.json")
    @Test fun `logs search10 response`() = testSearchResponse("/scenarios/logs/queries/search10/response.json")

    private fun testDataRequest(path: String) {
        val dataReq = load<DataRequest>(path)
        println(dataReq)
    }

    private fun testDataResponse(path: String) {
        val dresp = load<DataResponse>(path)
        println(dresp)
    }

    private fun testSearchRequest(path: String) {
        val kreqJ = load<ObjectNode>(path)

        val kreq2 = ServerJSON.objectMapper.convertValue(kreqJ, KibanaSearchRequest::class.java)

        println(kreq2)

        val sreqJ = kreqJ["params"]["body"]

        val sreq2 = ServerJSON.objectMapper.convertValue(sreqJ, SearchRequest::class.java)

        println(sreq2)
    }

    private fun testSearchResponse(path: String) {
        val sresp = load<KibanaSearchResponse>(path)
        println(sresp)
    }

    @Test fun `RT logs search1 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search1/request.json")
    @Test fun `RT logs search2 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search2/request.json")
    @Test fun `RT logs search3 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search3/request.json")
    @Test fun `RT logs search4 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search4/request.json")
    @Test fun `RT logs search5 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search5/request.json")
    @Test fun `RT logs search6 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search6/request.json")
    @Test fun `RT logs search7 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search7/request.json")
    @Test fun `RT logs search8 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search8/request.json")
    @Test fun `RT logs search9 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search9/request.json")
    @Test fun `RT logs search10 request`() = roundTripTestSearchRequest("/scenarios/logs/queries/search10/request.json")
    @Test fun `RT ecommerce search1 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search1/request.json")
    @Test fun `RT ecommerce search2 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search2/request.json")
    @Test fun `RT ecommerce search3 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search3/request.json")
    @Test fun `RT ecommerce search4 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search4/request.json")
    @Test fun `RT ecommerce search5 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search5/request.json")
    @Test fun `RT ecommerce search6 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search6/request.json")
    @Test fun `RT ecommerce search7 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search7/request.json")
    @Test fun `RT ecommerce search8 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search8/request.json")
    @Test fun `RT ecommerce search9 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search9/request.json")
    @Test fun `RT ecommerce search10 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search10/request.json")
    @Test fun `RT ecommerce search11 request`() = roundTripTestSearchRequest("/scenarios/ecommerce/queries/search11/request.json")
    @Test fun `RT flights search1 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search1/request.json")
    @Test fun `RT flights search2 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search2/request.json")
    @Test fun `RT flights search3 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search3/request.json")
    @Test fun `RT flights search4 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search4/request.json")
    @Test fun `RT flights search5 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search5/request.json")
    @Test fun `RT flights search6 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search6/request.json")
    @Test fun `RT flights search7 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search7/request.json")
    @Test fun `RT flights search8 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search8/request.json")
    @Test fun `RT flights search9 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search9/request.json")
    @Test fun `RT flights search10 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search10/request.json")
    @Test fun `RT flights search11 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search11/request.json")
    @Test fun `RT flights search12 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search12/request.json")
    @Test fun `RT flights search13 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search13/request.json")
    @Test fun `RT flights search14 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search14/request.json")
    @Test fun `RT flights search15 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search15/request.json")
    @Test fun `RT flights search16 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search16/request.json")
    @Test fun `RT flights search17 request`() = roundTripTestSearchRequest("/scenarios/flights/queries/search17/request.json")

    @Test fun `discover request & response`() {
        val req = load<KibanaSearchRequest>("/scenarios/logs/queries/search11/request.json")
        println(req)
        val resp = load<KibanaSearchResponse>("/scenarios/logs/queries/search11/response.json")
        println(resp)
    }

    @Test fun `batch request & response`() {
        val req = load<BatchRequest>("/scenarios/random/queries/batch1/request.json")
        println(req)
    }

    // Note, this has to be a JSON comparison because https://github.com/elastic/elasticsearch-java/issues/101
    private fun roundTripTestSearchRequest(path: String) {
        val req = load<KibanaSearchRequest>(path)
        val json = JSON.objectMapper.writeValueAsString(req)

        val req2 = JSON.objectMapper.readValue(json, KibanaSearchRequest::class.java)
        val json2 = JSON.objectMapper.writeValueAsString(req2)

        assertEquals(json, json2)
    }
}

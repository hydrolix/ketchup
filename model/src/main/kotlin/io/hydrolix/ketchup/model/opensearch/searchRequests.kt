package io.hydrolix.ketchup.model.opensearch

import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.json.jackson.JacksonJsonpGenerator
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpParser
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.model.transform.QueryTransformer
import io.hydrolix.ketchup.model.transform.TimeRangeZero
import io.hydrolix.ketchup.util.JSON
import java.io.ByteArrayOutputStream

data class KibanaSearchRequest(
    val params: SearchRequestContent,
    val id: String?,
) {
    fun timeless(): KibanaSearchRequest {
        val req = this.params.body

        val baos = ByteArrayOutputStream(16384)
        req.serialize(JacksonJsonpGenerator(JSON.objectMapper.createGenerator(baos)), JacksonJsonpMapper(JSON.objectMapper))

        val timelessQuery = QueryTransformer.transform(req.query()!!, listOf(TimeRangeZero))

        val xreq = SearchRequest.Builder().withJson(baos.toByteArray().inputStream())

        return this.copy(params = this.params.copy(body = xreq.query(timelessQuery).build()))
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SearchRequestContent(
    val index: String,
    @JsonSerialize(using = SearchRequestSerializer::class)
    @JsonDeserialize(using = SearchRequestDeserializer::class)
    val body: SearchRequest,
    val preference: Long,
    val trackTotalHits: Boolean?,
    @JsonProperty("ignoreThrottled") @JsonAlias("ignore_throttled")
    val ignoreThrottled: Boolean?,
    val restTotalHitsAsInt: Boolean?,
    val ignoreUnavailable: Boolean?,
    val timeout: String?,
)

class SearchRequestSerializer : JsonSerializer<SearchRequest>() {
    override fun serialize(value: SearchRequest, gen: JsonGenerator, serializers: SerializerProvider) {
        value.serialize(JacksonJsonpGenerator(gen), JacksonJsonpMapper(JSON.objectMapper))
    }
}

// Workaround for https://github.com/elastic/elasticsearch-java/issues/400
class SearchRequestDeserializer : JsonDeserializer<SearchRequest>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): SearchRequest {
        val obj = p.readValueAsTree<ObjectNode>()

        if (obj["_source"]?.isArray == true) {
            val fields = (obj["_source"] as ArrayNode).mapNotNull {
                if (it.isTextual) it else null
            }

            obj.putObject("_source")
                .putArray("includes")
                .addAll(fields)
        }

        return SearchRequest._DESERIALIZER.deserialize(JacksonJsonpParser(obj.traverse()), JacksonJsonpMapper(JSON.objectMapper))
    }
}
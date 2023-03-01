package io.hydrolix.ketchup.model.opensearch

import co.elastic.clients.elasticsearch.async_search.SubmitRequest
import co.elastic.clients.elasticsearch.async_search.SubmitResponse
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.json.JsonpMapperFeatures
import co.elastic.clients.json.jackson.JacksonJsonpGenerator
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpParser
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hydrolix.ketchup.util.JSON
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.streams.toList

/**
 * Responses from /internal/bsearch are actually (sometimes?) lines of Base64(gzip(json)); once decoded and uncompressed
 * each line should decode to this.
 */
data class BatchSingleResponse(
    val id: Int,
    val result: KibanaSearchResponse
) {
    companion object {
        /**
         * Closes the stream.
         */
        fun decodeMulti(stream: InputStream): List<BatchSingleResponse> {
            val lines = stream.use {
                it.bufferedReader().lines().toList()
            }

            return lines.map { line ->
                val bytes = Base64.getDecoder().decode(line)

                // TODO create an Inflater once and reuse it for each line if this becomes a perf bottleneck
                InflaterInputStream(ByteArrayInputStream(bytes)).use {
                    JSON.objectMapper.readValue(it, BatchSingleResponse::class.java)
                }
            }
        }

        fun encodeMulti(resps: List<BatchSingleResponse>, out: OutputStream) {
            for (resp in resps) {
                val baos = ByteArrayOutputStream(16384)
                DeflaterOutputStream(baos).use {
                    JSON.objectMapper.writeValue(it, resp)
                }
                out.write(baos.toByteArray())
                out.write('\n'.code)
            }
        }
    }
}

data class KibanaSearchResponse(
    val id: String?, // TODO should this be here or maybe a different type?
    val isPartial: Boolean,
    val isRunning: Boolean,
    val rawResponse: SearchRawResponse, // TODO [SearchResponse] is too persnickety
    val total: Int,
    val loaded: Int,
    val isRestored: Boolean?, // TODO should this be here or maybe a different type?
) {
    companion object {
        val ListOfThese = object : TypeReference<List<KibanaSearchResponse>>() { }
    }
}

class SubmitRequestSerializer : JsonSerializer<SubmitRequest>() {
    override fun serialize(value: SubmitRequest, gen: JsonGenerator, serializers: SerializerProvider) {
        value.serialize(JacksonJsonpGenerator(gen), JacksonJsonpMapper(JSON.objectMapper))
    }
}

// TODO this might need the `_source: []` fix as well
class SubmitRequestDeserializer : JsonDeserializer<SubmitRequest>() {
    override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): SubmitRequest {
        return SubmitRequest._DESERIALIZER.deserialize(JacksonJsonpParser(p), JacksonJsonpMapper(JSON.objectMapper))
    }
}

class SubmitResponseSerializer : JsonSerializer<SubmitResponse<*>>() {
    override fun serialize(value: SubmitResponse<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        value.serialize(
            JacksonJsonpGenerator(gen),
            JacksonJsonpMapper(JSON.objectMapper)
        )
    }
}

class SubmitResponseDeserializer : JsonDeserializer<SubmitResponse<*>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): SubmitResponse<*> {
        return SubmitResponse._DESERIALIZER.deserialize(
            JacksonJsonpParser(p),
            JacksonJsonpMapper(JSON.objectMapper)
        )
    }
}

class SearchResponseSerializer : JsonSerializer<SearchResponse<*>>() {
    override fun serialize(value: SearchResponse<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        value.serialize(
            JacksonJsonpGenerator(gen),
            // TODO make SERIALIZE_TYPED_KEYS=false conditional based on typedKeys query parameter -- java client always
            //  sets it to true but others might have a meaningful preference -- Kibana in particular needs this
            JacksonJsonpMapper(JSON.objectMapper).withAttribute(JsonpMapperFeatures.SERIALIZE_TYPED_KEYS, false)
        )
    }
}

class SearchResponseDeserializer : JsonDeserializer<SearchResponse<*>>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): SearchResponse<*> {
        return SearchResponse._DESERIALIZER.deserialize(JacksonJsonpParser(p), JacksonJsonpMapper(JSON.objectMapper))
    }
}

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SearchRawResponse(
    val took: Int,
    val timedOut: Boolean,
    @JsonProperty("_shards")
    val shards: SearchResponseShardInfo,
    val hits: SearchResponseHits,
    /** the map keys are just names, and the values don't have type tags; we can't parse them until we look at the request */
    val aggregations: Map<String, ObjectNode>?,
    val terminatedEarly: Boolean?,
)

data class SearchResponseHits(
    val total: Int,
    @JsonProperty("max_score")
    val maxScore: Double?,
    val hits: List<SearchHit>
)

data class SearchHit(
    @JsonProperty("_index")
    val index: String,
    @JsonProperty("_id")
    val id: String,
    @JsonProperty("_version")
    val version: Int,
    @JsonProperty("_score")
    val score: Double?,
    @JsonProperty("_source")
    val source: ObjectNode?, // TODO is this always an object?
    val fields: ObjectNode?, // TODO is this always an object?
    val sort: List<Long>?,
    @JsonProperty("ignored_field_values")
    val ignoredFieldValues: ObjectNode?, // TODO is this always an object?
)

data class SearchResponseShardInfo(
    val total: Int,
    val successful: Int,
    val skipped: Int,
    val failed: Int,
)

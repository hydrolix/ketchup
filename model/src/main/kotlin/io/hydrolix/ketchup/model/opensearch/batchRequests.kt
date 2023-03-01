package io.hydrolix.ketchup.model.opensearch

import io.hydrolix.ketchup.model.testcases.Case
import io.hydrolix.ketchup.model.testcases.CaseMeta
import io.hydrolix.ketchup.model.testcases.CaseType
import io.hydrolix.ketchup.util.JSON
import java.io.InputStream
import java.nio.file.Path
import java.util.*

/**
 * This is POSTed to /internal/bsearch
 */
data class BatchRequest(
    val batch: List<BatchSingleRequest>
) {
    fun timeless(): BatchRequest {
        return this.copy(batch = batch.map { it.timeless() })
    }
}

data class BatchSingleRequest(
    val request: KibanaSearchRequest,
    val options: BatchRequestOptions
) {
    fun timeless(): BatchSingleRequest {
        return this.copy(request = request.timeless())
    }
}

data class BatchRequestOptions(
    val sessionId: UUID?,
    val isRestore: Boolean,
    val strategy: String, // TODO enum? `ese`
    val isStored: Boolean,
    val executionContext: BatchRequestExecutionContext,
)

data class BatchRequestExecutionContext(
    val name: String, // TODO enum? `lens`
    val url: String,
    val type: String, // TODO enum? `application`
    val id: String, // `new` or..?
    val page: String, // TODO enum? `editor`
    val description: String?,
    val child: BatchRequestExecutionContextChild?,
)

data class BatchRequestExecutionContextChild(
    val type: String, // TODO enum? `lens`, `visualization`
    val name: String,
    val id: String?, // TODO sometimes UUID, sometimes, empty, what else?
    val description: String,
    val url: String?,
)

data class BatchCase(
    override val indexPattern: String,
    override val ordinal: Int,
    override val meta: CaseMeta?,
    override val caseDir: Path,
    override val request: BatchRequest,
    override val response: List<KibanaSearchResponse>?
) : Case<BatchRequest, List<KibanaSearchResponse>>

object BatchCaseType : CaseType<BatchRequest, List<KibanaSearchResponse>, BatchCase> {
    override fun parseReq(stream: InputStream): BatchRequest {
        return stream.use { JSON.objectMapper.readValue(it, BatchRequest::class.java )}
    }

    override fun parseResp(stream: InputStream): List<KibanaSearchResponse> {
        return stream.use { JSON.objectMapper.readValue(it, KibanaSearchResponse.ListOfThese) }
    }

    override fun mk(
        indexPattern: String,
        ordinal: Int,
        meta: CaseMeta?,
        caseDir: Path,
        request: BatchRequest,
        response: List<KibanaSearchResponse>?
    ): BatchCase {
        return BatchCase(indexPattern, ordinal, meta, caseDir, request, response)
    }

    override fun timeless(req: BatchRequest): BatchRequest {
        return req.timeless()
    }
}

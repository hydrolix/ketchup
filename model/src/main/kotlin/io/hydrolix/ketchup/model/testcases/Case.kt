package io.hydrolix.ketchup.model.testcases

import io.hydrolix.ketchup.model.opensearch.DataRequest
import io.hydrolix.ketchup.model.opensearch.DataResponse
import io.hydrolix.ketchup.model.opensearch.KibanaSearchRequest
import io.hydrolix.ketchup.model.opensearch.KibanaSearchResponse
import io.hydrolix.ketchup.util.JSON
import java.io.InputStream
import java.nio.file.Path

data class CaseMeta(
    val queryParams: Map<String, List<String>>,
    val extraHeaders: Map<String, List<String>>,
)

interface CaseType<Req, Resp, C : Case<Req, Resp>> {
    fun parseReq(stream: InputStream): Req
    fun parseResp(stream: InputStream): Resp
    fun mk(
        indexPattern: String,
        ordinal: Int,
        meta: CaseMeta?,
        caseDir: Path,
        request: Req,
        response: Resp?
    ): C
    fun timeless(req: Req): Req
}

object SearchCaseType : CaseType<KibanaSearchRequest, KibanaSearchResponse, SearchCase> {
    override fun parseReq(stream: InputStream): KibanaSearchRequest {
        return stream.use { JSON.objectMapper.readValue(it, KibanaSearchRequest::class.java) }
    }

    override fun parseResp(stream: InputStream): KibanaSearchResponse {
        return stream.use { JSON.objectMapper.readValue(it, KibanaSearchResponse::class.java) }
    }

    override fun timeless(req: KibanaSearchRequest): KibanaSearchRequest {
        return req.timeless()
    }

    override fun mk(
        indexPattern: String,
        ordinal: Int,
        meta: CaseMeta?,
        caseDir: Path,
        request: KibanaSearchRequest,
        response: KibanaSearchResponse?
    ): SearchCase {
        return SearchCase(indexPattern, ordinal, meta, caseDir, request, response)
    }
}


object DataCaseType : CaseType<DataRequest, DataResponse, DataCase> {
    override fun parseReq(stream: InputStream): DataRequest {
        return stream.use { JSON.objectMapper.readValue(it, DataRequest::class.java) }
    }

    override fun parseResp(stream: InputStream): DataResponse {
        return stream.use { JSON.objectMapper.readValue(it, DataResponse::class.java) }
    }

    override fun mk(
        indexPattern: String,
        ordinal: Int,
        meta: CaseMeta?,
        caseDir: Path,
        request: DataRequest,
        response: DataResponse?
    ): DataCase {
        return DataCase(indexPattern, ordinal, meta, caseDir, request, response)
    }

    override fun timeless(req: DataRequest): DataRequest {
        return req.timeless()
    }
}

interface Case<Req, Resp> {
    val indexPattern: String
    val ordinal: Int
    val meta: CaseMeta?
    val caseDir: Path
    val request: Req
    val response: Resp?
}

data class SearchCase(
    override val indexPattern: String,
    override val ordinal: Int,
    override val meta: CaseMeta?,
    override val caseDir: Path,
    override val request: KibanaSearchRequest,
    override val response: KibanaSearchResponse?
) : Case<KibanaSearchRequest, KibanaSearchResponse>

data class DataCase(
    override val indexPattern: String,
    override val ordinal: Int,
    override val meta: CaseMeta?,
    override val caseDir: Path,
    override val request: DataRequest,
    override val response: DataResponse?
) : Case<DataRequest, DataResponse>

package io.hydrolix.ketchup.server.kibanaproxy

import io.hydrolix.ketchup.model.opensearch.*
import io.hydrolix.ketchup.model.testcases.*
import io.hydrolix.ketchup.util.JSON
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.toList

val LearningPlugin = createApplicationPlugin(name = "QueryLearning") {
    val captureDir = File(environment!!.config.property("ketchup.ketchup.learning.capture-dir").getString())
    captureDir.mkdirs()

    val indexMappings =
        environment!!.config.property("ketchup.ketchup.learning.index-mappings").getList().associate {
            val (index, scenarioName) = it.split(":")
            index to scenarioName
        }

    val scenarioDirs = Files.find(captureDir.toPath(), 1, { path, _ -> path.isDirectory() }).toList().drop(1)

    environment!!.log.info("LearningPlugin initializing; capture dir is $captureDir; indexMappings are $indexMappings")

    val (dataRequests, searchRequests) = loadSavedScenarios(scenarioDirs, environment!!.log)

    val maxDataOrds = dataRequests.mapValues { (_, cases) ->
        cases.maxOfOrNull { it.value.ordinal }
    }.toMutableMap()

    val maxSearchOrds = searchRequests.mapValues { (_, cases) ->
        cases.maxOfOrNull { it.value.ordinal }
    }.toMutableMap()

    val dataCaseKey = AttributeKey<DataCase>("dataCase")
    val searchCaseKey = AttributeKey<SearchCase>("searchCase")

    onCallReceive { call, body ->
        if (call.request.httpMethod == HttpMethod.Post) {
            val bytes = body as ByteArray

            when (call.request.path()) {
                "/api/metrics/vis/data" -> {
                    try {
                        val dataReq = JSON.objectMapper.readValue(bytes, DataRequest::class.java)

                        call.application.log.debug("Data request: $dataReq")

                        // TODO when the IndexPattern is an ID-based one, resolve the actual IndexPattern
                        val indexPatterns = dataReq.panels.map { it.indexPattern.stringValue }.toSet()
                        val indexPattern = complain("Data request", "index patterns", indexPatterns, call)

                        if (indexPattern != null) {
                            val scenarioName = indexMappings[indexPattern] ?: indexPattern

                            val (maxOrd, dataCase) = doStuff(
                                "data",
                                dataRequests,
                                scenarioName,
                                dataReq,
                                maxDataOrds[scenarioName] ?: 0,
                                captureDir,
                                indexPattern,
                                DataRequest::timeless,
                                call.application.log
                            ) { ip, no, dir, req ->
                                DataCase(ip, no, null, dir, req, null)
                            }

                            maxDataOrds[scenarioName] = maxOrd
                            call.attributes.put(dataCaseKey, dataCase)
                        }
                    } catch (e: Exception) {
                        call.application.log.info("Couldn't parse Data request", e)
                    }
                }

                "/internal/search/opensearch" -> {
                    try {
                        val searchReq = JSON.objectMapper.readValue(bytes, KibanaSearchRequest::class.java)
                        val scenarioName = indexMappings[searchReq.params.index] ?: searchReq.params.index

                        call.application.log.debug("Search request: $searchReq")

                        val (maxOrd, searchCase) = doStuff("search", searchRequests, scenarioName, searchReq, maxSearchOrds[scenarioName] ?: 0, captureDir, searchReq.params.index, KibanaSearchRequest::timeless, call.application.log) { ip, no, dir, req ->
                            SearchCase(ip, no, null, dir, req, null)
                        }

                        maxSearchOrds[scenarioName] = maxOrd
                        call.attributes.put(searchCaseKey, searchCase)
                    } catch (e: Exception) {
                        call.application.log.info("Couldn't parse Search request", e)
                    }
                }

                "/internal/bsearch" -> {
                    try {
                        val searchReq = JSON.objectMapper.readValue(bytes, BatchRequest::class.java)
                        val indexes = searchReq.batch.map { it.request.params.index }.toSet()

                        val index = complain("Batch request", "index", indexes, call)
                        if (index == null) {
                            call.application.log.warn("Batch request had no index; skipping")
                        } else {
                            // TODO we should really process each member of the batch as a distinct case, but `searchCaseKey` is single-valued...
                            // TODO we should really process each member of the batch as a distinct case, but `searchCaseKey` is single-valued...
                            // TODO we should really process each member of the batch as a distinct case, but `searchCaseKey` is single-valued...
                            // TODO we should really process each member of the batch as a distinct case, but `searchCaseKey` is single-valued...
                        }
                    } catch (e: Exception) {
                        call.application.log.info("Couldn't parse Batch request", e)
                    }
                }
                else -> {
                    // Not interested
                }
            }
        }
    }

    onCallRespond { call, body ->
        if (call.request.httpMethod == HttpMethod.Post) {
            if (body is ByteArrayContent) {
                when (call.request.path()) {
                    "/api/metrics/vis/data" -> {
                        val dataCase = call.attributes[dataCaseKey]
                        try {
                            val dataResp = JSON.objectMapper.readValue(body.bytes(), DataResponse::class.java)

                            call.application.log.debug("Data response: $dataResp")
                        } catch (e: Exception) {
                            call.application.log.info("Couldn't parse data response", e)
                        }
                    }

                    in setOf("/internal/search/opensearch", "/internal/search/es") -> {
                        val searchCase = call.attributes[searchCaseKey]
                        try {
                            val searchResp = JSON.objectMapper.readValue(body.bytes(), KibanaSearchResponse::class.java)
                            call.application.log.debug("Search response: $searchResp")
                        } catch (e: Exception) {
                            call.application.log.info("Couldn't parse search response", e)
                        }
                    }
                }
            } else {
                // The response wasn't a body, never mind
            }
        }
    }
}

private fun complain(
    context: String,
    what: String,
    values: Set<String>,
    call: ApplicationCall
): String? {
    return if (values.isEmpty()) {
        call.application.log.warn("$context had no $what, skipping capture?!")
        null
    } else {
        if (values.size > 1) {
            call.application.log.warn("$context had multiple distinct $what; arbitrarily picking the first one: $values?!")
        }
        values.first()
    }
}

private fun <C : Case<Req, Resp>, Req, Resp> doStuff(
    what: String,
    knownRequests: MutableMap<String, Map<Req, C>>,
    scenarioName: String,
    req: Req,
    maxOrd: Int,
    captureDir: File,
    indexPattern: String,
    timeless: (Req) -> Req,
    logger: Logger,
    f: (String, Int, Path, Req) -> C
): Pair<Int, C> {
    val reqT = timeless(req)

    val existing = knownRequests[scenarioName]?.get(reqT)

    if (existing != null) {
        logger.info("Recognized existing $scenarioName/$what case ${existing.caseDir}")
        return maxOrd to existing
    } else {
        val caseNo = maxOrd + 1
        logger.info("Learning new $scenarioName/$what case #$caseNo")
        val caseDir = captureDir.toPath()
            .resolve(scenarioName)
            .resolve("queries")
            .resolve("$what$caseNo")

        val case = f(indexPattern, caseNo, caseDir, req)

        knownRequests[scenarioName] = knownRequests.getValue(scenarioName) + (reqT to case)

        return caseNo to case
    }
}

private val dataR = "^data(\\d+)$".toRegex()
private val searchR = "^search(\\d+)$".toRegex()

private fun loadSavedScenarios(
    scenarioDirs: List<Path>,
    logger: Logger
): Pair<MutableMap<String, Map<DataRequest, DataCase>>, MutableMap<String, Map<KibanaSearchRequest, SearchCase>>> {
    return loadSavedScenarios(scenarioDirs, logger, DataCaseType, SearchCaseType)
}

fun <DReq, DResp, DC : Case<DReq, DResp>, SReq, SResp, SC: Case<SReq, SResp>>loadSavedScenarios(
    scenarioDirs: List<Path>,
    logger: Logger,
    dataCaseType: CaseType<DReq, DResp, DC>,
    searchCaseType: CaseType<SReq, SResp, SC>,
): Pair<MutableMap<String, Map<DReq, DC>>, MutableMap<String, Map<SReq, SC>>> {
    val dataRequests = mutableMapOf<String, Map<DReq, DC>>().withDefault { mapOf() }
    val searchRequests = mutableMapOf<String, Map<SReq, SC>>().withDefault { mapOf() }

    for (scenarioDir in scenarioDirs) {
        val caseDirs = Files.find(
            scenarioDir.resolve("queries"),
            1,
            { path, _ -> path.isDirectory() }
        ).toList().drop(1)

        for (caseDir in caseDirs) {
            val dm = dataR.matchEntire(caseDir.name)
            val sm = searchR.matchEntire(caseDir.name)
            val metaPath = caseDir.resolve("meta.json")
            val reqPath = caseDir.resolve("request.json")
            val respPath = caseDir.resolve("response.json")

            if (!reqPath.exists() || !respPath.exists()) {
                logger.info("Skipping case $caseDir with missing request/response")
                continue
            }

            if (dm != null) {
                val ord = dm.groupValues[1].toInt()

                val req = tryReq(reqPath, dataCaseType)
                val resp = tryResp(respPath, dataCaseType)

                val meta = if (metaPath.exists()) JSON.objectMapper.readValue(
                    metaPath.toFile(),
                    CaseMeta::class.java
                ) else null

                val case = dataCaseType.mk(
                    scenarioDir.name,
                    ord,
                    meta,
                    caseDir,
                    req,
                    resp
                )
                dataRequests[scenarioDir.name] = dataRequests.getValue(scenarioDir.name) + (dataCaseType.timeless(req) to case)
            } else if (sm != null) {
                val ord = sm.groupValues[1].toInt()

                val req = tryReq(reqPath, searchCaseType)
                val resp = tryResp(respPath, searchCaseType)

                val meta = if (metaPath.exists()) JSON.objectMapper.readValue(
                    metaPath.toFile(),
                    CaseMeta::class.java
                ) else null

                val case = searchCaseType.mk(
                    scenarioDir.name,
                    ord,
                    meta,
                    caseDir,
                    req,
                    resp
                )
                searchRequests[scenarioDir.name] = searchRequests.getValue(scenarioDir.name) + (searchCaseType.timeless(req) to case)
            }
        }
    }

    return dataRequests to searchRequests
}

class FileException(file: File, cause: Throwable) : Exception("Exception processing file ${file.absolutePath}: ${cause.message}", cause)

private fun <DC : Case<DReq, DResp>, DReq, DResp> tryReq(
    reqPath: Path,
    caseType: CaseType<DReq, DResp, DC>
): DReq {
    val file = reqPath.toFile()
    return file.inputStream().use {
        try {
            caseType.parseReq(it)
        } catch (e: Exception) {
            throw FileException(file, e)
        }
    }
}

private fun <DC : Case<DReq, DResp>, DReq, DResp> tryResp(
    respPath: Path,
    caseType: CaseType<DReq, DResp, DC>
): DResp {
    val file = respPath.toFile()
    return file.inputStream().use {
        try {
            caseType.parseResp(it)
        } catch (e: Exception) {
            throw FileException(file, e)
        }
    }
}

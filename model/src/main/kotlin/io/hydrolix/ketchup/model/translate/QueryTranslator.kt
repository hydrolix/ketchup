package io.hydrolix.ketchup.model.translate

import co.elastic.clients.elasticsearch.core.SearchRequest
import io.hydrolix.ketchup.model.query.IRQuery
import java.util.*

// TODO map the projections back to the original searches too; unique names?
data class TranslationResult(
    val clickhouseConnectionId: UUID,
    val query: IRQuery,
    val index: String,
    val requestIds: Set<UUID>
)

interface QueryTranslator {
    fun translate(reqs: LinkedHashMap<UUID, Pair<SearchRequest, String?>>): List<TranslationResult>
}

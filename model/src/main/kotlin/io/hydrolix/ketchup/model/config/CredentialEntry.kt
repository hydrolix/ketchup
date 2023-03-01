package io.hydrolix.ketchup.model.config

import com.fasterxml.jackson.core.type.TypeReference
import java.time.Instant
import java.util.*

data class CredentialEntry(
    val id: UUID,
    val name: String?,
    val created: Instant,
    val modified: Instant?,
    val username: String?,
    val credential: String?
) {
    companion object {
        val ListOfThese = object : TypeReference<List<CredentialEntry>>() { }
    }
}

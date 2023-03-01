package io.hydrolix.ketchup.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.kotlinModule

@Suppress("FunctionName", "HasPlatformType")
object JSON {
    // TODO move this into server-common if it turns out dependencies will allow it
    val objectMapper = ObjectMapper()
        .registerModule(Jdk8Module())
        .registerModule(JavaTimeModule())
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false) // ISO8601 please
        .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true) // TODO can we not do this globally?
        .registerModule(kotlinModule {
            enable(KotlinFeature.SingletonSupport)
        })

    fun JsonNode.str_(s: String): String? {
        val kid = this[s] ?: return null
        if (kid.isTextual) return kid.textValue()
        error("Expected $s to be a string!")
    }

    fun JsonNode.str(s: String): String {
        return str_(s) ?: error("`$s` is required")
    }

    fun JsonNode.obj_(s: String): ObjectNode? {
        val kid = this[s] ?: return null
        if (kid.isObject) return kid as ObjectNode
        error("Expected $s to be an object!")
    }

    fun JsonNode.obj(s: String): ObjectNode {
        return obj_(s) ?: error("`$s` is required")
    }
}
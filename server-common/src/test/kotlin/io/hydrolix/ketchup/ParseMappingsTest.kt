package io.hydrolix.ketchup

import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch.indices.GetIndexResponse
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpParser
import io.hydrolix.ketchup.util.JSON
import org.junit.jupiter.api.Test

class ParseMappingsTest {
    @Test
    fun `try to parse mappings json`() {
        javaClass.getResourceAsStream("/scenarios/random/hits_mappings.json").use { stream ->
            val resp = GetIndexResponse._DESERIALIZER.deserialize(
                JacksonJsonpParser(JSON.objectMapper.createParser(stream)),
                JacksonJsonpMapper(JSON.objectMapper)
            )

            for ((name, state) in resp.result()) {
                println("Aliases for index $name: ${state.aliases()}")
                println("Mappings for index $name: ${state.mappings()}")
                var i = 0
                for ((name, prop) in state.mappings()?.properties().orEmpty()) {
                    i++
                    print("  $name [$i]: ")
                    when (prop._kind()) {
                        Property.Kind.Object -> {
                            println(" object:")
                            var j = 0
                            for ((k,v) in prop.`object`().properties()) {
                                j++
                                println("    $k[$j] -> $v")
                            }
                        }
                        Property.Kind.Alias  -> println(" alias:${prop.alias().path()}")
                        else -> println("other:$prop")
                    }
                }
            }
        }
    }
}
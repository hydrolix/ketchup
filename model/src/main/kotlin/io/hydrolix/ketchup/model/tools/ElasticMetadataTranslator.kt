package io.hydrolix.ketchup.model.tools

import co.elastic.clients.elasticsearch._types.mapping.*
import co.elastic.clients.elasticsearch.indices.GetIndexResponse
import co.elastic.clients.elasticsearch.indices.IndexState
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.json.jackson.JacksonJsonpParser
import io.hydrolix.ketchup.model.*
import io.hydrolix.ketchup.model.config.*
import io.hydrolix.ketchup.util.JSON
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.JDBCType

object ElasticMetadataTranslator {
    private val logger = LoggerFactory.getLogger(ElasticMetadataTranslator::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val primaryField = args[0]
        val timestampField = args[1]
        val files = args.drop(2).map { File(it) }.filter { it.exists() && it.isFile && it.canRead() }

        val aliases = mutableMapOf<String, Set<String>>().withDefault { emptySet() }

        val outByIndex = mutableMapOf<String, List<MappedThing>>().withDefault { emptyList() }
        for (file in files) {
            logger.info("Processing ${file.absolutePath}")
            file.inputStream().use { stream ->
                val resp = GetIndexResponse._DESERIALIZER.deserialize(
                    JacksonJsonpParser(JSON.objectMapper.createParser(stream)),
                    JacksonJsonpMapper(JSON.objectMapper)
                )

                for ((indexName, indexState) in resp.result()) {
                    logger.info("  Index $indexName")

                    collectAliases(indexName, indexState, aliases, logger)

                    val props = indexState.mappings()?.properties().orEmpty()

                    for ((name, property) in props) {
                        outByIndex[indexName] = outByIndex.getValue(indexName) + prop(file, indexName, emptyList(), name, property)
                    }
                }
            }
        }

        // Check if any prefixes are shared across multiple indexes (other than timestamp etc.?)
        val byPrefix = mutableMapOf<String, Set<String>>().withDefault { emptySet() }
        for ((indexName, outs) in outByIndex) {
            for (out in outs) {
                byPrefix[out.path.first()] = byPrefix.getValue(out.path.first()) + indexName
            }
        }

        val shared = byPrefix.filter { it.value.size > 1 }
        for ((prefix, indexNames) in shared) {
            System.err.println("$prefix is found in multiple indices: $indexNames")
        }

        for ((indexName, out) in outByIndex) {
            val sizes = out.map { it.path.size }.groupBy { it }.map { it.key to it.value.size }.sortedBy { it.first }
            System.err.println("$indexName sizes:\n  ${sizes.joinToString("\n  ") { it.first.toString() + ": " + it.second }}")

            System.err.println("$indexName 100 longest bois:")
            out.sortedByDescending { it.path.size }.take(100).forEach {
                System.err.println("  $indexName: ${it.path.joinToString(".")} [${it.path.size}]")
            }

            val tmp2 = File.createTempFile("elasticMetadata-$indexName-", ".json")
            tmp2.writeBytes(JSON.objectMapper.writeValueAsBytes(
                ElasticMetadata(
                indexName,
                aliases.filter { it.value.contains(indexName) }.map { it.key },
                primaryField,
                timestampField,
                NestedFieldsConfig.default,
                out.filterIsInstance<MappedColumn>(),
                out.filterIsInstance<MappedAlias>()
            )
            ))
            println(tmp2.absolutePath)
        }
    }

    private fun prop(
        file: File,
        indexName: String,
        path: List<String>,
        name: String,
        prop: Property,
    ): List<MappedThing> {
        val escapedName = name.replace('.', '*')
        val pathPlus = path + escapedName

        return when (prop._kind()) {
            Property.Kind.Object -> {
                val obj = prop.`object`()
                val out = mutableListOf<MappedThing>()
                for ((childName, property) in obj.properties().orEmpty()) {
                    out += prop(file, indexName, pathPlus, childName, property)
                }
                out
            }
            Property.Kind.Keyword      -> aliases(pathPlus, prop.keyword())      + keyword  (prop.keyword(), pathPlus)
            Property.Kind.Text         -> aliases(pathPlus, prop.text())         + text     (prop.text(), pathPlus)
            Property.Kind.Integer      -> aliases(pathPlus, prop.integer())      + int      (prop.integer(), pathPlus)
            Property.Kind.Short        -> aliases(pathPlus, prop.short_())       + short    (prop.short_(), pathPlus)
            Property.Kind.Long         -> aliases(pathPlus, prop.long_())        + long     (prop.long_(), pathPlus)
            Property.Kind.UnsignedLong -> aliases(pathPlus, prop.unsignedLong()) + ulong    (prop.unsignedLong(), pathPlus)
            Property.Kind.Float        -> aliases(pathPlus, prop.float_())       + float    (prop.float_(), pathPlus)
            Property.Kind.Double       -> aliases(pathPlus, prop.double_())      + double   (prop.double_(), pathPlus)
            Property.Kind.Date         -> aliases(pathPlus, prop.date())         + date     (prop.date(), pathPlus)
            Property.Kind.DateNanos    -> aliases(pathPlus, prop.dateNanos())    + date     (prop.date(), pathPlus)
            Property.Kind.Boolean      -> aliases(pathPlus, prop.boolean_())     + boolean  (prop.boolean_(), pathPlus)
            Property.Kind.Ip           -> aliases(pathPlus, prop.ip())           + ip       (prop.ip(), pathPlus)
            Property.Kind.Alias -> {
                val alias = prop.alias()
                listOf(
                    MappedAlias(
                        pathPlus,
                        alias.path() ?: error("Alias was missing a path?!") // TODO should the alias path also be scoped to our current nesting depth?
                    )
                )
            }
            else -> {
                // TODO others...
                logger.warn("File ${file.absolutePath}, index $indexName: Unsupported property ${(path+name).joinToString(".")} of type ${prop._kind()}")
                emptyList()
            }
        }
    }

    private fun aliases(path: List<String>, prop: PropertyBase): List<MappedAlias> {
        val fields = prop.fields()

        if (fields.isEmpty()) return emptyList()

        return fields.keys.map {
            MappedAlias(path + it, path.joinToString("_")) // TODO `_` will often be wrong here
        }
    }

    private fun boolean(
        prop: BooleanProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue() // TODO do we care?
        val indexed = prop.index() // TODO do we care?
        return MappedColumn(path, Property.Kind.Boolean, JDBCType.BOOLEAN, null)
    }

    private fun ip(
        prop: IpProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        val indexed = prop.index()
        val ignoreMalformed = prop.ignoreMalformed()
        return MappedColumn(path, Property.Kind.Ip, JDBCType.VARCHAR, null)
    }

    private fun date(
        prop: DateProperty,
        path: List<String>
    ) : MappedColumn {
        val fmt = prop.format() ?: "date_time"
        val res = TimeResolution.ms // TODO try to set this properly if `fmt` is set

        return MappedColumn(path, Property.Kind.Date, parseElasticTimeFormat(fmt, logger), DateTimeInfo(fmt, res))
    }

    private fun double(
        prop: DoubleNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue() // TODO find a place to put this
        return MappedColumn(path, Property.Kind.Double, JDBCType.DOUBLE, null)
    }

    private fun float(
        prop: FloatNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        return MappedColumn(path, Property.Kind.Float, JDBCType.FLOAT, null)
    }

    private fun long(
        prop: LongNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        return MappedColumn(path, Property.Kind.Long, JDBCType.BIGINT, null)
    }

    private fun ulong(
        prop: UnsignedLongNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        return MappedColumn(path, Property.Kind.Long, JDBCType.BIGINT, null)
    }

    private fun int(
        prop: IntegerNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        return MappedColumn(path, Property.Kind.Integer, JDBCType.INTEGER, null)
    }

    private fun short(
        prop: ShortNumberProperty,
        path: List<String>
    ) : MappedColumn {
        val nullValue = prop.nullValue()
        return MappedColumn(path, Property.Kind.Short, JDBCType.SMALLINT, null)
    }

    private fun text(
        prop: TextProperty,
        path: List<String>
    ) : MappedColumn {
        val index = prop.index() // TODO if this is false, do we want to also set index=false on the hdx side?
        val indexPhrases = prop.indexPhrases()
        val analyzer = prop.analyzer()
        val searchAnalyzer = prop.searchAnalyzer()
        val searchQuoteAnalyzer = prop.searchQuoteAnalyzer()
        val ignoreAbove = prop.ignoreAbove() // TODO do we need to care about this?
        return MappedColumn(path, Property.Kind.Text, JDBCType.VARCHAR, null)
    }

    private fun keyword(
        prop: KeywordProperty,
        path: List<String>
    ) : MappedColumn {
        val indexed = prop.index()  // TODO if this is false, do we want to also set index=false on the hdx side?
        val indexOptions = prop.indexOptions()
        val nullValue = prop.nullValue()
        val normalizer = prop.normalizer()
        val boost = prop.boost()
        val splitQueriesOnWhitespace = prop.splitQueriesOnWhitespace()
        val ignoreAbove = prop.ignoreAbove() // TODO do we need to care about this?
        return MappedColumn(path, Property.Kind.Keyword, JDBCType.VARCHAR, null)
    }

    fun readMappings(files: List<File>): MutableMap<String, List<MappedThing>> {
        val aliases = mutableMapOf<String, Set<String>>().withDefault { emptySet() }
        val outByIndex = mutableMapOf<String, List<MappedThing>>().withDefault { emptyList() }

        for (file in files) {
            logger.info("Processing ${file.absolutePath}")
            file.inputStream().use { stream ->
                val resp = GetIndexResponse._DESERIALIZER.deserialize(
                    JacksonJsonpParser(JSON.objectMapper.createParser(stream)),
                    JacksonJsonpMapper(JSON.objectMapper)
                )

                for ((indexName, indexState) in resp.result()) {
                    logger.info("  Index $indexName")

                    collectAliases(indexName, indexState, aliases, logger)

                    val props = indexState.mappings()?.properties().orEmpty()

                    for ((name, property) in props) {
                        outByIndex[indexName] =
                            outByIndex.getValue(indexName) + prop(file, indexName, emptyList(), name, property)
                    }
                }
            }
        }

        return outByIndex
    }

    fun collectAliases(
        indexName: String,
        indexState: IndexState,
        aliases: MutableMap<String, Set<String>>,
        logger: Logger
    ) {
        for ((alias, info) in indexState.aliases()) {
            val infos = listOfNotNull(
                info.searchRouting(),
                info.isHidden,
                info.isWriteIndex,
                info.filter(),
                info.routing(),
                info.indexRouting()
            )
            if (infos.isNotEmpty()) {
                logger.warn("Index $indexName: Alias definition had some unsupported complexity: $alias -> $info")
            }
            aliases[alias] = aliases.getValue(alias) + indexName
        }
    }
}


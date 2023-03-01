package io.hydrolix.ketchup.model.hdx

import co.elastic.clients.elasticsearch._types.mapping.Property
import io.hydrolix.ketchup.model.config.DateTimeInfo
import io.hydrolix.ketchup.model.config.ElasticMetadata
import io.hydrolix.ketchup.model.config.MappedColumn
import io.hydrolix.ketchup.model.TimeResolution
import io.hydrolix.ketchup.model.tools.ElasticMetadataTranslator
import io.hydrolix.ketchup.util.JSON
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.JDBCType

object HydrolixMetadataTranslator {
    private val logger = LoggerFactory.getLogger(ElasticMetadataTranslator::class.java)

    private val catchAllColumn = OutputColumn(
        "catch_all",
        null,
        OutputColumnDatatype(
            type = HdxValueType.map,
            catchAll = true,
            elements = listOf(
                OutputColumnDatatype(type = HdxValueType.string),
                OutputColumnDatatype(type = HdxValueType.string)
            )
        )
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // Note that Hydrolix indexes everything by default, and our primary key needs to be a timestamp, so there's no
        // point asking for Elastic's ID field here
        val timestampField = args[0] // TODO this can come from the ElasticMetadata now
        val files = args.drop(1).map { File(it) }.filter { it.exists() && it.isFile && it.canRead() }

        val mappings = files.map { file ->
            file.inputStream().use { stream ->
                val mapping = JSON.objectMapper.readValue(stream, ElasticMetadata::class.java)
                mapping.indexName to mapping
            }
        }

        val allOutCols = linkedSetOf<OutputColumn>()

        for ((indexName, mapping) in mappings) {
            val thisIndexOutCols = mutableListOf<OutputColumn>()

            for (col in mapping.columnMappings) {
                val isRoot = col.path.size == 1
                val isObject = col.elasticType == Property.Kind.Object
                val hasChildren = mapping.columnMappings.any { it.path.size > 1 && it.path.first() == col.path.first() }
                val isTopLevel = mapping.nestedFieldsConfig.topLevelFields.contains(col.path.first())

                if (isRoot && isObject && (isTopLevel || !hasChildren)) {
                    // Root objects that are marked as top-level, or don't have children, need to be maps
                    thisIndexOutCols += OutputColumn(
                        col.path.first(),
                        null,
                        OutputColumnDatatype(
                            HdxValueType.map,
                            elements = listOf(
                                OutputColumnDatatype(HdxValueType.string),
                                OutputColumnDatatype(HdxValueType.string)
                            )
                        )
                    )
                } else if (isRoot && !isObject) {
                    // Normal top-level field

                    val qname = col.path.joinToString(mapping.nestedFieldsConfig.pathSeparator) // TODO escaping?

                    val hdxType = col.hdxType()

                    thisIndexOutCols += when (col.jdbcType) {
                        JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE -> {
                            val ts = col.columnInfo as? DateTimeInfo

                            OutputColumn(
                                qname,
                                null,
                                OutputColumnDatatype(
                                    primary = if (qname == timestampField) true else null,
                                    type = hdxType,
                                    format = ts?.format,
                                    resolution = ts?.resolution,
                                    source = OutputColumnSource(fromInputField = qname), // TODO we don't need fromInputField if it's identical apparently
                                )
                            )
                        }

                        JDBCType.DATE, JDBCType.TIME_WITH_TIMEZONE, JDBCType.TIME -> {
                            logger.warn(
                                "Mapping partial date/time component to string: {}",
                                col
                            ) // TODO is this reasonable?
                            OutputColumn(
                                qname,
                                null,
                                OutputColumnDatatype(
                                    type = HdxValueType.string,
                                    source = OutputColumnSource(fromInputField = col.path.joinToString("."))
                                )
                            )
                        }

                        else -> {
                            OutputColumn(
                                qname,
                                null,
                                OutputColumnDatatype(
                                    type = hdxType,
                                    source = OutputColumnSource(fromInputField = col.path.joinToString("."))
                                )
                            )
                        }
                    }
                } else {
                    logger.warn("Don't know how to map {}", col)

                    continue
                }
            }

            allOutCols += thisIndexOutCols

            val thisIndexTransform = Transform(
                indexName,
                null,
                TransformType.json,
                table = null,
                TransformSettings(
                    isDefault = true,
                    compression = TransformCompression.none,
                    formatDetails = JsonFormatDetails(
                        JsonFlattening(
                            true,
                            FlatteningStrategy(".", ""),
                            null,
                            null
                        )
                    ),
                    outputColumns = listOf(catchAllColumn) + thisIndexOutCols
                )
            )

            val tmp = File.createTempFile("transform-$indexName-", ".json")
            tmp.writeBytes(JSON.objectMapper.writeValueAsBytes(thisIndexTransform))
            println(tmp.absolutePath)
        }

        val allIndexesTransform = Transform(
            files.joinToString(", ") { it.name },
            null,
            TransformType.json,
            table = null,
            TransformSettings(
                isDefault = true,
                compression = TransformCompression.none,
                formatDetails = JsonFormatDetails(
                    JsonFlattening(
                        true,
                        FlatteningStrategy(".", ""),
                        null,
                        null
                    )
                ),
                outputColumns = listOf(catchAllColumn) + allOutCols.toList()
            )
        )

        val tmp = File.createTempFile("transform-allIndexes-", ".json")
        tmp.writeBytes(JSON.objectMapper.writeValueAsBytes(allIndexesTransform))
        println(tmp.absolutePath)
    }

    private fun MappedColumn.hdxType(): HdxValueType {
        return when (this.jdbcType) {
            JDBCType.TIMESTAMP, JDBCType.TIMESTAMP_WITH_TIMEZONE -> {
                val ts = this.columnInfo as? DateTimeInfo
                when (ts?.resolution) {
                    TimeResolution.ms, TimeResolution.ns, TimeResolution.cs, TimeResolution.us -> HdxValueType.datetime64
                    else -> HdxValueType.datetime
                }
            }
            JDBCType.DATE, JDBCType.TIME_WITH_TIMEZONE, JDBCType.TIME -> {
                logger.warn("Mapping partial date/time component to string: {}", this) // TODO is this reasonable?
                HdxValueType.string
            }
            JDBCType.VARCHAR,
            JDBCType.CHAR,
            JDBCType.NVARCHAR,
            JDBCType.NCHAR,
            JDBCType.LONGVARCHAR,
            JDBCType.LONGNVARCHAR,
            JDBCType.CLOB -> HdxValueType.string

            JDBCType.BIGINT -> HdxValueType.int64

            JDBCType.INTEGER,
            JDBCType.SMALLINT,
            JDBCType.TINYINT -> HdxValueType.int32

            JDBCType.DECIMAL,
            JDBCType.FLOAT,
            JDBCType.DOUBLE,
            JDBCType.NUMERIC -> HdxValueType.double

            JDBCType.BIT,
            JDBCType.BOOLEAN -> HdxValueType.boolean

            JDBCType.BINARY, JDBCType.LONGVARBINARY, JDBCType.VARBINARY, JDBCType.BLOB -> {
                logger.warn("Mapping binary field to string: {}", this) // TODO is this reasonable?
                HdxValueType.string
            }

            else -> {
                logger.warn("Mapping field of type ${this.jdbcType} to string; this might not be valid")
                HdxValueType.string
            }
        }
    }
}

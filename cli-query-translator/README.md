# KETCHUP CLI Query Translator

## What is it?
This is a CLI tool that reads Elastic/OpenSearch queries, as well as configuration information, and attempts to produce 
SQL queries that match the input query's semantics.

## Command-Line Arguments
| Long Name                    | Short Name | Description                                                                                                                                         |
|------------------------------|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| --idField                    | (none)     | Name of the ID field; required unless a `--dbMappingFile` provides it                                                                               |
| --table                      | (none)     | Name of the table; required unless a `--dbMappingFile` provides it                                                                                  |
| --index                      | -i         | index name or `cluster:index` target string – required if the document on stdin doesn't specify an index, or when `--queryString` is used           |
| (positional)                 | (none)     | Input query filename, or `-` to read from stdin. Optional; if not specified `--queryString` is required.                                            |
| <nobr>--queryString</nobr>   | -q         | A KQL/Lucene query string – optional; if used, nothing will be read from stdin                                                                      |
| --timeField                  | -t         | The name of the timestamp field – optional unless `--min` and `--max` are specified                                                                 |
| --minTime                    | -min       | Minimum (inclusive) timestamp, in ISO8601 or whatever Elastic accepts in `strict_date_optional_time` format – optional unless maxTime is specified. |
| --maxTime                    | -max       | Maximum (inclusive) timestamp – optional unless minTime is specified                                                                                |
| <nobr>--metadataFile</nobr>  | -e         | Paths of an Elastic Metadata file; can be repeated. Optional, but if not used, nested fieldnames won’t be mapped correctly.                         |
| <nobr>--dbMappingFile</nobr> | -d         | Path of a DBMapping file; can be repeated. If not available, `--idField` and `--table` must be specified.                                           |

## Input (stdin or positional argument)
If no `--queryString` or query document path is passed as a command-line argument, or `-` is passed, the Translator will 
expect a single Kibana or Elastic search request as a JSON document on stdin, and will block, appearing to hang, if none 
is provided.

It accepts “wrapped” queries which include an index name so the request can be self-contained:
   
    {
      "index": "elasticlogsnt*:search-logs",
      "body": { /* (the usual Elastic search request) */ }
    }
    
As well as “unwrapped” queries (just the content of the `body` field above), in which case an index name should be 
passed using the `-i`/`--index` command-line argument.

## Output (stderr)
The Translator will log errors/warnings on stderr.

## Output (stdout)
If the query could be translated successfully, the Translator will pretty-print its SQL to stdout.

## Running
If you have any [Elastic Metadata](../README.md#elastic-metadata-mappings) files that are relevant to your current use 
case, you'll need their paths here, specified with `-e`/`--metadataFile` arguments. The Translator works without them, 
but the translation may not be ideal, e.g. especially when nested fields are involved.  

### JSON Query Document

#### query.json
Create an Elastic/OpenSearch query DSL as a JSON file: 
```json
{
    "query": {
        "query_string": {
            "query": "foo:bar AND baz:quux*"
        }
    }
}
```

Then pass its path to the Translator as a positional argument:
```
java -jar cli-query-translator/build/libs/cli-query-translator-all.jar \
    -e myElasticMetadata.json \
    --index my_index \
    --table my_db.my_table \
    query.json
```

Alternatively, a query can be fed in via stdin if `-` is passed as the input filename:
```
cat query.json | \
    java -jar cli-query-translator/build/libs/cli-query-translator-all.jar \
        -e myElasticMetadata.json \
        --index my_index \
        --table my_db.my_table \
        -
```
### KQL/Lucene Query as Command-Line Argument
                       
```
java -jar query-translator-7d9e3afa.jar \
    -e myElasticMetadata.json \
    --index my_index \
    --table my_db.my_table \
    --queryString 'foo:bar AND baz:quux*' \
    --timestampField "@timestamp" \
    --min 2023-01-01T00:00:00.000Z \
    --max 2023-01-31T23:59:59.999Z
```

## Environment Variables
The Query Translator doesn't require any environment variables, but they can be used instead of command-line parameters, 
e.g. to run a batch job that translates queries for multiple different indexes/tables.

| File Type        | Variable Name                       | Comments                                                                                              |
|------------------|-------------------------------------|-------------------------------------------------------------------------------------------------------|
| DB Mapping       | `DB_MAPPINGS_JSON_URL`              | A URL (not a filesystem path) where a single DB Mapping JSON file can be found.                       |
| Elastic Metadata | `ELASTIC_METADATA_JSON_URLS.<0..n>` | Numbered environment variables containing URLs (not filesystem paths!) of Elastic Metadata JSON files |

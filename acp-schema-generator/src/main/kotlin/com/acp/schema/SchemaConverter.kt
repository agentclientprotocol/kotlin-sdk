package com.acp.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import java.io.File

/**
 * Recursively updates all $ref paths in the JSON schema to use OpenAPI format.
 * Converts #/$defs/... to #/components/schemas/...
 */
fun updateRefs(mapper: ObjectMapper, node: JsonNode): JsonNode {
    return when (node) {
        is ObjectNode -> {
            val updated = mapper.createObjectNode()
            node.fields().forEach { (key, value) ->
                if (key == "\$ref" && value.isTextual) {
                    val refValue = value.asText()
                    val newRef = refValue.replace("#/\$defs/", "#/components/schemas/")
                    updated.set<JsonNode>(key, TextNode(newRef))
                } else {
                    updated.set<JsonNode>(key, updateRefs(mapper, value))
                }
            }
            updated
        }
        is ArrayNode -> {
            val updated = mapper.createArrayNode()
            node.forEach { element ->
                updated.add(updateRefs(mapper, element))
            }
            updated
        }
        else -> node
    }
}

fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: SchemaConverter <input-json-file> <output-yaml-file> <version>")
        System.exit(1)
    }

    val inputFile = File(args[0])
    val outputFile = File(args[1])
    val version = args[2].removePrefix("v")

    if (!inputFile.exists()) {
        println("Error: Input file does not exist: ${inputFile.absolutePath}")
        System.exit(1)
    }

    println("Converting schema to OpenAPI YAML...")

    val jsonMapper = ObjectMapper()
    val yamlMapper = ObjectMapper(
        YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
    )

    val jsonSchema: JsonNode = jsonMapper.readTree(inputFile)

    // Extract $defs content (the actual schema definitions)
    val defsNode = jsonSchema.get("\$defs") ?: jsonSchema

    // Update all $ref paths from #/$defs/... to #/components/schemas/...
    val updatedSchemas = updateRefs(jsonMapper, defsNode)

    // Convert JSON Schema to OpenAPI format
    val openApiSpec = mapOf(
        "openapi" to "3.1.0",
        "info" to mapOf(
            "title" to "Agent Client Protocol",
            "version" to version
        ),
        "components" to mapOf(
            "schemas" to updatedSchemas
        )
    )

    outputFile.parentFile?.mkdirs()
    yamlMapper.writeValue(outputFile, openApiSpec)

    println("OpenAPI spec generated at: ${outputFile.absolutePath}")
}

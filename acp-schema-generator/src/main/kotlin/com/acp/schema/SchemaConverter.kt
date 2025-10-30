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

/**
 * Adds title properties to oneOf items that have a discriminator.
 * Uses the discriminator property to determine which property contains the type value.
 * For example, if discriminator propertyName is "type" and const value is "text",
 * generates title "TextContentBlock" for a ContentBlock parent.
 */
fun addTitlesToOneOfItems(mapper: ObjectMapper, schemas: ObjectNode): ObjectNode {
    val updated = mapper.createObjectNode()

    schemas.fields().forEach { (schemaName, schemaNode) ->
        if (schemaNode is ObjectNode) {
            val processedSchema = processSchemaForTitles(mapper, schemaNode, schemaName)
            updated.set<JsonNode>(schemaName, processedSchema)
        } else {
            updated.set<JsonNode>(schemaName, schemaNode)
        }
    }

    return updated
}

/**
 * Processes a single schema to add titles to oneOf/anyOf items with a discriminator
 */
fun processSchemaForTitles(mapper: ObjectMapper, schema: ObjectNode, schemaName: String): ObjectNode {
    val updated = mapper.createObjectNode()

    schema.fields().forEach { (key, value) ->
        when {
            (key == "oneOf" || key == "anyOf") && value is ArrayNode && schema.has("discriminator") -> {
                // Get the discriminator property name
                val discriminator = schema.get("discriminator")
                val propertyName = discriminator?.get("propertyName")?.asText()

                if (propertyName != null) {
                    // Process each oneOf/anyOf item to add title
                    val updatedArray = mapper.createArrayNode()
                    value.forEach { item ->
                        if (item is ObjectNode) {
                            updatedArray.add(addTitleToOneOfItem(mapper, item, schemaName, propertyName))
                        } else {
                            updatedArray.add(item)
                        }
                    }
                    updated.set<JsonNode>(key, updatedArray)
                } else {
                    updated.set<JsonNode>(key, value)
                }
            }
            else -> {
                updated.set<JsonNode>(key, value)
            }
        }
    }

    return updated
}

/**
 * Adds a title property to a oneOf/anyOf item based on its discriminator property const value
 */
fun addTitleToOneOfItem(mapper: ObjectMapper, item: ObjectNode, parentName: String, discriminatorPropertyName: String): ObjectNode {
    // Check if this item has the discriminator property with a const value
    val properties = item.get("properties")
    if (properties is ObjectNode) {
        val discriminatorProperty = properties.get(discriminatorPropertyName)
        if (discriminatorProperty is ObjectNode) {
            val constValue = discriminatorProperty.get("const")?.asText()
            if (constValue != null && !item.has("title")) {
                // Generate title from const value and parent name
                val title = generateTitle(constValue, parentName)
                val updated = mapper.createObjectNode()

                // Add title as the first property
                updated.put("title", title)

                // Copy all existing properties
                item.fields().forEach { (key, value) ->
                    updated.set<JsonNode>(key, value)
                }

                return updated
            }
        }
    }

    return item
}

/**
 * Generates a title from a type const value and parent name.
 * Example: constValue="text", parentName="ContentBlock" -> "TextContentBlock"
 * Example: constValue="resource_link", parentName="ContentBlock" -> "ResourceLinkContentBlock"
 */
fun generateTitle(constValue: String, parentName: String): String {
    // Split by underscore and capitalize each part
    val parts = constValue.split("_")
    val capitalizedParts = parts.joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
    return capitalizedParts + parentName
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

    // Add titles to oneOf items with type discriminator
    val schemasWithTitles = if (updatedSchemas is ObjectNode) {
        addTitlesToOneOfItems(jsonMapper, updatedSchemas)
    } else {
        updatedSchemas
    }

    // Convert JSON Schema to OpenAPI format
    val openApiSpec = mapOf(
        "openapi" to "3.1.0",
        "info" to mapOf(
            "title" to "Agent Client Protocol",
            "version" to version
        ),
        "components" to mapOf(
            "schemas" to schemasWithTitles
        )
    )

    outputFile.parentFile?.mkdirs()
    yamlMapper.writeValue(outputFile, openApiSpec)

    println("OpenAPI spec generated at: ${outputFile.absolutePath}")
}

@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SessionConfigSelectOptionsSerializerTest {

    @Test
    fun `decode session-new response params with config options`() {
        val json = """
            {
                "_direction": "incoming",
                "_type": "response",
                "id": 1,
                "method": "session/new",
                "params": {
                  "sessionId": "019c1edc-aae9-70d0-ba38-024381e6c6c3",
                  "modes": {
                    "currentModeId": "auto",
                    "availableModes": [
                      {
                        "id": "read-only",
                        "name": "Read Only",
                        "description": "Codex can read files in the current workspace. Approval is required to edit files or access the internet."
                      },
                      {
                        "id": "auto",
                        "name": "Default",
                        "description": "Codex can read and edit files in the current workspace, and run commands. Approval is required to access the internet or edit other files. (Identical to Agent mode)"
                      },
                      {
                        "id": "full-access",
                        "name": "Full Access",
                        "description": "Codex can edit files outside this workspace and access the internet without asking for approval. Exercise caution when using."
                      }
                    ]
                  },
                  "models": {
                    "currentModelId": "gpt-5.2-codex/xhigh",
                    "availableModels": [
                      {
                        "modelId": "gpt-5.2-codex/low",
                        "name": "gpt-5.2-codex (low)",
                        "description": "Latest frontier agentic coding model. Fast responses with lighter reasoning"
                      }
                    ]
                  },
                  "configOptions": [
                    {
                      "id": "mode",
                      "name": "Approval Preset",
                      "description": "Choose an approval and sandboxing preset for your session",
                      "category": "mode",
                      "type": "select",
                      "currentValue": "auto",
                      "options": [
                        {
                          "value": "read-only",
                          "name": "Read Only",
                          "description": "Codex can read files in the current workspace. Approval is required to edit files or access the internet."
                        },
                        {
                          "value": "auto",
                          "name": "Default",
                          "description": "Codex can read and edit files in the current workspace, and run commands. Approval is required to access the internet or edit other files. (Identical to Agent mode)"
                        },
                        {
                          "value": "full-access",
                          "name": "Full Access",
                          "description": "Codex can edit files outside this workspace and access the internet without asking for approval. Exercise caution when using."
                        }
                      ]
                    }
                  ]
                }
              }
        """.trimIndent()

        val request = ACPJson.decodeFromString(JsonRpcRequest.serializer(), json)
        assertEquals("session/new", request.method.name)

        val response = ACPJson.decodeFromJsonElement(
            NewSessionResponse.serializer(),
            request.params ?: JsonNull
        )

        assertEquals(SessionId("019c1edc-aae9-70d0-ba38-024381e6c6c3"), response.sessionId)
        assertEquals(SessionModeId("auto"), response.modes?.currentModeId)
        assertEquals(ModelId("gpt-5.2-codex/xhigh"), response.models?.currentModelId)

        val configOptions = response.configOptions
        assertNotNull(configOptions)
        val first = configOptions.first()
        val select = assertIs<SessionConfigOption.Select>(first)
        val options = assertIs<SessionConfigSelectOptions.Flat>(select.options)
        assertEquals(SessionConfigValueId("auto"), select.currentValue)
        assertEquals(3, options.options.size)
    }

    @Test
    fun `decode session-new response params with grouped config options`() {
        val json = """
            {
              "id": "models",
              "name": "Model",
              "currentValue": "ask",
              "type": "select",
              "options": [
                {
                  "group": "Provider A",
                  "options": [
                    {
                      "value": "model-1",
                      "name": "Model 1",
                      "description": "The fastest model"
                    }
                  ]
                },
                {
                  "group": "Provider B",
                  "options": [
                    {
                      "value": "model-2",
                      "name": "Model 2",
                      "description": "The most powerful model"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val option = ACPJson.decodeFromString(SessionConfigOption.serializer(), json)
        val select = assertIs<SessionConfigOption.Select>(option)
        val options = assertIs<SessionConfigSelectOptions.Grouped>(select.options)
        assertEquals(SessionConfigValueId("ask"), select.currentValue)
        assertEquals(2, options.groups.size)
        assertEquals("Provider A", options.groups[0].group.value)
        assertEquals(1, options.groups[0].options.size)
        assertEquals(SessionConfigValueId("model-1"), options.groups[0].options[0].value)
    }

    @Test
    fun `decode rfd session-new response example with config options`() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "sessionId": "sess_abc123def456",
                "configOptions": [
                  {
                    "id": "mode",
                    "name": "Session Mode",
                    "description": "Optional description for the Client to display to the user.",
                    "category": "mode",
                    "type": "select",
                    "currentValue": "ask",
                    "options": [
                      {
                        "value": "ask",
                        "name": "Ask",
                        "description": "Request permission before making any changes"
                      },
                      {
                        "value": "code",
                        "name": "Code",
                        "description": "Write and modify code with full tool access"
                      }
                    ]
                  },
                  {
                    "id": "models",
                    "name": "Model",
                    "category": "model",
                    "type": "select",
                    "currentValue": "ask",
                    "options": [
                      {
                        "value": "model-1",
                        "name": "Model 1",
                        "description": "The fastest model"
                      },
                      {
                        "value": "model-2",
                        "name": "Model 2",
                        "description": "The most powerful model"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(JsonRpcResponse.serializer(), json)
        val result = ACPJson.decodeFromJsonElement(
            NewSessionResponse.serializer(),
            response.result ?: JsonNull
        )

        val configOptions = result.configOptions
        assertNotNull(configOptions)
        assertEquals(2, configOptions.size)
        val first = assertIs<SessionConfigOption.Select>(configOptions[0])
        val firstOptions = assertIs<SessionConfigSelectOptions.Flat>(first.options)
        assertEquals(SessionConfigValueId("ask"), first.currentValue)
        assertEquals(2, firstOptions.options.size)
    }

    @Test
    fun `decode rfd session-set-config-option request example`() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "method": "session/set_config_option",
              "params": {
                "sessionId": "sess_abc123def456",
                "configId": "mode",
                "value": "code"
              }
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(JsonRpcRequest.serializer(), json)
        assertEquals("session/set_config_option", request.method.name)

        val params = ACPJson.decodeFromJsonElement(
            SetSessionConfigOptionRequest.serializer(),
            request.params ?: JsonNull
        )
        assertEquals(SessionId("sess_abc123def456"), params.sessionId)
        assertEquals(SessionConfigId("mode"), params.configId)
        val stringValue = assertIs<SessionConfigOptionValue.StringValue>(params.value)
        assertEquals("code", stringValue.value)
    }

    @Test
    fun `decode rfd session-set-config-option response example`() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": 2,
              "result": {
                "configOptions": [
                  {
                    "id": "mode",
                    "name": "Session Mode",
                    "type": "select",
                    "currentValue": "ask",
                    "options": [
                      {
                        "value": "ask",
                        "name": "Ask",
                        "description": "Request permission before making any changes"
                      },
                      {
                        "value": "code",
                        "name": "Code",
                        "description": "Write and modify code with full tool access"
                      }
                    ]
                  },
                  {
                    "id": "models",
                    "name": "Model",
                    "type": "select",
                    "currentValue": "ask",
                    "options": [
                      {
                        "value": "model-1",
                        "name": "Model 1",
                        "description": "The fastest model"
                      },
                      {
                        "value": "model-2",
                        "name": "Model 2",
                        "description": "The most powerful model"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(JsonRpcResponse.serializer(), json)
        val result = ACPJson.decodeFromJsonElement(
            SetSessionConfigOptionResponse.serializer(),
            response.result ?: JsonNull
        )

        assertEquals(2, result.configOptions.size)
        val first = assertIs<SessionConfigOption.Select>(result.configOptions[0])
        val options = assertIs<SessionConfigSelectOptions.Flat>(first.options)
        assertEquals(SessionConfigValueId("ask"), first.currentValue)
        assertEquals(2, options.options.size)
    }

    @Test
    fun `decode rfd config-option-update notification example`() {
        val json = """
            {
              "jsonrpc": "2.0",
              "method": "session/update",
              "params": {
                "sessionId": "sess_abc123def456",
                "update": {
                  "sessionUpdate": "config_option_update",
                  "configOptions": [
                    {
                      "id": "mode",
                      "name": "Session Mode",
                      "type": "select",
                      "currentValue": "ask",
                      "options": [
                        {
                          "value": "ask",
                          "name": "Ask",
                          "description": "Request permission before making any changes"
                        },
                        {
                          "value": "code",
                          "name": "Code",
                          "description": "Write and modify code with full tool access"
                        }
                      ]
                    },
                    {
                      "id": "models",
                      "name": "Model",
                      "type": "select",
                      "currentValue": "ask",
                      "options": [
                        {
                          "value": "model-1",
                          "name": "Model 1",
                          "description": "The fastest model"
                        },
                        {
                          "value": "model-2",
                          "name": "Model 2",
                          "description": "The most powerful model"
                        }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val notification = ACPJson.decodeFromString(JsonRpcNotification.serializer(), json)
        assertEquals("session/update", notification.method.name)

        val params = ACPJson.decodeFromJsonElement(
            SessionNotification.serializer(),
            notification.params ?: JsonNull
        )
        assertEquals(SessionId("sess_abc123def456"), params.sessionId)
        val update = assertIs<SessionUpdate.ConfigOptionUpdate>(params.update)
        assertEquals(2, update.configOptions.size)
        val first = assertIs<SessionConfigOption.Select>(update.configOptions[0])
        val options = assertIs<SessionConfigSelectOptions.Flat>(first.options)
        assertEquals(2, options.options.size)
    }

    @Test
    fun `decode boolean config option`() {
        val json = """
            {
              "id": "auto_approve",
              "name": "Auto Approve",
              "description": "Automatically approve all tool calls",
              "type": "boolean",
              "currentValue": true
            }
        """.trimIndent()

        val option = ACPJson.decodeFromString(SessionConfigOption.serializer(), json)
        val boolOption = assertIs<SessionConfigOption.BooleanOption>(option)
        assertEquals(SessionConfigId("auto_approve"), boolOption.id)
        assertEquals("Auto Approve", boolOption.name)
        assertEquals("Automatically approve all tool calls", boolOption.description)
        assertEquals(true, boolOption.currentValue)
    }

    @Test
    fun `decode boolean config option with false value`() {
        val json = """
            {
              "id": "verbose",
              "name": "Verbose",
              "type": "boolean",
              "currentValue": false
            }
        """.trimIndent()

        val option = ACPJson.decodeFromString(SessionConfigOption.serializer(), json)
        val boolOption = assertIs<SessionConfigOption.BooleanOption>(option)
        assertEquals(SessionConfigId("verbose"), boolOption.id)
        assertEquals(false, boolOption.currentValue)
        assertEquals(null, boolOption.description)
    }

    @Test
    fun `encode boolean config option roundtrip`() {
        val original = SessionConfigOption.BooleanOption(
            id = SessionConfigId("auto_approve"),
            name = "Auto Approve",
            description = "Automatically approve all tool calls",
            currentValue = true
        )

        val encoded = ACPJson.encodeToString(SessionConfigOption.serializer(), original)
        val decoded = ACPJson.decodeFromString(SessionConfigOption.serializer(), encoded)
        val boolOption = assertIs<SessionConfigOption.BooleanOption>(decoded)
        assertEquals(original.id, boolOption.id)
        assertEquals(original.name, boolOption.name)
        assertEquals(original.description, boolOption.description)
        assertEquals(original.currentValue, boolOption.currentValue)
    }

    @Test
    fun `decode session-new response with mixed select and boolean config options`() {
        val json = """
            {
              "sessionId": "sess_mixed",
              "configOptions": [
                {
                  "id": "mode",
                  "name": "Session Mode",
                  "type": "select",
                  "currentValue": "ask",
                  "options": [
                    {
                      "value": "ask",
                      "name": "Ask"
                    }
                  ]
                },
                {
                  "id": "auto_approve",
                  "name": "Auto Approve",
                  "type": "boolean",
                  "currentValue": true
                }
              ]
            }
        """.trimIndent()

        val response = ACPJson.decodeFromString(NewSessionResponse.serializer(), json)
        val configOptions = response.configOptions
        assertNotNull(configOptions)
        assertEquals(2, configOptions.size)
        assertIs<SessionConfigOption.Select>(configOptions[0])
        val boolOption = assertIs<SessionConfigOption.BooleanOption>(configOptions[1])
        assertEquals(true, boolOption.currentValue)
    }

    @Test
    fun `decode set config option request with boolean value`() {
        val json = """
            {
              "jsonrpc": "2.0",
              "id": 3,
              "method": "session/set_config_option",
              "params": {
                "sessionId": "sess_abc123def456",
                "configId": "auto_approve",
                "value": true
              }
            }
        """.trimIndent()

        val request = ACPJson.decodeFromString(JsonRpcRequest.serializer(), json)
        val params = ACPJson.decodeFromJsonElement(
            SetSessionConfigOptionRequest.serializer(),
            request.params ?: JsonNull
        )
        assertEquals(SessionId("sess_abc123def456"), params.sessionId)
        assertEquals(SessionConfigId("auto_approve"), params.configId)
        val boolValue = assertIs<SessionConfigOptionValue.BoolValue>(params.value)
        assertEquals(true, boolValue.value)
    }

    @Test
    fun `encode set config option request with boolean value roundtrip`() {
        val original = SetSessionConfigOptionRequest(
            sessionId = SessionId("sess_test"),
            configId = SessionConfigId("verbose"),
            value = SessionConfigOptionValue.BoolValue(false)
        )

        val encoded = ACPJson.encodeToString(SetSessionConfigOptionRequest.serializer(), original)
        val decoded = ACPJson.decodeFromString(SetSessionConfigOptionRequest.serializer(), encoded)
        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.configId, decoded.configId)
        val boolValue = assertIs<SessionConfigOptionValue.BoolValue>(decoded.value)
        assertEquals(false, boolValue.value)
    }

    @Test
    fun `encode set config option request with string value roundtrip`() {
        val original = SetSessionConfigOptionRequest(
            sessionId = SessionId("sess_test"),
            configId = SessionConfigId("mode"),
            value = SessionConfigOptionValue.StringValue("code")
        )

        val encoded = ACPJson.encodeToString(SetSessionConfigOptionRequest.serializer(), original)
        val decoded = ACPJson.decodeFromString(SetSessionConfigOptionRequest.serializer(), encoded)
        assertEquals(original.sessionId, decoded.sessionId)
        assertEquals(original.configId, decoded.configId)
        val stringValue = assertIs<SessionConfigOptionValue.StringValue>(decoded.value)
        assertEquals("code", stringValue.value)
    }

    // --- Edge case tests ---

    @Test
    fun `quoted string true is deserialized as StringValue not BoolValue`() {
        val json = """{"sessionId":"s","configId":"c","value":"true"}"""
        val request = ACPJson.decodeFromString(SetSessionConfigOptionRequest.serializer(), json)
        val stringValue = assertIs<SessionConfigOptionValue.StringValue>(request.value)
        assertEquals("true", stringValue.value)
    }

    @Test
    fun `quoted string false is deserialized as StringValue not BoolValue`() {
        val json = """{"sessionId":"s","configId":"c","value":"false"}"""
        val request = ACPJson.decodeFromString(SetSessionConfigOptionRequest.serializer(), json)
        val stringValue = assertIs<SessionConfigOptionValue.StringValue>(request.value)
        assertEquals("false", stringValue.value)
    }

    @Test
    fun `numeric value in config option throws SerializationException`() {
        val json = """{"sessionId":"s","configId":"c","value":42}"""
        assertFailsWith<kotlinx.serialization.SerializationException> {
            ACPJson.decodeFromString(SetSessionConfigOptionRequest.serializer(), json)
        }
    }

    // --- Factory method and extension function tests ---

    @Test
    fun `SessionConfigOption boolean factory creates BooleanOption`() {
        val option = SessionConfigOption.boolean("verbose", "Verbose", true, "Enable verbose logging")
        assertIs<SessionConfigOption.BooleanOption>(option)
        assertEquals(SessionConfigId("verbose"), option.id)
        assertEquals("Verbose", option.name)
        assertEquals("Enable verbose logging", option.description)
        assertEquals(true, option.currentValue)
    }

    @Test
    fun `SessionConfigOption select factory creates Select`() {
        val options = SessionConfigSelectOptions.Flat(listOf(
            SessionConfigSelectOption(SessionConfigValueId("a"), "Option A")
        ))
        val option = SessionConfigOption.select("mode", "Mode", "a", options, "Pick a mode")
        assertIs<SessionConfigOption.Select>(option)
        assertEquals(SessionConfigId("mode"), option.id)
        assertEquals("Mode", option.name)
        assertEquals("Pick a mode", option.description)
        assertEquals(SessionConfigValueId("a"), option.currentValue)
    }

    @Test
    fun `SessionConfigOptionValue of String creates StringValue`() {
        val value = SessionConfigOptionValue.of("code")
        val stringValue = assertIs<SessionConfigOptionValue.StringValue>(value)
        assertEquals("code", stringValue.value)
    }

    @Test
    fun `SessionConfigOptionValue of Boolean creates BoolValue`() {
        val value = SessionConfigOptionValue.of(true)
        val boolValue = assertIs<SessionConfigOptionValue.BoolValue>(value)
        assertEquals(true, boolValue.value)
    }

}

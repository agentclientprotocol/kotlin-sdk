@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package com.agentclientprotocol.model

import com.agentclientprotocol.rpc.ACPJson
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcRequest
import com.agentclientprotocol.rpc.JsonRpcResponse
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(SessionConfigValueId("code"), params.value)
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
}

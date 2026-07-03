package com.xgglass.appcontract

import kotlin.test.Test
import kotlin.test.assertEquals

class AIApiSettingsTest {
    @Test
    fun `fields returns documented keys input types and defaults`() {
        // Arrange
        val defaultBaseUrl = "https://api.example.test/v1/"
        val defaultModel = "test-model"
        val defaultApiKey = "test-key"

        // Act
        val fields = AIApiSettings.fields(
            defaultBaseUrl = defaultBaseUrl,
            defaultModel = defaultModel,
            defaultApiKey = defaultApiKey,
        )

        // Assert
        assertEquals(3, fields.size)
        assertEquals(
            listOf(
                AIApiSettings.KEY_BASE_URL,
                AIApiSettings.KEY_MODEL,
                AIApiSettings.KEY_API_KEY,
            ),
            fields.map { it.key },
        )
        assertEquals(
            listOf(
                UserSettingInputType.URL,
                UserSettingInputType.TEXT,
                UserSettingInputType.PASSWORD,
            ),
            fields.map { it.inputType },
        )
        assertEquals(
            listOf(defaultBaseUrl, defaultModel, defaultApiKey),
            fields.map { it.defaultValue },
        )
    }

    @Test
    fun `readers return settings values or empty strings when absent`() {
        // Arrange
        val settings = mapOf(
            AIApiSettings.KEY_BASE_URL to "https://api.example.test/v1/",
            AIApiSettings.KEY_MODEL to "test-model",
            AIApiSettings.KEY_API_KEY to "test-key",
        )

        // Act / Assert
        assertEquals("https://api.example.test/v1/", AIApiSettings.baseUrl(settings))
        assertEquals("test-model", AIApiSettings.model(settings))
        assertEquals("test-key", AIApiSettings.apiKey(settings))
        assertEquals("", AIApiSettings.baseUrl(emptyMap()))
        assertEquals("", AIApiSettings.model(emptyMap()))
        assertEquals("", AIApiSettings.apiKey(emptyMap()))
    }
}

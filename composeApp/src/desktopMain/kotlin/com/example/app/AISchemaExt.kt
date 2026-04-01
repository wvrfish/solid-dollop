package com.example.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Serialises an [AISchema] to the JSON schema object expected by the
 * Gemini REST API (`"type": "STRING"` / `"OBJECT"` / etc.).
 */
fun AISchema.toGeminiJson(): JsonObject = when (this) {
    is AISchema.StringType -> buildJsonObject { put("type", "STRING") }
    is AISchema.IntType -> buildJsonObject { put("type", "INTEGER") }
    is AISchema.NumberType -> buildJsonObject { put("type", "NUMBER") }
    is AISchema.BoolType -> buildJsonObject { put("type", "BOOLEAN") }
    is AISchema.ObjectType -> buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            properties.forEach { (k, v) -> put(k, v.toGeminiJson()) }
        }
    }
    is AISchema.ArrayType -> buildJsonObject {
        put("type", "ARRAY")
        put("items", items.toGeminiJson())
    }
}

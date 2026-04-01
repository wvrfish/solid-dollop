package com.example.app

/**
 * Maps a raw model-argument value of type [T] to Kotlin primitives.
 * Each [GenerativeChatModel] implementation provides its own mapper that
 * unwraps the SDK-specific argument type (e.g. [JsonElement] on JVM/desktop,
 * or the equivalent on Android).
 */
interface PrimitiveParamMapper<T> {
    fun toInt(value: T): Int?
    fun toString(value: T): String?
}

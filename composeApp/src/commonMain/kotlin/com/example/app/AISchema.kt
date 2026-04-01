package com.example.app

/** Describes a parameter or response schema understood by [GenerativeChatModel] implementations. */
sealed interface AISchema {
    data object StringType : AISchema
    data object IntType : AISchema
    data object NumberType : AISchema
    data object BoolType : AISchema
    data class ObjectType(val properties: Map<String, AISchema>) : AISchema
    data class ArrayType(val items: AISchema) : AISchema
}

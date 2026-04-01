package com.example.app

interface GenerativeChatModel {

    fun configure(
        instruction: String?,
        history: List<String>,
//        snapshot: UserSnapshot,
        schema: AISchema?,
        tools: List<ModelTool<*>>
    ): Configured

    interface Configured {
        suspend fun sendMessage(prompt: String): String
    }
}

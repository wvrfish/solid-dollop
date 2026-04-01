package com.example.app

/** Represents a tool (function) that a [GenerativeChatModel] may invoke during inference. */
interface ModelTool<P> {
    val name: String
    val description: String
    val group: ParamGroup<P>

    /** Execute the tool, mapping raw model arguments via [mapper] to typed values. */
    fun <M> execute(mapper: PrimitiveParamMapper<M>, args: Map<String, M>): String
}

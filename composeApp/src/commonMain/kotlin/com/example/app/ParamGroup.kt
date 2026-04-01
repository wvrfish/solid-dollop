package com.example.app

/** A group of parameters that together describe the input of a [ModelTool]. */
interface ParamGroup<P> {
    val params: List<Param<*>>
}

/** A single named, schema-described parameter within a [ParamGroup]. */
interface Param<T> {
    val name: String
    fun schema(): AISchema?
}

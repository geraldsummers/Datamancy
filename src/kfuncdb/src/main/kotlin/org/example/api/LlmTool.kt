package org.example.api

/**
 * Documentation entry for a single parameter of an @LlmTool function.
 */
annotation class LlmToolParamDoc(
    val name: String,
    val description: String
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmTool(
    val name: String = "",
    /**
     * A concise, one-line summary suitable for listings.
     */
    val shortDescription: String,
    /**
     * A detailed, long-form human-readable description of what the tool does. Markdown is allowed.
     */
    val longDescription: String,
    /**
     * Parameters technical specification (e.g., JSON schema, signature notes, constraints).
     * LM agents rely on this to know how to call the function precisely.
     */
    val paramsSpec: String,
    /**
     * Optional per-parameter descriptions. Each entry maps a parameter name to its description.
     * Use param name exactly as declared in the Kotlin function.
     */
    val params: Array<LlmToolParamDoc> = []
)

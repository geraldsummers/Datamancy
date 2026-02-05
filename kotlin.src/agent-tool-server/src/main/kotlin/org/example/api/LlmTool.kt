package org.example.api


annotation class LlmToolParamDoc(
    val name: String,
    val description: String
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmTool(
    val name: String = "",
    
    val shortDescription: String,
    
    val longDescription: String,
    
    val paramsSpec: String,
    
    val params: Array<LlmToolParamDoc> = []
)

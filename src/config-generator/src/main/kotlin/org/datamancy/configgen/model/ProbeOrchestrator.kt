package org.datamancy.configgen.model

import kotlinx.serialization.Serializable

@Serializable
data class ProbeOrchestratorConfig(
    val baseUrlInternal: String = "http://probe-orchestrator:8089",
    val manifestServices: List<ProbeServiceConfig> = emptyList(),
    val proofDir: String = "/proofs",
    val llmModel: String = "hermes-2-pro-mistral-7b",
    val ocrModelEnvKey: String? = "OCR_MODEL"
)

@Serializable
data class ProbeServiceConfig(
    val name: String,
    val internalUrls: List<String>,
    val externalUrlTemplate: String? = "https://{name}.{domain}",
    val containerName: String,
    val profile: String = "applications",
    val critical: Boolean = true
)

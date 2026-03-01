package org.datamancy.pipeline.sinks

/**
 * Represents a vectorized document ready for storage in Qdrant.
 */
data class VectorDocument(
    val id: String,
    val vector: FloatArray,
    val metadata: Map<String, Any> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorDocument
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

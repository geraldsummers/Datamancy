package org.datamancy.pipeline.core

/**
 * A sink writes data of type T to an external system.
 * Sinks handle persistence and should be idempotent.
 */
interface Sink<T> {
    /**
     * Write a single item to this sink
     */
    suspend fun write(item: T)

    /**
     * Optional: Write multiple items in a batch for efficiency
     */
    suspend fun writeBatch(items: List<T>) {
        items.forEach { write(it) }
    }

    /**
     * Name of this sink for logging/metrics
     */
    val name: String

    /**
     * Check if this sink is healthy/reachable
     */
    suspend fun healthCheck(): Boolean
}

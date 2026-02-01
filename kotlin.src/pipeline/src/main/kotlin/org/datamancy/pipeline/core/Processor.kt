package org.datamancy.pipeline.core

/**
 * A processor transforms data from type In to type Out.
 * Processors are pure functions with no side effects.
 */
interface Processor<In, Out> {
    /**
     * Process a single item
     */
    suspend fun process(input: In): Out

    /**
     * Name of this processor for logging/metrics
     */
    val name: String
}

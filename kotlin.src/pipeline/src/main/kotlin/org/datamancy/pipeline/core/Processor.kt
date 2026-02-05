package org.datamancy.pipeline.core

/**
 * Interface for data transformation stages in the pipeline.
 *
 * Processors transform data between Source and Sink, handling operations like:
 * - Chunking large documents for embedding model token limits (8192 tokens for BGE-M3)
 * - Deduplication using content hashing
 * - Text extraction and cleaning
 * - Embedding generation via Embedding Service (HTTP:8000)
 *
 * Integration points:
 * - Pipeline: Multiple processors can be chained together for complex transformations
 * - Embedding Service: Embedder processor calls POST /embed with text input
 * - DeduplicationStore: Deduplicator processor checks PostgreSQL for existing content hashes
 *
 * Design rationale:
 * - Processors are stateless and composable, enabling flexible pipeline configurations
 * - Generic In/Out types allow type-safe chaining (e.g., String -> EmbeddedDocument -> StagedDocument)
 * - Suspend function supports async operations like HTTP calls to embedding service
 */
interface Processor<In, Out> {
    /**
     * Processes a single input item and returns the transformed output.
     *
     * @param input The input item to transform
     * @return The transformed output. May return null if the processor filters out the item.
     */
    suspend fun process(input: In): Out

    /**
     * Unique identifier for this processor, used for logging and pipeline visualization.
     */
    val name: String
}

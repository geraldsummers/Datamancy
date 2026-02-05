package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow

/**
 * Base interface for data ingestion sources in the Datamancy pipeline.
 *
 * Sources are the entry point of the pipeline's data flow. They fetch data from external systems
 * (RSS feeds, CVE databases, Wikipedia, etc.) and emit it as a flow for downstream processing.
 *
 * Integration points:
 * - Pipeline: Sources are composed with Processors and Sinks to form complete data flows
 * - StandardizedRunner: Wraps StandardizedSource implementations for scheduled execution
 * - DeduplicationStore: Source data is checked for duplicates before processing
 *
 * Design rationale:
 * - Flow-based API enables backpressure handling and memory-efficient streaming
 * - Suspend function allows sources to perform async I/O without blocking threads
 * - Generic type T allows sources to emit domain-specific data models
 */
interface Source<T> {
    /**
     * Fetches data from the external source as a Kotlin Flow.
     *
     * @return Flow of items fetched from the source. The flow should handle errors internally
     *         and emit only successfully fetched items.
     */
    suspend fun fetch(): Flow<T>

    /**
     * Unique identifier for this source, used for logging, metrics, and database tracking.
     */
    val name: String
}

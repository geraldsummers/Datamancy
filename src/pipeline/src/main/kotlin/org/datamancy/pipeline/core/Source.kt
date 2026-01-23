package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow

/**
 * A data source that produces items of type T.
 * Sources are responsible for fetching data from external systems.
 */
interface Source<T> {
    /**
     * Fetch items from this source as a Flow.
     * The Flow will emit items as they are fetched.
     */
    suspend fun fetch(): Flow<T>

    /**
     * Name of this source for logging/metrics
     */
    val name: String
}

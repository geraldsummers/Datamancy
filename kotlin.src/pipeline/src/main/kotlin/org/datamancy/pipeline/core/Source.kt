package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow


interface Source<T> {
    
    suspend fun fetch(): Flow<T>

    
    val name: String
}

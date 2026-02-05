package org.datamancy.pipeline.core


interface Sink<T> {
    
    suspend fun write(item: T)

    
    suspend fun writeBatch(items: List<T>) {
        items.forEach { write(it) }
    }

    
    val name: String

    
    suspend fun healthCheck(): Boolean
}

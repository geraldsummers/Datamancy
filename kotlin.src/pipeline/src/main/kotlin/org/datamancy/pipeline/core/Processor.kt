package org.datamancy.pipeline.core


interface Processor<In, Out> {
    
    suspend fun process(input: In): Out

    
    val name: String
}

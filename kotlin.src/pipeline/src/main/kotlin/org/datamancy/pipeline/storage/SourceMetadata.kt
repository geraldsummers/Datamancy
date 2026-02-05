package org.datamancy.pipeline.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Instant

private val logger = KotlinLogging.logger {}


data class SourceMetadata(
    val sourceName: String,
    val lastSuccessfulRun: String? = null,  
    val lastAttemptedRun: String? = null,
    val totalItemsProcessed: Long = 0,
    val totalItemsFailed: Long = 0,
    val consecutiveFailures: Int = 0,
    val sourceVersion: String = "1.0",
    val checkpointData: Map<String, String> = emptyMap()  
)


class SourceMetadataStore(
    private val storePath: String = System.getProperty("java.io.tmpdir") + "/datamancy/metadata"
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeDir = File(storePath)
    private val memoryCache = mutableMapOf<String, SourceMetadata>()  

    init {
        storeDir.mkdirs()
    }

    
    fun load(sourceName: String): SourceMetadata {
        
        memoryCache[sourceName]?.let { return it }

        
        val file = File(storeDir, "${sourceName}.json")
        return try {
            if (file.exists()) {
                val json = file.readText()
                val metadata = gson.fromJson(json, SourceMetadata::class.java)
                memoryCache[sourceName] = metadata  
                metadata
            } else {
                logger.info { "No metadata found for $sourceName, creating new" }
                val metadata = SourceMetadata(sourceName = sourceName)
                memoryCache[sourceName] = metadata
                metadata
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load metadata for $sourceName: ${e.message}" }
            val metadata = SourceMetadata(sourceName = sourceName)
            memoryCache[sourceName] = metadata
            metadata
        }
    }

    
    fun save(metadata: SourceMetadata) {
        
        memoryCache[metadata.sourceName] = metadata

        
        
        if (!storeDir.exists()) {
            storeDir.mkdirs()
        }

        val file = File(storeDir, "${metadata.sourceName}.json")
        try {
            val json = gson.toJson(metadata)
            file.writeText(json)
            logger.debug { "Saved metadata for ${metadata.sourceName}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save metadata for ${metadata.sourceName}: ${e.message}" }
            
            
        }
    }

    
    fun recordSuccess(
        sourceName: String,
        itemsProcessed: Long,
        itemsFailed: Long = 0,
        checkpointData: Map<String, String> = emptyMap()
    ) {
        val existing = load(sourceName)
        val updated = existing.copy(
            lastSuccessfulRun = Instant.now().toString(),
            lastAttemptedRun = Instant.now().toString(),
            totalItemsProcessed = existing.totalItemsProcessed + itemsProcessed,
            totalItemsFailed = existing.totalItemsFailed + itemsFailed,
            consecutiveFailures = 0,
            checkpointData = checkpointData
        )
        save(updated)
    }

    
    fun recordFailure(sourceName: String) {
        val existing = load(sourceName)
        val updated = existing.copy(
            lastAttemptedRun = Instant.now().toString(),
            consecutiveFailures = existing.consecutiveFailures + 1
        )
        save(updated)
    }

    
    fun listAll(): List<SourceMetadata> {
        return try {
            storeDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val json = file.readText()
                        gson.fromJson(json, SourceMetadata::class.java)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse ${file.name}: ${e.message}" }
                        null
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list metadata: ${e.message}" }
            emptyList()
        }
    }
}

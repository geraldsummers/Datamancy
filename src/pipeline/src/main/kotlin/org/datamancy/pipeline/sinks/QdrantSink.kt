package org.datamancy.pipeline.sinks

import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.ValueFactory.value
import org.datamancy.pipeline.core.Sink

private val logger = KotlinLogging.logger {}

/**
 * Writes vectors to Qdrant
 */
class QdrantSink(
    qdrantUrl: String,
    private val collectionName: String,
    private val vectorSize: Int = 1024  // bge-m3
) : Sink<VectorDocument> {
    override val name = "QdrantSink[$collectionName]"

    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://")
        .split(":").getOrNull(1)?.toIntOrNull() ?: 6334

    private val client = QdrantClient(
        QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build()
    )

    init {
        ensureCollection()
    }

    private fun ensureCollection() {
        try {
            // Try to create collection (will fail if exists, which is fine)
            logger.info { "Ensuring Qdrant collection exists: $collectionName" }
            try {
                client.createCollectionAsync(
                    collectionName,
                    VectorParams.newBuilder()
                        .setSize(vectorSize.toLong())
                        .setDistance(Distance.Cosine)
                        .build()
                ).get()
                logger.info { "Created collection: $collectionName" }
            } catch (e: Exception) {
                // Collection likely already exists, which is fine
                logger.debug { "Collection $collectionName already exists or creation skipped" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure collection exists: ${e.message}" }
        }
    }

    override suspend fun write(item: VectorDocument) {
        try {
            val point = PointStruct.newBuilder()
                .setId(id(item.id.hashCode().toLong()))
                .setVectors(vectors(item.vector.toList()))
                .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                .build()

            client.upsertAsync(collectionName, listOf(point)).get()
            logger.debug { "Wrote vector ${item.id} to $collectionName" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
            throw e
        }
    }

    override suspend fun writeBatch(items: List<VectorDocument>) {
        try {
            val points = items.map { item ->
                PointStruct.newBuilder()
                    .setId(id(item.id.hashCode().toLong()))
                    .setVectors(vectors(item.vector.toList()))
                    .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                    .build()
            }

            client.upsertAsync(collectionName, points).get()
            logger.info { "Wrote ${items.size} vectors to $collectionName" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write batch to Qdrant: ${e.message}" }
            throw e
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            client.listCollectionsAsync().get()
            true
        } catch (e: Exception) {
            logger.error(e) { "Qdrant health check failed: ${e.message}" }
            false
        }
    }
}

data class VectorDocument(
    val id: String,
    val vector: FloatArray,
    val metadata: Map<String, Any>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorDocument
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

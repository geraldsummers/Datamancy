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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}
private const val QDRANT_TIMEOUT_SECONDS = 30L

/**
 * Generate deterministic UUID from string (prevents collisions from hashCode())
 */
private fun String.toDeterministicUUID(): UUID {
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(this.toByteArray())
    // Use first 16 bytes of SHA-256 hash for UUID
    return UUID.nameUUIDFromBytes(hash.copyOf(16))
}

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
            // Use deterministic UUID instead of hashCode to prevent collisions
            val deterministicId = item.id.toDeterministicUUID()

            val point = PointStruct.newBuilder()
                .setId(id(deterministicId.mostSignificantBits xor deterministicId.leastSignificantBits))
                .setVectors(vectors(item.vector.toList()))
                .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                .build()

            // Add timeout to prevent hanging indefinitely
            client.upsertAsync(collectionName, listOf(point)).get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.debug { "Wrote vector ${item.id} to $collectionName" }
        } catch (e: TimeoutException) {
            logger.error { "Timeout writing to Qdrant after ${QDRANT_TIMEOUT_SECONDS}s: ${item.id}" }
            throw RuntimeException("Qdrant write timeout", e)
        } catch (e: Exception) {
            logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
            throw e
        }
    }

    override suspend fun writeBatch(items: List<VectorDocument>) {
        try {
            val points = items.map { item ->
                // Use deterministic UUID instead of hashCode to prevent collisions
                val deterministicId = item.id.toDeterministicUUID()

                PointStruct.newBuilder()
                    .setId(id(deterministicId.mostSignificantBits xor deterministicId.leastSignificantBits))
                    .setVectors(vectors(item.vector.toList()))
                    .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                    .build()
            }

            // Add timeout to prevent hanging indefinitely
            client.upsertAsync(collectionName, points).get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info { "Wrote ${items.size} vectors to $collectionName" }
        } catch (e: TimeoutException) {
            logger.error { "Timeout writing batch to Qdrant after ${QDRANT_TIMEOUT_SECONDS}s: ${items.size} items" }
            throw RuntimeException("Qdrant batch write timeout", e)
        } catch (e: Exception) {
            logger.error(e) { "Failed to write batch to Qdrant: ${e.message}" }
            throw e
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            client.listCollectionsAsync().get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (e: TimeoutException) {
            logger.error { "Qdrant health check timeout after ${QDRANT_TIMEOUT_SECONDS}s" }
            false
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

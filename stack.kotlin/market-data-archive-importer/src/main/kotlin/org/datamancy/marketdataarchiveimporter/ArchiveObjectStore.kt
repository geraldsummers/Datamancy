package org.datamancy.marketdataarchiveimporter

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.RequestPayer
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.Closeable
import java.io.InputStream
import java.net.URI

data class ArchiveObjectRef(
    val bucket: String,
    val key: String,
    val sizeBytes: Long
)

interface ArchiveObjectStore {
    fun listObjects(bucket: String, prefix: String): List<ArchiveObjectRef>
    fun open(objectRef: ArchiveObjectRef): InputStream
}

class S3ArchiveObjectStore(
    config: ArchiveImporterConfig = loadArchiveImporterConfig()
) : ArchiveObjectStore, Closeable {
    private val client: S3Client = run {
        val builder = S3Client.builder()
            .region(Region.of(config.archiveRegion))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
        config.archiveEndpoint?.let {
            builder.endpointOverride(URI.create(it))
            builder.serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build()
            )
        }
        builder.build()
    }

    override fun listObjects(bucket: String, prefix: String): List<ArchiveObjectRef> {
        val refs = mutableListOf<ArchiveObjectRef>()
        var continuationToken: String? = null
        do {
            val request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .requestPayer(RequestPayer.REQUESTER)
                .continuationToken(continuationToken)
                .build()
            val response = client.listObjectsV2(request)
            response.contents()
                .asSequence()
                .filterNot { it.key().endsWith("/") }
                .forEach { refs += ArchiveObjectRef(bucket = bucket, key = it.key(), sizeBytes = it.size()) }
            continuationToken = if (response.isTruncated) response.nextContinuationToken() else null
        } while (continuationToken != null)
        return refs
    }

    override fun open(objectRef: ArchiveObjectRef): ResponseInputStream<GetObjectResponse> {
        val request = GetObjectRequest.builder()
            .bucket(objectRef.bucket)
            .key(objectRef.key)
            .requestPayer(RequestPayer.REQUESTER)
            .build()
        return client.getObject(request)
    }

    override fun close() {
        client.close()
    }
}

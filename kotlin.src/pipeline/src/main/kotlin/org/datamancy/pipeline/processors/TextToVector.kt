package org.datamancy.pipeline.processors

import org.datamancy.pipeline.core.Processor
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.sources.RssArticle


class TextToVector(
    private val articleProvider: () -> RssArticle?
) : Processor<Pair<RssArticle, FloatArray>, VectorDocument> {
    override val name = "TextToVector"

    override suspend fun process(input: Pair<RssArticle, FloatArray>): VectorDocument {
        val (article, embedding) = input

        return VectorDocument(
            id = article.guid,
            vector = embedding,
            metadata = mapOf(
                "title" to article.title,
                "link" to article.link,
                "description" to article.description.take(200),
                "publishedDate" to article.publishedDate,
                "author" to article.author,
                "feedTitle" to article.feedTitle,
                "feedUrl" to article.feedUrl,
                "categories" to article.categories.joinToString(","),
                "source" to "rss"
            )
        )
    }
}

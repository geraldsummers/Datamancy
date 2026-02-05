package org.datamancy.pipeline.processors

import org.datamancy.pipeline.core.Processor
import org.datamancy.pipeline.sources.RssArticle


class RssToText : Processor<RssArticle, String> {
    override val name = "RssToText"

    override suspend fun process(input: RssArticle): String {
        return input.toText()
    }
}

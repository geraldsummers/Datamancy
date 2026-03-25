package org.datamancy.pipeline.materializer

import org.datamancy.pipeline.runtime.FeatureMaterializerRunner

fun main() {
    val runner = FeatureMaterializerRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

package org.datamancy.pipeline.persist

import org.datamancy.pipeline.runtime.MarketDataPersistRunner

fun main() {
    val runner = MarketDataPersistRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

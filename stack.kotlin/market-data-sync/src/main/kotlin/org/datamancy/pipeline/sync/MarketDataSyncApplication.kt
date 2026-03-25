package org.datamancy.pipeline.sync

import org.datamancy.pipeline.runtime.MarketDataSyncRunner

fun main() {
    val runner = MarketDataSyncRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

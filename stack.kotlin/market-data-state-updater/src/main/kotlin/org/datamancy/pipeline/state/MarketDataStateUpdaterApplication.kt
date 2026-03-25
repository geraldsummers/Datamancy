package org.datamancy.pipeline.state

import org.datamancy.pipeline.runtime.MarketDataStateUpdaterRunner

fun main() {
    val runner = MarketDataStateUpdaterRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

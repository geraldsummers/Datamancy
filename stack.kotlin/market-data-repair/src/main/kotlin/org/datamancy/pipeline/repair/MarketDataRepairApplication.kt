package org.datamancy.pipeline.repair

import org.datamancy.pipeline.runtime.MarketDataRepairRunner

fun main() {
    val runner = MarketDataRepairRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

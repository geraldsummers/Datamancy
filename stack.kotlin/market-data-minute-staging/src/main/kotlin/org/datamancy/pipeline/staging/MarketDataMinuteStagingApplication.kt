package org.datamancy.pipeline.staging

import org.datamancy.pipeline.runtime.MinuteStagingRunner

fun main() {
    val runner = MinuteStagingRunner()
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    runner.start()
    Thread.currentThread().join()
}

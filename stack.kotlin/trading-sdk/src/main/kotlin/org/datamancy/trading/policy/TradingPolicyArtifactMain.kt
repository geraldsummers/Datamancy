package org.datamancy.trading.policy

import java.io.File

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Usage: TradingPolicyArtifactMainKt <output-file>" }
    val output = File(args.first())
    ActiveTradingPolicy.write(DatamancyTradingPolicy.default(), output)
    println("Wrote trading policy artifact to ${output.absolutePath}")
}

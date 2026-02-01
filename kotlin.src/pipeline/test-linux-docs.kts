#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import java.io.File

println("🐧 Testing Linux Docs Source...")
println("Checking /usr/share/man...")

val manDir = File("/usr/share/man/man1")
if (manDir.exists()) {
    println("✅ Found man directory: ${manDir.absolutePath}")
    val files = manDir.listFiles()?.take(5)
    println("📚 First 5 man pages:")
    files?.forEach { println("   - ${it.name}") }
    println("\n✅ Linux Docs source can read man pages!")
} else {
    println("❌ /usr/share/man not found")
}

package com.example.clashforlsposed.proxy

import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val output: String) {
    val succeeded: Boolean get() = exitCode == 0
}

object RootShell {
    fun isAvailable(): Boolean {
        val result = run("id")
        return result.succeeded && result.output.contains("uid=0")
    }

    fun run(command: String, timeoutMs: Long = 8_000): ShellResult {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_OUTPUT_CHARS) output.appendLine(line)
                        }
                    }
                }
            }.apply {
                name = "root-shell-output"
                isDaemon = true
                start()
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                reader.join(500)
                ShellResult(-1, "root 命令超时${output.toString().trim().takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()}")
            } else {
                reader.join(500)
                ShellResult(process.exitValue(), output.toString().trim())
            }
        }.getOrElse { ShellResult(-1, it.message ?: "无法执行 root 命令") }
    }

    private const val MAX_OUTPUT_CHARS = 32_768
}

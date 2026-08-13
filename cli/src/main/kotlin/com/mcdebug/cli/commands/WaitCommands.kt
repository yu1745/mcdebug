package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class WaitUntilCmd : CliktCommand(
    name = "until",
    help = "passively wait for a predicate to become true (evaluated on natural server ticks)",
) {
    private val predicate by option("--predicate", help = "predicate DSL, e.g. 'inv slot 1 has 2x minecraft:coal'").required()
    private val timeoutTicks by option("--timeout-ticks", help = "timeout in ticks; 0 (default) = wait FOREVER, never times out").int()
    private val pollIntervalTicks by option("--poll-interval-ticks").int()

    override fun run() = withApi { api ->
        printJson(api.wait.until(predicate, timeoutTicks, pollIntervalTicks))
    }
}

class WaitCommands : CliktCommand(name = "wait", help = "passive wait for server-side conditions") {
    override fun run() = Unit
}

fun waitSubcommands() = listOf(WaitUntilCmd())

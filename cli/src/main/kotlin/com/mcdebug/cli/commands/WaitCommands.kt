package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

class WaitUntilCmd : CliktCommand(
    name = "until",
    help = "passively wait for a predicate to become true (evaluated on natural server ticks)",
) {
    private val predicate by option("--predicate", help = "predicate DSL, e.g. 'inv[0,64,0].1.count >= 2' or 'be[0,64,0].energy > 0 AND tick > 100'").required()
    private val timeoutTicks by option("--timeout-ticks", help = "timeout in ticks; default 1200 (1 min @20tps); pass 0 explicitly to wait FOREVER").int()
    private val pollIntervalTicks by option("--poll-interval-ticks").int()
    private val sprint by option("--sprint", help = "fast-forward while waiting: request a vanilla /tick sprint budget (timeout + slack) so the server runs at full speed; the sprint stops on match, timeout or disconnect (requires a finite --timeout-ticks)").flag(default = false)

    override fun run() = withApi { api ->
        printJson(api.wait.until(predicate, timeoutTicks, pollIntervalTicks, sprint))
    }
}

class WaitCommands : CliktCommand(name = "wait", help = "passive wait for server-side conditions") {
    override fun run() = Unit
}

fun waitSubcommands() = listOf(WaitUntilCmd())

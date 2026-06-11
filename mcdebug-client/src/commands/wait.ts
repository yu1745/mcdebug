import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { WAIT_UNTIL_HELP } from './help-text.js';
import { outputJson } from './util.js';

/**
 * `mcdebug wait-until --expr <expr>` — passively wait for a predicate to become true.
 *
 * The server registers a ServerTickEvents.END_SERVER_TICK callback that evaluates
 * the predicate on each natural server tick. We do NOT call any tick-advance method.
 *
 * Predicate grammar (single-line, quoted on the shell):
 *   be[x,y,z].<jsonPointer>   <op>  <value>
 *   inv[x,y,z].<slotField>    <op>  <value>
 *   block[x,y,z].<id|prop.k>  <op>  <value>
 *   tick                       <op>  <value>
 *
 *   <op>   := == | != | < | <= | > | >=
 *   <value>:= number | "string" | true | false | null
 */
export function registerWaitCommand(cmd: Command, getApi: () => DebugApi): void {
  cmd
    .command('wait-until')
    .description('passively wait until the predicate becomes true; DOES NOT drive ticks')
    .addHelpText('after', WAIT_UNTIL_HELP)
    .requiredOption('--expr <s>', 'predicate string (see grammar in the help text)')
    .option('--timeout <ticks>', 'timeout in server ticks (default 0 = no timeout)', '0')
    .option('--poll <ticks>', 'evaluate every N ticks (default 1)', '1')
    .action(async (opts: { expr: string; timeout: string; poll: string }) => {
      const api = getApi();
      const timeout = Number(opts.timeout);
      const poll = Number(opts.poll);
      if (!Number.isInteger(timeout) || timeout < 0) throw new Error('--timeout must be a non-negative integer');
      if (!Number.isInteger(poll) || poll < 1) throw new Error('--poll must be a positive integer');
      const r = await api.wait.until(opts.expr, { timeoutTicks: timeout, pollIntervalTicks: poll });
      outputJson(r);
      await api.close();
    });
}

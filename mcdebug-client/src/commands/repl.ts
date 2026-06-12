import * as readline from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { DebugApi } from '../api.js';
import { handleError } from './util.js';

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor as new (
  ...args: string[]
) => (dbg: DebugApi) => Promise<unknown>;

/**
 * Start an interactive REPL exposing the DebugApi as `dbg`.
 *
 * Example session:
 *   > await dbg.server.status()
 *   > await dbg.world.setBlock([0,64,0], 'minecraft:stone')
 *   > await dbg.wait.until('block[0,64,0].id == "minecraft:water"', { timeoutTicks: 24000 })
 */
export async function startRepl(api: DebugApi): Promise<void> {
  const rl = readline.createInterface({ input: stdin, output: stdout, terminal: true });
  console.log('mcdebug REPL — `dbg` is the DebugApi instance. Type `.exit` to quit.');
  while (true) {
    let line: string;
    try {
      line = await rl.question('mcdebug> ');
    } catch {
      break;
    }
    const trimmed = line.trim();
    if (!trimmed) continue;
    if (trimmed === '.exit' || trimmed === ':exit' || trimmed === 'exit') break;
    try {
      // The expression has access to `dbg`. Await its result, print JSON.
      const evaluate = new AsyncFunction('dbg', `return (${trimmed});`);
      const result = await evaluate(api);
      if (result !== undefined) console.log(JSON.stringify(result, null, 2));
    } catch (e) {
      handleError(e);
    }
  }
  await rl.close();
  await api.close();
}

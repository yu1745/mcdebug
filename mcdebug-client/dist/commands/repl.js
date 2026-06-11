import * as readline from 'node:readline/promises';
import { stdin, stdout } from 'node:process';
import { handleError } from './util.js';
/**
 * Start an interactive REPL exposing the DebugApi as `dbg`.
 *
 * Example session:
 *   > await dbg.server.status()
 *   > await dbg.world.setBlock([0,64,0], 'minecraft:stone')
 *   > await dbg.wait.until('block[0,64,0].id == "minecraft:water"', { timeoutTicks: 24000 })
 */
export async function startRepl(api) {
    const rl = readline.createInterface({ input: stdin, output: stdout, terminal: true });
    console.log('mcdebug REPL — `dbg` is the DebugApi instance. Type `.exit` to quit.');
    while (true) {
        let line;
        try {
            line = await rl.question('mcdebug> ');
        }
        catch {
            break;
        }
        const trimmed = line.trim();
        if (!trimmed)
            continue;
        if (trimmed === '.exit' || trimmed === ':exit' || trimmed === 'exit')
            break;
        try {
            // The expression has access to `dbg`. Await its result, print JSON.
            const result = await (0, eval)(`(async () => { return (${trimmed}); })()`);
            if (result !== undefined)
                console.log(JSON.stringify(result, null, 2));
        }
        catch (e) {
            handleError(e);
        }
    }
    await rl.close();
    await api.close();
}

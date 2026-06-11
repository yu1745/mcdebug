import { DebugApi } from '../api.js';
/**
 * Start an interactive REPL exposing the DebugApi as `dbg`.
 *
 * Example session:
 *   > await dbg.server.status()
 *   > await dbg.world.setBlock([0,64,0], 'minecraft:stone')
 *   > await dbg.wait.until('block[0,64,0].id == "minecraft:water"', { timeoutTicks: 24000 })
 */
export declare function startRepl(api: DebugApi): Promise<void>;

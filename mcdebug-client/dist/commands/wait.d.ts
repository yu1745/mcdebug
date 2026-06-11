import type { Command } from 'commander';
import { DebugApi } from '../api.js';
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
export declare function registerWaitCommand(cmd: Command, getApi: () => DebugApi): void;

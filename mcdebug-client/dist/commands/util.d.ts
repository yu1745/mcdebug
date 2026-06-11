import { Pos } from '../types.js';
/**
 * Parse a "x,y,z" triplet string into a Pos. Used by --from/--to/--box flags.
 */
export declare function parseTriplet(spec: string, what: string): Pos;
/**
 * Helper: require --x --y --z in the opts object. Returns a Pos.
 * Throws a clear error if any are missing or non-integer.
 */
export declare function requirePos(opts: {
    x?: string;
    y?: string;
    z?: string;
}, what: string): Pos;
export declare function parseStateProps(entries: string[]): Record<string, string>;
/** Parse JSON literal or "@file" reference. */
export declare function parseJsonArg(s: string): Promise<unknown>;
export declare function outputJson(v: unknown): void;
export declare function outputOneLineJson(v: unknown): void;
export declare function handleError(e: unknown): never;

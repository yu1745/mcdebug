import { RpcError } from '../types.js';
/**
 * Parse a "x,y,z" triplet string into a Pos. Used by --from/--to/--box flags.
 */
export function parseTriplet(spec, what) {
    const parts = spec.split(',').map((s) => s.trim());
    if (parts.length !== 3) {
        throw new Error(`${what} must be "x,y,z" with 3 integers, got: ${spec}`);
    }
    const out = parts.map((s, i) => {
        const n = Number(s);
        if (!Number.isInteger(n)) {
            throw new Error(`${what}[${i}] is not an integer: ${s}`);
        }
        return n;
    });
    return out;
}
/**
 * Helper: require --x --y --z in the opts object. Returns a Pos.
 * Throws a clear error if any are missing or non-integer.
 */
export function requirePos(opts, what) {
    const { x, y, z } = opts;
    if (x === undefined || y === undefined || z === undefined) {
        throw new Error(`${what} requires --x, --y, --z (all integers)`);
    }
    const out = [x, y, z].map((s, i) => {
        const n = Number(s);
        if (!Number.isInteger(n)) {
            throw new Error(`${what} axis ${['x', 'y', 'z'][i]} is not an integer: ${s}`);
        }
        return n;
    });
    return out;
}
export function parseStateProps(entries) {
    const out = {};
    for (const e of entries) {
        const idx = e.indexOf('=');
        if (idx < 0)
            throw new Error(`state entry must be k=v, got: ${e}`);
        out[e.slice(0, idx)] = e.slice(idx + 1);
    }
    return out;
}
/** Parse JSON literal or "@file" reference. */
export async function parseJsonArg(s) {
    if (s.startsWith('@')) {
        const fs = await import('node:fs/promises');
        const txt = await fs.readFile(s.slice(1), 'utf8');
        return JSON.parse(txt);
    }
    return JSON.parse(s);
}
export function outputJson(v) {
    console.log(JSON.stringify(v, null, 2));
}
export function outputOneLineJson(v) {
    console.log(JSON.stringify(v));
}
export function handleError(e) {
    if (e instanceof RpcError) {
        const dataStr = e.data !== undefined ? ` data=${JSON.stringify(e.data)}` : '';
        console.error(`error [${e.code}] ${e.message}${dataStr}`);
        process.exit(e.code >= -32000 && e.code <= -32099 ? 2 : 1);
    }
    if (e instanceof Error) {
        console.error(`error: ${e.message}`);
        process.exit(1);
    }
    console.error(e);
    process.exit(1);
}

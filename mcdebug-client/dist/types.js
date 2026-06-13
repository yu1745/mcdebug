// Wire-level and domain types for the mcdebug protocol.
export function pos(xOrValue, y, z) {
    const base = Array.isArray(xOrValue) ? xOrValue : [xOrValue, y, z];
    if (base[1] === undefined || base[2] === undefined) {
        throw new Error('pos requires x, y, z coordinates');
    }
    const value = [base[0], base[1], base[2]];
    return Object.assign(value, {
        offset: (dx = 0, dy = 0, dz = 0) => pos(value[0] + dx, value[1] + dy, value[2] + dz),
        east: (blocks = 1) => pos(value[0] + blocks, value[1], value[2]),
        west: (blocks = 1) => pos(value[0] - blocks, value[1], value[2]),
        up: (blocks = 1) => pos(value[0], value[1] + blocks, value[2]),
        down: (blocks = 1) => pos(value[0], value[1] - blocks, value[2]),
        south: (blocks = 1) => pos(value[0], value[1], value[2] + blocks),
        north: (blocks = 1) => pos(value[0], value[1], value[2] - blocks),
    });
}
/**
 * Transport-layer RPC error. Mirrors the server's RpcError fields.
 */
export class RpcError extends Error {
    code;
    data;
    constructor(code, message, data) {
        super(message);
        this.code = code;
        this.data = data;
        this.name = 'RpcError';
    }
}

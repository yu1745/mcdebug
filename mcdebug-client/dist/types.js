// Wire-level and domain types for the mcdebug protocol.
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

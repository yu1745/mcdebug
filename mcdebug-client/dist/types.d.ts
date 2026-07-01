export type Pos = readonly [number, number, number];
export type Side = 'up' | 'down' | 'north' | 'south' | 'east' | 'west' | null;
export interface PosHelpers {
    offset(dx?: number, dy?: number, dz?: number): RichPos;
    east(blocks?: number): RichPos;
    west(blocks?: number): RichPos;
    up(blocks?: number): RichPos;
    down(blocks?: number): RichPos;
    south(blocks?: number): RichPos;
    north(blocks?: number): RichPos;
}
export type RichPos = Pos & PosHelpers;
export declare function pos(x: number, y: number, z: number): RichPos;
export declare function pos(value: Pos): RichPos;
export interface Box {
    from: Pos;
    to: Pos;
}
export interface BlockStateSpec {
    /** e.g. "minecraft:furnace" */
    name: string;
    /** state property key/value pairs */
    props?: Record<string, string>;
}
export type JsonNbt = string | number | boolean | null | {
    [k: string]: JsonNbt;
} | JsonNbt[];
export interface ItemStackJson {
    item: string | null;
    count: number;
    nbt?: JsonNbt | null;
}
export type Target = {
    kind: 'block';
    pos: Pos;
    dim?: string;
} | {
    kind: 'entity';
    uuid: string;
    dim?: string;
} | {
    kind: 'item';
    stack: ItemStackJson;
};
export type StorageResource = {
    kind: 'item';
    item: string;
    nbt?: JsonNbt | null;
} | {
    kind: 'fluid';
    fluid: string;
    nbt?: JsonNbt | null;
} | {
    kind: 'energy';
};
export type StorageHandle = {
    handle: string;
    kind: 'item';
    slots: number;
} | {
    handle: string;
    kind: 'fluid';
    tanks: number;
} | {
    handle: string;
    kind: 'energy';
    amount: number;
    capacity: number;
};
export interface StorageListResult {
    handles: StorageHandle[];
}
export type StorageGetResult = {
    handle: string;
    kind: 'item';
    supportsInsertion: boolean;
    supportsExtraction: boolean;
    slots: Array<{
        index: number;
        stack: ItemStackJson;
        amount?: number;
        capacity: number;
    }>;
} | {
    handle: string;
    kind: 'fluid';
    supportsInsertion: boolean;
    supportsExtraction: boolean;
    tanks: Array<{
        index: number;
        fluid: string | null;
        nbt?: JsonNbt | null;
        amount: number;
        capacity: number;
    }>;
} | {
    handle: string;
    kind: 'energy';
    supportsInsertion: boolean;
    supportsExtraction: boolean;
    amount: number;
    capacity: number;
};
export interface StorageMoveResult {
    handle?: string;
    kind: 'item' | 'fluid' | 'energy';
    requested: number;
    inserted?: number;
    extracted?: number;
    transferred?: number;
    remaining: number;
    simulated: boolean;
    targetAfter?: Target;
    fromAfter?: Target;
    toAfter?: Target;
}
export type SnapshotKind = 'block' | 'blockEntityNbt' | 'inventory' | 'fluid' | 'energy' | 'entity';
export interface SnapshotCaptureOptions {
    box: Box;
    dim?: string;
    include?: SnapshotKind[];
}
export interface SnapshotDiffResult {
    equal: boolean;
    changeCount: number;
    changes: Array<{
        path: string;
        before: JsonNbt;
        after: JsonNbt;
    }>;
}
export interface TraceStartOptions extends SnapshotCaptureOptions {
    intervalTicks?: number;
}
export interface TraceStartResult {
    traceId: string;
    startedTick: number;
    intervalTicks: number;
    frames: number;
}
export interface TraceResult {
    traceId: string;
    active: boolean;
    dim: string;
    startedTick: number;
    intervalTicks: number;
    include: SnapshotKind[];
    frames: Array<{
        tick: number;
        snapshot: JsonNbt;
    }>;
}
export interface ScreenSnapshot {
    screenId: string;
    title: string;
    handlerType: string | null;
    syncId: number;
    dim: string;
    pos: Pos;
    slots: ItemStackJson[];
    slotDetails: Array<{
        index: number;
        id: number;
        x: number;
        y: number;
        canTake: boolean;
        canInsert: boolean;
        stack: ItemStackJson;
    }>;
    cursor: ItemStackJson;
    properties: number[];
}
export interface BlockSnapshot {
    pos: Pos;
    dim: string;
    state: {
        name: string;
        props: Record<string, string>;
    };
    hasBlockEntity: boolean;
    nbt?: JsonNbt;
}
export interface WorldBoxEditResult {
    count: number;
    changed: number;
    dim: string;
}
export type Direction = 'up' | 'down' | 'north' | 'south' | 'east' | 'west';
export interface RedstonePowerSnapshot {
    pos: Pos;
    dim: string;
    state: {
        name: string;
        props: Record<string, string>;
    };
    powered: boolean;
    received: number;
    inputs: Record<Direction, number>;
    outputs: Record<Direction, number>;
    side?: Direction;
    sideInput?: number;
    sideOutput?: number;
}
export interface EntitySnapshot {
    type: string;
    uuid: string;
    dim: string;
    x: number;
    y: number;
    z: number;
    yaw: number;
    pitch: number;
    removed: boolean;
    health?: number;
    maxHealth?: number;
    stack?: ItemStackJson;
    nbt?: JsonNbt;
}
export interface EntitySpawnOptions {
    dim?: string;
    yaw?: number;
    pitch?: number;
    nbt?: JsonNbt;
    stack?: ItemStackJson;
    includeNbt?: boolean;
}
export interface FixtureBlock {
    rel: Pos;
    state: {
        name: string;
        props: Record<string, string>;
    };
    nbt?: JsonNbt;
}
export interface FixtureJson {
    version: number;
    dim?: string;
    origin: Pos;
    size: Pos;
    blocks: FixtureBlock[];
}
export interface ServerStatus {
    mcVersion: string;
    modVersion: string;
    modLoader: 'fabric';
    protocolVersion: number;
    dims: string[];
    players: number;
    dayTime: number;
    tick: number;
}
export interface WaitResult {
    matched: boolean;
    ranTicks: number;
    value?: JsonNbt;
}
/**
 * Transport-layer RPC error. Mirrors the server's RpcError fields.
 */
export declare class RpcError extends Error {
    readonly code: number;
    readonly data?: unknown | undefined;
    constructor(code: number, message: string, data?: unknown | undefined);
}

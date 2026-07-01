// Wire-level and domain types for the mcdebug protocol.

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

export function pos(x: number, y: number, z: number): RichPos;
export function pos(value: Pos): RichPos;
export function pos(xOrValue: number | Pos, y?: number, z?: number): RichPos {
  const base = Array.isArray(xOrValue) ? xOrValue : ([xOrValue, y, z] as Pos);
  if (base[1] === undefined || base[2] === undefined) {
    throw new Error('pos requires x, y, z coordinates');
  }
  const value = [base[0], base[1], base[2]] as unknown as RichPos;
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

export type JsonNbt =
  | string
  | number
  | boolean
  | null
  | { [k: string]: JsonNbt }
  | JsonNbt[];

export interface ItemStackJson {
  item: string | null;
  count: number;
  nbt?: JsonNbt | null;
}

export type Target =
  | { kind: 'block'; pos: Pos; dim?: string }
  | { kind: 'entity'; uuid: string; dim?: string }
  | { kind: 'item'; stack: ItemStackJson };

export type StorageResource =
  | { kind: 'item'; item: string; nbt?: JsonNbt | null }
  | { kind: 'fluid'; fluid: string; nbt?: JsonNbt | null }
  | { kind: 'energy' };

export type StorageHandle =
  | { handle: string; kind: 'item'; slots: number }
  | { handle: string; kind: 'fluid'; tanks: number }
  | { handle: string; kind: 'energy'; amount: number; capacity: number };

export interface StorageListResult {
  handles: StorageHandle[];
}

export type StorageGetResult =
  | {
      handle: string;
      kind: 'item';
      supportsInsertion: boolean;
      supportsExtraction: boolean;
      slots: Array<{ index: number; stack: ItemStackJson; amount?: number; capacity: number }>;
    }
  | {
      handle: string;
      kind: 'fluid';
      supportsInsertion: boolean;
      supportsExtraction: boolean;
      tanks: Array<{ index: number; fluid: string | null; nbt?: JsonNbt | null; amount: number; capacity: number }>;
    }
  | {
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
  changes: Array<{ path: string; before: JsonNbt; after: JsonNbt }>;
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
  frames: Array<{ tick: number; snapshot: JsonNbt }>;
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
  state: { name: string; props: Record<string, string> };
  hasBlockEntity: boolean;
  nbt?: JsonNbt;
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
export class RpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly data?: unknown,
  ) {
    super(message);
    this.name = 'RpcError';
  }
}

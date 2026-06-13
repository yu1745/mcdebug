// Wire-level and domain types for the mcdebug protocol.

export type Pos = readonly [number, number, number];

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

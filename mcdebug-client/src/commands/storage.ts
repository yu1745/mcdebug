import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import type { JsonNbt, Side, StorageResource, Target } from '../types.js';
import { outputJson, outputOneLineJson, parseJsonArg, parseTriplet, requirePos } from './util.js';

type TargetOpts = { target?: string; x?: string; y?: string; z?: string; dim?: string };
type ResourceOpts = { resource?: string; item?: string; fluid?: string; energy?: boolean; nbt?: string };

export function registerStorageCommands(parent: Command, getApi: () => DebugApi): void {
  const storage = parent.command('storage').description('generic item/fluid/energy storage operations');

  storage
    .command('list')
    .description('list generic storage handles at a target')
    .option('--target <json>', 'Target JSON or @file')
    .option('--x <n>', 'block x coordinate')
    .option('--y <n>', 'block y coordinate')
    .option('--z <n>', 'block z coordinate')
    .option('--dim <id>', 'dimension id')
    .option('--side <side>', 'side: up|down|north|south|east|west|null')
    .action(async (opts: TargetOpts & { side?: string }) => {
      const api = getApi();
      const r = await api.storage.list(await parseTarget(opts, 'storage list'), { side: normalizeSide(opts.side) });
      outputJson(r);
      await api.close();
    });

  storage
    .command('get')
    .description('read one generic storage handle')
    .requiredOption('--handle <id>', 'handle, e.g. fabric:item, fabric:fluid, teamreborn:energy')
    .option('--target <json>', 'Target JSON or @file')
    .option('--x <n>', 'block x coordinate')
    .option('--y <n>', 'block y coordinate')
    .option('--z <n>', 'block z coordinate')
    .option('--dim <id>', 'dimension id')
    .option('--side <side>', 'side: up|down|north|south|east|west|null')
    .action(async (opts: TargetOpts & { handle: string; side?: string }) => {
      const api = getApi();
      const r = await api.storage.get(await parseTarget(opts, 'storage get'), opts.handle, { side: normalizeSide(opts.side) });
      outputJson(r);
      await api.close();
    });

  storage
    .command('insert')
    .description('insert item/fluid/energy into a target')
    .option('--handle <id>', 'handle; defaults from resource kind')
    .requiredOption('--amount <n>', 'amount to insert')
    .option('--target <json>', 'Target JSON or @file')
    .option('--x <n>', 'block x coordinate')
    .option('--y <n>', 'block y coordinate')
    .option('--z <n>', 'block z coordinate')
    .option('--dim <id>', 'dimension id')
    .option('--side <side>', 'side: up|down|north|south|east|west|null')
    .option('--resource <json>', 'StorageResource JSON or @file')
    .option('--item <id>', 'item resource id')
    .option('--fluid <id>', 'fluid resource id')
    .option('--energy', 'energy resource')
    .option('--nbt <json>', 'resource NBT JSON or @file')
    .option('--simulate', 'do not commit')
    .action(async (opts: TargetOpts & ResourceOpts & { handle?: string; amount: string; side?: string; simulate?: boolean }) => {
      const api = getApi();
      const resource = await parseResource(opts);
      const amount = parseAmount(opts.amount);
      const r = await api.storage.insert(
        await parseTarget(opts, 'storage insert'),
        opts.handle ?? defaultHandle(resource),
        resource,
        amount,
        { side: normalizeSide(opts.side), simulate: opts.simulate },
      );
      outputOneLineJson(r);
      await api.close();
    });

  storage
    .command('extract')
    .description('extract item/fluid/energy from a target')
    .option('--handle <id>', 'handle; defaults from resource kind')
    .requiredOption('--amount <n>', 'amount to extract')
    .option('--target <json>', 'Target JSON or @file')
    .option('--x <n>', 'block x coordinate')
    .option('--y <n>', 'block y coordinate')
    .option('--z <n>', 'block z coordinate')
    .option('--dim <id>', 'dimension id')
    .option('--side <side>', 'side: up|down|north|south|east|west|null')
    .option('--resource <json>', 'StorageResource JSON or @file')
    .option('--item <id>', 'item resource id')
    .option('--fluid <id>', 'fluid resource id')
    .option('--energy', 'energy resource')
    .option('--nbt <json>', 'resource NBT JSON or @file')
    .option('--simulate', 'do not commit')
    .action(async (opts: TargetOpts & ResourceOpts & { handle?: string; amount: string; side?: string; simulate?: boolean }) => {
      const api = getApi();
      const resource = await parseResource(opts);
      const amount = parseAmount(opts.amount);
      const r = await api.storage.extract(
        await parseTarget(opts, 'storage extract'),
        opts.handle ?? defaultHandle(resource),
        resource,
        amount,
        { side: normalizeSide(opts.side), simulate: opts.simulate },
      );
      outputOneLineJson(r);
      await api.close();
    });

  storage
    .command('transfer')
    .description('transfer item/fluid/energy between two targets')
    .requiredOption('--amount <n>', 'amount to transfer')
    .option('--from <x,y,z>', 'source block position')
    .option('--to <x,y,z>', 'destination block position')
    .option('--from-target <json>', 'source Target JSON or @file')
    .option('--to-target <json>', 'destination Target JSON or @file')
    .option('--dim <id>', 'dimension id for --from/--to')
    .option('--from-side <side>', 'source side')
    .option('--to-side <side>', 'destination side')
    .option('--resource <json>', 'StorageResource JSON or @file')
    .option('--item <id>', 'item resource id')
    .option('--fluid <id>', 'fluid resource id')
    .option('--energy', 'energy resource')
    .option('--nbt <json>', 'resource NBT JSON or @file')
    .option('--simulate', 'do not commit')
    .action(async (opts: ResourceOpts & {
      amount: string;
      from?: string;
      to?: string;
      fromTarget?: string;
      toTarget?: string;
      dim?: string;
      fromSide?: string;
      toSide?: string;
      simulate?: boolean;
    }) => {
      const api = getApi();
      const r = await api.storage.transfer(
        await parseEndpointTarget(opts.fromTarget, opts.from, opts.dim, 'storage transfer --from'),
        await parseEndpointTarget(opts.toTarget, opts.to, opts.dim, 'storage transfer --to'),
        await parseResource(opts),
        parseAmount(opts.amount),
        { fromSide: normalizeSide(opts.fromSide), toSide: normalizeSide(opts.toSide), simulate: opts.simulate },
      );
      outputOneLineJson(r);
      await api.close();
    });
}

async function parseTarget(opts: TargetOpts, what: string): Promise<Target> {
  if (opts.target) return (await parseJsonArg(opts.target)) as Target;
  return { kind: 'block', pos: requirePos(opts, what), dim: opts.dim };
}

async function parseEndpointTarget(json: string | undefined, posSpec: string | undefined, dim: string | undefined, what: string): Promise<Target> {
  if (json) return (await parseJsonArg(json)) as Target;
  if (!posSpec) throw new Error(`${what} requires --from/--to or --from-target/--to-target`);
  return { kind: 'block', pos: parseTriplet(posSpec, what), dim };
}

async function parseResource(opts: ResourceOpts): Promise<StorageResource> {
  if (opts.resource) return (await parseJsonArg(opts.resource)) as StorageResource;
  const nbt = opts.nbt ? ((await parseJsonArg(opts.nbt)) as JsonNbt) : undefined;
  if (opts.item) return { kind: 'item', item: opts.item, nbt };
  if (opts.fluid) return { kind: 'fluid', fluid: opts.fluid, nbt };
  if (opts.energy) return { kind: 'energy' };
  throw new Error('resource required: use --resource, --item, --fluid, or --energy');
}

function defaultHandle(resource: StorageResource): string {
  if (resource.kind === 'item') return 'fabric:item';
  if (resource.kind === 'fluid') return 'fabric:fluid';
  return 'teamreborn:energy';
}

function parseAmount(value: string): number {
  const amount = Number(value);
  if (!Number.isInteger(amount) || amount < 0) throw new Error('--amount must be a non-negative integer');
  return amount;
}

function normalizeSide(side: string | undefined): Side | undefined {
  if (side === undefined) return undefined;
  return side === 'null' ? null : (side as Side);
}

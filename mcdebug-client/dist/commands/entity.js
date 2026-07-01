import { FIND_ENTITIES_HELP } from './help-text.js';
import { parseJsonArg, parseTriplet, outputJson, outputOneLineJson } from './util.js';
export function registerEntityCommands(cmd, getApi) {
    cmd
        .command('find-entities')
        .description('list all entities in a box')
        .addHelpText('after', FIND_ENTITIES_HELP)
        .requiredOption('--from <x,y,z>', 'box from-corner (inclusive), 3 ints')
        .requiredOption('--to <x,y,z>', 'box to-corner (inclusive), 3 ints')
        .option('--type <id>', 'filter by entity type (e.g. minecraft:chicken)')
        .option('--nbt', 'include full entity NBT')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.scan.findEntities({ from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') }, { type: opts.type, includeNbt: !!opts.nbt, dim: opts.dim });
        outputJson(r);
        await api.close();
    });
    const entity = cmd.command('entity').description('spawn, inspect, move, and remove entities');
    entity
        .command('spawn')
        .description('spawn an entity at a position')
        .requiredOption('--type <id>', 'entity type id')
        .requiredOption('--pos <x,y,z>', 'spawn position')
        .option('--dim <id>', 'dimension id')
        .option('--yaw <n>', 'yaw degrees')
        .option('--pitch <n>', 'pitch degrees')
        .option('--nbt <json>', 'partial entity NBT JSON or @file')
        .option('--stack <json>', 'ItemStack JSON for minecraft:item, or @file')
        .option('--include-nbt', 'include spawned entity NBT')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.spawn(opts.type, parseTriplet(opts.pos, '--pos'), {
            dim: opts.dim,
            yaw: opts.yaw ? Number(opts.yaw) : undefined,
            pitch: opts.pitch ? Number(opts.pitch) : undefined,
            nbt: opts.nbt ? (await parseJsonArg(opts.nbt)) : undefined,
            stack: opts.stack ? (await parseJsonArg(opts.stack)) : undefined,
            includeNbt: !!opts.includeNbt,
        });
        outputJson(r);
        await api.close();
    });
    entity
        .command('get-nbt')
        .description('read entity NBT by UUID')
        .requiredOption('--uuid <uuid>', 'entity UUID')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.getNbt(opts.uuid, { dim: opts.dim });
        outputJson(r);
        await api.close();
    });
    entity
        .command('set-nbt')
        .description('merge partial NBT into an entity by UUID')
        .requiredOption('--uuid <uuid>', 'entity UUID')
        .requiredOption('--nbt <json>', 'NBT JSON or @file')
        .option('--dim <id>', 'dimension id')
        .option('--replace', 'replace full entity NBT instead of merging')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.setNbt(opts.uuid, (await parseJsonArg(opts.nbt)), {
            dim: opts.dim,
            replace: !!opts.replace,
        });
        outputJson(r);
        await api.close();
    });
    entity
        .command('teleport')
        .description('teleport an entity by UUID')
        .requiredOption('--uuid <uuid>', 'entity UUID')
        .requiredOption('--pos <x,y,z>', 'target position')
        .option('--dim <id>', 'dimension where the entity currently is')
        .option('--to-dim <id>', 'target dimension')
        .option('--yaw <n>', 'yaw degrees')
        .option('--pitch <n>', 'pitch degrees')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.teleport(opts.uuid, parseTriplet(opts.pos, '--pos'), {
            dim: opts.dim,
            toDim: opts.toDim,
            yaw: opts.yaw ? Number(opts.yaw) : undefined,
            pitch: opts.pitch ? Number(opts.pitch) : undefined,
        });
        outputOneLineJson(r);
        await api.close();
    });
    entity
        .command('remove')
        .description('discard an entity by UUID')
        .requiredOption('--uuid <uuid>', 'entity UUID')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.remove(opts.uuid, { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
    });
    entity
        .command('list-items')
        .description('list dropped item entities in a box')
        .requiredOption('--from <x,y,z>', 'box minimum corner')
        .requiredOption('--to <x,y,z>', 'box maximum corner')
        .option('--item <id>', 'filter by item id')
        .option('--dim <id>', 'dimension id')
        .option('--include-nbt', 'include full entity NBT')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.listItems({ from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') }, { item: opts.item, dim: opts.dim, includeNbt: !!opts.includeNbt });
        outputJson(r);
        await api.close();
    });
    entity
        .command('collect-items')
        .description('list and optionally remove dropped item entities in a box')
        .requiredOption('--from <x,y,z>', 'box minimum corner')
        .requiredOption('--to <x,y,z>', 'box maximum corner')
        .option('--item <id>', 'filter by item id')
        .option('--dim <id>', 'dimension id')
        .option('--keep', 'do not remove matching item entities')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.entity.collectItems({ from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') }, { item: opts.item, dim: opts.dim, remove: !opts.keep });
        outputJson(r);
        await api.close();
    });
}

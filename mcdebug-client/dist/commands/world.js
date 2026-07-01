import { COUNT_HELP, FIND_HELP, FORCELOAD_HELP, GET_HELP, PLACE_AS_PLAYER_HELP, PLACE_HELP, REMOVE_HELP, UNFORCELOAD_HELP } from './help-text.js';
import { parseJsonArg, parseTriplet, parseStateProps, requirePos, outputJson, outputOneLineJson } from './util.js';
export function registerWorldCommands(cmd, getApi) {
    cmd
        .command('place')
        .description('place a block at a position (raw setBlockState; no BlockItem pipeline, no fake player). For simulation of a real player placement, use place-as-player.')
        .addHelpText('after', PLACE_HELP)
        .requiredOption('--block <id>', 'block id, e.g. minecraft:stone')
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .option('--state <k=v>', 'state property, repeatable, e.g. --state lit=true', (value, previous) => previous.concat(value), [])
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .option('--flags <n>', 'setBlock flags (default 3)')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'place');
        const stateProps = opts.state ? parseStateProps(opts.state) : undefined;
        const flags = opts.flags ? Number(opts.flags) : undefined;
        const r = await api.world.setBlock(pos, opts.block, stateProps ? { name: opts.block, props: stateProps } : undefined, { dim: opts.dim, flags });
        outputOneLineJson(r);
        await api.close();
    });
    cmd
        .command('remove')
        .description('remove a block (sets it to minecraft:air)')
        .addHelpText('after', REMOVE_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'remove');
        const r = await api.world.setBlock(pos, 'minecraft:air', undefined, { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
    });
    cmd
        .command('fill-box')
        .description('fill a loaded box with one block state')
        .requiredOption('--from <x,y,z>', 'box minimum corner')
        .requiredOption('--to <x,y,z>', 'box maximum corner')
        .requiredOption('--block <id>', 'block id')
        .option('--state <k=v>', 'state property, repeatable', (value, previous) => previous.concat(value), [])
        .option('--dim <id>', 'dimension id')
        .option('--flags <n>', 'setBlock flags')
        .option('--max-blocks <n>', 'safety limit', '32768')
        .action(async (opts) => {
        const api = getApi();
        const stateProps = opts.state ? parseStateProps(opts.state) : undefined;
        const r = await api.world.fillBox(parseBox(opts.from, opts.to), opts.block, stateProps ? { name: opts.block, props: stateProps } : undefined, {
            dim: opts.dim,
            flags: opts.flags ? Number(opts.flags) : undefined,
            maxBlocks: Number(opts.maxBlocks),
        });
        outputOneLineJson(r);
        await api.close();
    });
    cmd
        .command('clear-box')
        .description('clear a loaded box to air')
        .requiredOption('--from <x,y,z>', 'box minimum corner')
        .requiredOption('--to <x,y,z>', 'box maximum corner')
        .option('--dim <id>', 'dimension id')
        .option('--flags <n>', 'setBlock flags')
        .option('--max-blocks <n>', 'safety limit', '32768')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.world.clearBox(parseBox(opts.from, opts.to), {
            dim: opts.dim,
            flags: opts.flags ? Number(opts.flags) : undefined,
            maxBlocks: Number(opts.maxBlocks),
        });
        outputOneLineJson(r);
        await api.close();
    });
    cmd
        .command('place-as-player')
        .description('place a block as if a player were clicking the side of an adjacent block (full BlockItem pipeline with a stable fake player)')
        .addHelpText('after', PLACE_AS_PLAYER_HELP)
        .requiredOption('--block <id>', 'block id, e.g. minecraft:furnace')
        .requiredOption('--x <n>', 'x coordinate of the placement target (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--face <dir>', 'which side of the neighbor was clicked: up|down|north|south|east|west')
        .option('--neighbor <x,y,z>', 'block that was clicked (default: pos offset by --face opposite)')
        .option('--player-facing <dir>', 'which way the placer was looking (default: opposite of --face if horizontal, else north)')
        .option('--nbt <json>', 'ItemStack NBT (e.g. BlockEntityTag for chest), or @file')
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'place-as-player');
        const face = opts.face.toLowerCase();
        if (!['up', 'down', 'north', 'south', 'east', 'west'].includes(face)) {
            throw new Error(`--face must be one of up|down|north|south|east|west, got: ${opts.face}`);
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const neighbor = opts.neighbor ? parseTriplet(opts.neighbor, '--neighbor') : undefined;
        const r = await api.world.placeAsPlayer(pos, opts.block, face, {
            neighbor,
            playerFacing: opts.playerFacing
                ? opts.playerFacing.toLowerCase()
                : undefined,
            nbt: nbt,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('get')
        .description('read the block at a position')
        .addHelpText('after', GET_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .option('--nbt', 'include block-entity NBT if present')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'get');
        const r = await api.world.getBlock(pos, { dim: opts.dim, includeNbt: !!opts.nbt });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('find')
        .description('find all positions of a block inside a box')
        .addHelpText('after', FIND_HELP)
        .requiredOption('--from <x,y,z>', 'box from-corner (inclusive), 3 ints')
        .requiredOption('--to <x,y,z>', 'box to-corner (inclusive), 3 ints')
        .requiredOption('--block <id>', 'block id to search for')
        .option('--count', 'include count in result')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.scan.findBlocks({ from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') }, opts.block, { count: opts.count, dim: opts.dim });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('count')
        .description('count blocks in a box, grouped by id')
        .addHelpText('after', COUNT_HELP)
        .requiredOption('--from <x,y,z>', 'box from-corner (inclusive), 3 ints')
        .requiredOption('--to <x,y,z>', 'box to-corner (inclusive), 3 ints')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.scan.countByBlock({ from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') }, opts.dim);
        outputJson(r);
        await api.close();
    });
    cmd
        .command('forceload')
        .description('force-load a chunk so its block entities tick')
        .addHelpText('after', FORCELOAD_HELP)
        .requiredOption('--cx <n>', 'chunk X coordinate (blockX >> 4, integer)')
        .requiredOption('--cz <n>', 'chunk Z coordinate (blockZ >> 4, integer)')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const cx = Number(opts.cx);
        const cz = Number(opts.cz);
        if (!Number.isInteger(cx) || !Number.isInteger(cz))
            throw new Error('--cx and --cz must be integers');
        const r = await api.world.forceloadChunk(cx, cz, { dim: opts.dim });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('unforceload')
        .description('stop force-loading a chunk')
        .addHelpText('after', UNFORCELOAD_HELP)
        .requiredOption('--cx <n>', 'chunk X coordinate (integer)')
        .requiredOption('--cz <n>', 'chunk Z coordinate (integer)')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const cx = Number(opts.cx);
        const cz = Number(opts.cz);
        if (!Number.isInteger(cx) || !Number.isInteger(cz))
            throw new Error('--cx and --cz must be integers');
        const r = await api.world.unforceloadChunk(cx, cz, { dim: opts.dim });
        outputJson(r);
        await api.close();
    });
}
function parseBox(from, to) {
    return { from: parseTriplet(from, '--from'), to: parseTriplet(to, '--to') };
}

import { COUNT_HELP, FIND_HELP, FORCELOAD_HELP, GET_HELP, PLACE_HELP, REMOVE_HELP, UNFORCELOAD_HELP } from './help-text.js';
import { parseTriplet, parseStateProps, requirePos, outputJson, outputOneLineJson } from './util.js';
export function registerWorldCommands(cmd, getApi) {
    cmd
        .command('place')
        .description('place a block at a position')
        .addHelpText('after', PLACE_HELP)
        .requiredOption('--block <id>', 'block id, e.g. minecraft:stone')
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .option('--state <k=v>', 'state property, repeatable, e.g. --state lit=true')
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

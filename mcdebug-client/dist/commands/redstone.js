import { outputJson, outputOneLineJson, requirePos } from './util.js';
export function registerRedstoneCommands(parent, getApi) {
    const redstone = parent.command('redstone').description('redstone power and lever controls');
    redstone
        .command('get-power')
        .description('read received and emitted redstone power at a position')
        .requiredOption('--x <n>', 'x coordinate')
        .requiredOption('--y <n>', 'y coordinate')
        .requiredOption('--z <n>', 'z coordinate')
        .option('--side <dir>', 'up|down|north|south|east|west')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.redstone.getPower(requirePos(opts, 'redstone get-power'), { side: opts.side, dim: opts.dim });
        outputJson(r);
        await api.close();
    });
    redstone
        .command('is-powered')
        .description('check whether a position receives redstone power')
        .requiredOption('--x <n>', 'x coordinate')
        .requiredOption('--y <n>', 'y coordinate')
        .requiredOption('--z <n>', 'z coordinate')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.redstone.isPowered(requirePos(opts, 'redstone is-powered'), { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
    });
    redstone
        .command('set-lever')
        .description('set a vanilla lever powered state')
        .requiredOption('--x <n>', 'x coordinate')
        .requiredOption('--y <n>', 'y coordinate')
        .requiredOption('--z <n>', 'z coordinate')
        .requiredOption('--powered <bool>', 'true|false')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.redstone.setLever(requirePos(opts, 'redstone set-lever'), parseBool(opts.powered, '--powered'), {
            dim: opts.dim,
        });
        outputOneLineJson(r);
        await api.close();
    });
    redstone
        .command('pulse')
        .description('turn a vanilla lever on, then off after natural server ticks')
        .requiredOption('--x <n>', 'x coordinate')
        .requiredOption('--y <n>', 'y coordinate')
        .requiredOption('--z <n>', 'z coordinate')
        .option('--ticks <n>', 'pulse length in natural server ticks', '2')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.redstone.pulse(requirePos(opts, 'redstone pulse'), Number(opts.ticks), { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
    });
    redstone
        .command('notify-neighbors')
        .description('trigger neighbor updates from the block at a position')
        .requiredOption('--x <n>', 'x coordinate')
        .requiredOption('--y <n>', 'y coordinate')
        .requiredOption('--z <n>', 'z coordinate')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.redstone.notifyNeighbors(requirePos(opts, 'redstone notify-neighbors'), { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
    });
}
function parseBool(value, name) {
    if (value === 'true')
        return true;
    if (value === 'false')
        return false;
    throw new Error(`${name} must be true or false`);
}

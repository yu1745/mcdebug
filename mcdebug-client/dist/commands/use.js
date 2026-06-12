import { USE_HELP, USE_ITEM_HELP, ATTACK_HELP } from './help-text.js';
import { parseJsonArg, requirePos, outputJson } from './util.js';
export function registerUseCommand(cmd, getApi) {
    cmd
        .command('use-item')
        .description('right-click (use) an item in air — calls Item.use without a block/entity target')
        .addHelpText('after', USE_ITEM_HELP)
        .requiredOption('--item <id>', 'item id to hold and use')
        .option('--count <n>', 'stack size (default 1)', '1')
        .option('--nbt <json>', 'item NBT, or @file')
        .option('--sneaking', 'simulate shift+right-click')
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .action(async (opts) => {
        const api = getApi();
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.useItem(opts.item, {
            count: Number(opts.count),
            nbt: nbt,
            sneaking: opts.sneaking,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('use')
        .description('right-click (use) a block with an item in hand — fires Fabric API UseBlockCallback + vanilla Block.onUse + Item.useOnBlock pipeline')
        .addHelpText('after', USE_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--face <dir>', 'which face to click: up|down|north|south|east|west')
        .option('--item <id>', 'item id to hold (omit for empty hand)')
        .option('--count <n>', 'stack size (default 1)', '1')
        .option('--nbt <json>', 'item NBT, or @file')
        .option('--sneaking', 'simulate shift+right-click')
        .option('--player-facing <dir>', 'which direction the player is facing: north|south|east|west (default: face.opposite)')
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'use');
        const face = opts.face.toLowerCase();
        if (!['up', 'down', 'north', 'south', 'east', 'west'].includes(face)) {
            throw new Error(`--face must be one of up|down|north|south|east|west, got: ${opts.face}`);
        }
        if (opts.playerFacing) {
            const pf = opts.playerFacing.toLowerCase();
            if (!['north', 'south', 'east', 'west'].includes(pf)) {
                throw new Error(`--player-facing must be one of north|south|east|west, got: ${opts.playerFacing}`);
            }
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.useOnBlock(pos, face, {
            item: opts.item,
            count: Number(opts.count),
            nbt: nbt,
            sneaking: opts.sneaking,
            playerFacing: opts.playerFacing,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('attack')
        .description('left-click (attack) a block — triggers onBlockBreakStart and breaks the block in creative mode')
        .addHelpText('after', ATTACK_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--face <dir>', 'which face was hit: up|down|north|south|east|west')
        .option('--item <id>', 'item id to hold (omit for empty hand)')
        .option('--count <n>', 'stack size (default 1)', '1')
        .option('--nbt <json>', 'item NBT, or @file')
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .action(async (opts) => {
        const api = getApi();
        const pos = requirePos(opts, 'attack');
        const face = opts.face.toLowerCase();
        if (!['up', 'down', 'north', 'south', 'east', 'west'].includes(face)) {
            throw new Error(`--face must be one of up|down|north|south|east|west, got: ${opts.face}`);
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.attackBlock(pos, face, {
            item: opts.item,
            count: Number(opts.count),
            nbt: nbt,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
}

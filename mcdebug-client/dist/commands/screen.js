import { outputJson, outputOneLineJson, parseJsonArg, requirePos } from './util.js';
export function registerScreenCommands(parent, getApi) {
    const screen = parent.command('screen').description('server-side ScreenHandler operations');
    screen
        .command('open-block')
        .description('open a block ScreenHandler with a fake player')
        .requiredOption('--x <n>', 'block x coordinate')
        .requiredOption('--y <n>', 'block y coordinate')
        .requiredOption('--z <n>', 'block z coordinate')
        .option('--dim <id>', 'dimension id')
        .option('--side <side>', 'side used to position the fake player')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.screen.openBlock(requirePos(opts, 'screen open-block'), {
            dim: opts.dim,
            player: 'fake',
            side: normalizeSide(opts.side),
        });
        outputJson(r);
        await api.close();
    });
    screen
        .command('snapshot')
        .description('read an open ScreenHandler snapshot')
        .requiredOption('--screen-id <id>', 'screen id from screen open-block')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.screen.snapshot(opts.screenId);
        outputJson(r);
        await api.close();
    });
    screen
        .command('set-player-slot')
        .description('set a fake player main inventory slot for later click/quick-move')
        .requiredOption('--screen-id <id>', 'screen id')
        .requiredOption('--slot <n>', 'player inventory slot 0..35')
        .requiredOption('--item <id>', 'item id, or minecraft:air to clear')
        .option('--count <n>', 'item count', '1')
        .option('--nbt <json>', 'NBT JSON or @file')
        .action(async (opts) => {
        const api = getApi();
        const slot = parseSlot(opts.slot);
        const stack = await parseStack(opts);
        const r = await api.screen.setPlayerSlot(opts.screenId, slot, stack);
        outputJson(r);
        await api.close();
    });
    screen
        .command('click-slot')
        .description('run ScreenHandler.onSlotClick')
        .requiredOption('--screen-id <id>', 'screen id')
        .requiredOption('--slot <n>', 'handler slot index')
        .option('--button <n>', 'button id', '0')
        .option('--action-type <type>', 'pickup|quick_move|swap|clone|throw|quick_craft|pickup_all', 'pickup')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.screen.clickSlot(opts.screenId, parseSlot(opts.slot), parseSlot(opts.button), opts.actionType);
        outputJson(r);
        await api.close();
    });
    screen
        .command('quick-move')
        .description('shift-click a handler slot')
        .requiredOption('--screen-id <id>', 'screen id')
        .requiredOption('--slot <n>', 'handler slot index')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.screen.quickMove(opts.screenId, parseSlot(opts.slot));
        outputJson(r);
        await api.close();
    });
    screen
        .command('close')
        .description('close and forget a ScreenHandler session')
        .requiredOption('--screen-id <id>', 'screen id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.screen.close(opts.screenId);
        outputOneLineJson(r);
        await api.close();
    });
}
async function parseStack(opts) {
    const isAir = opts.item === '' || opts.item === 'minecraft:air';
    const count = isAir ? 0 : Number(opts.count);
    if (!Number.isInteger(count) || count < 0)
        throw new Error('--count must be a non-negative integer');
    return {
        item: isAir ? null : opts.item,
        count,
        nbt: opts.nbt ? (await parseJsonArg(opts.nbt)) : undefined,
    };
}
function parseSlot(value) {
    const slot = Number(value);
    if (!Number.isInteger(slot))
        throw new Error('slot/button must be an integer');
    return slot;
}
function normalizeSide(side) {
    if (side === undefined)
        return undefined;
    return side === 'null' ? null : side;
}

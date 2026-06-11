import { INV_EXTRACT_HELP, INV_GET_HELP, INV_GROUP_HELP, INV_INSERT_HELP, INV_SET_HELP } from './help-text.js';
import { outputJson, outputOneLineJson, parseJsonArg, requirePos } from './util.js';
export function registerInvCommands(parent, getApi) {
    const inv = parent.command('inv').description('inventory operations').addHelpText('after', INV_GROUP_HELP);
    inv
        .command('get')
        .description('read a slot from the inventory at a position')
        .addHelpText('after', INV_GET_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--slot <n>', 'slot index (integer)')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const slot = Number(opts.slot);
        if (!Number.isInteger(slot))
            throw new Error('--slot must be an integer');
        const r = await api.inv.getSlot(requirePos(opts, 'inv get'), slot, opts.dim);
        outputJson(r);
        await api.close();
    });
    inv
        .command('set')
        .description('set a slot; pass --item minecraft:air (or "") with --count 0 to clear')
        .addHelpText('after', INV_SET_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--slot <n>', 'slot index (integer)')
        .requiredOption('--item <id>', 'item id, e.g. minecraft:iron_ore ("" or "minecraft:air" to clear)')
        .option('--count <n>', 'item count (default 1 for items, 0 for air)', '1')
        .option('--nbt <json>', 'NBT as a JSON object or @file')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const slot = Number(opts.slot);
        if (!Number.isInteger(slot))
            throw new Error('--slot must be an integer');
        const isAir = opts.item === '' || opts.item === 'minecraft:air';
        const count = isAir ? 0 : Number(opts.count);
        if (!Number.isInteger(count) || count < 0)
            throw new Error('--count must be a non-negative integer');
        const nbtJson = opts.nbt ? (await parseJsonArg(opts.nbt)) : undefined;
        const r = await api.inv.setSlot(requirePos(opts, 'inv set'), slot, opts.item === '' ? null : opts.item, count, nbtJson, opts.dim);
        outputOneLineJson(r);
        await api.close();
    });
    inv
        .command('insert')
        .description('insert items into an inventory (auto-distribute across slots unless --slot given)')
        .addHelpText('after', INV_INSERT_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--item <id>', 'item id to insert')
        .option('--count <n>', 'how many to insert (default 1)', '1')
        .option('--slot <n>', 'force into a specific slot')
        .option('--simulate', 'do not actually modify the inventory')
        .option('--nbt <json>', 'NBT as a JSON object or @file')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const count = Number(opts.count);
        if (!Number.isInteger(count) || count < 0)
            throw new Error('--count must be a non-negative integer');
        const slot = opts.slot !== undefined ? Number(opts.slot) : undefined;
        if (slot !== undefined && !Number.isInteger(slot))
            throw new Error('--slot must be an integer');
        const nbtJson = opts.nbt ? (await parseJsonArg(opts.nbt)) : undefined;
        const r = await api.inv.insert(requirePos(opts, 'inv insert'), opts.item, count, {
            slot,
            simulate: opts.simulate,
            nbt: nbtJson,
            dim: opts.dim,
        });
        outputOneLineJson(r);
        await api.close();
    });
    inv
        .command('extract')
        .description('extract items from an inventory')
        .addHelpText('after', INV_EXTRACT_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--item <id>', 'item id to extract')
        .option('--count <n>', 'how many to extract (default 1)', '1')
        .option('--slot <n>', 'force from a specific slot')
        .option('--simulate', 'do not actually modify the inventory')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const count = Number(opts.count);
        if (!Number.isInteger(count) || count < 0)
            throw new Error('--count must be a non-negative integer');
        const slot = opts.slot !== undefined ? Number(opts.slot) : undefined;
        if (slot !== undefined && !Number.isInteger(slot))
            throw new Error('--slot must be an integer');
        const r = await api.inv.extract(requirePos(opts, 'inv extract'), opts.item, count, {
            slot,
            simulate: opts.simulate,
            dim: opts.dim,
        });
        outputOneLineJson(r);
        await api.close();
    });
}

import { BE_GET_FIELD_HELP, BE_GET_NBT_HELP, BE_SET_FIELD_HELP, BE_SET_NBT_HELP } from './help-text.js';
import { outputJson, outputOneLineJson, parseJsonArg, requirePos } from './util.js';
export function registerBeCommands(cmd, getApi) {
    cmd
        .command('get-nbt')
        .description('read full block-entity NBT at a position')
        .addHelpText('after', BE_GET_NBT_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.be.getNbt(requirePos(opts, 'be get-nbt'), opts.dim);
        outputJson(r);
        await api.close();
    });
    cmd
        .command('set-nbt')
        .description('write full block-entity NBT; --nbt is a JSON object or @file.json')
        .addHelpText('after', BE_SET_NBT_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--nbt <json>', 'NBT as a JSON object literal or @file.json')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const parsed = await parseJsonArg(opts.nbt);
        const r = await api.be.setNbt(requirePos(opts, 'be set-nbt'), parsed, opts.dim);
        outputOneLineJson(r);
        await api.close();
    });
    cmd
        .command('get-field')
        .description('read a single NBT field by JSON-Pointer-like path, e.g. Items.0.count')
        .addHelpText('after', BE_GET_FIELD_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--path <p>', 'dot path, e.g. Items.0.count or BurnTime')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.be.getField(requirePos(opts, 'be get-field'), opts.path, opts.dim);
        outputJson(r);
        await api.close();
    });
    cmd
        .command('set-field')
        .description('write a single NBT field; --value is a JSON literal or @file')
        .addHelpText('after', BE_SET_FIELD_HELP)
        .requiredOption('--x <n>', 'x coordinate (integer)')
        .requiredOption('--y <n>', 'y coordinate (integer)')
        .requiredOption('--z <n>', 'z coordinate (integer)')
        .requiredOption('--path <p>', 'dot path, e.g. Items.0.count or BurnTime')
        .requiredOption('--value <json>', 'value as a JSON literal or @file')
        .option('--dim <id>', 'dimension id')
        .action(async (opts) => {
        const api = getApi();
        const parsed = await parseJsonArg(opts.value);
        const r = await api.be.setField(requirePos(opts, 'be set-field'), opts.path, parsed, opts.dim);
        outputOneLineJson(r);
        await api.close();
    });
}

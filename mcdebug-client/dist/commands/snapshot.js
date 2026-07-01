import { outputJson, parseJsonArg, parseTriplet } from './util.js';
export function registerSnapshotCommands(parent, getApi) {
    const snapshot = parent.command('snapshot').description('capture and diff world-state snapshots');
    snapshot
        .command('capture')
        .description('capture a structured snapshot of a loaded box')
        .option('--box <json>', 'Box JSON or @file')
        .option('--from <x,y,z>', 'box minimum corner')
        .option('--to <x,y,z>', 'box maximum corner')
        .option('--dim <id>', 'dimension id')
        .option('--include <list>', 'comma list: block,blockEntityNbt,inventory,fluid,energy,entity')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.snapshot.capture({
            box: await parseBox(opts),
            dim: opts.dim,
            include: parseInclude(opts.include),
        });
        outputJson(r);
        await api.close();
    });
    snapshot
        .command('diff')
        .description('diff two snapshot JSON values')
        .requiredOption('--before <json>', 'before JSON or @file')
        .requiredOption('--after <json>', 'after JSON or @file')
        .action(async (opts) => {
        const api = getApi();
        const before = (await parseJsonArg(opts.before));
        const after = (await parseJsonArg(opts.after));
        const r = await api.snapshot.diff(before, after);
        outputJson(r);
        await api.close();
    });
}
async function parseBox(opts) {
    if (opts.box)
        return (await parseJsonArg(opts.box));
    if (!opts.from || !opts.to)
        throw new Error('snapshot capture requires --box or both --from and --to');
    return {
        from: parseTriplet(opts.from, '--from'),
        to: parseTriplet(opts.to, '--to'),
    };
}
function parseInclude(value) {
    if (!value)
        return undefined;
    return value.split(',').map((part) => part.trim()).filter(Boolean);
}

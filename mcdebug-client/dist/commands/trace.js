import { outputJson, outputOneLineJson, parseJsonArg, parseTriplet } from './util.js';
export function registerTraceCommands(parent, getApi) {
    const trace = parent.command('trace').description('record snapshot timelines over natural server ticks');
    trace
        .command('start')
        .description('start capturing snapshots every N ticks')
        .option('--box <json>', 'Box JSON or @file')
        .option('--from <x,y,z>', 'box minimum corner')
        .option('--to <x,y,z>', 'box maximum corner')
        .option('--dim <id>', 'dimension id')
        .option('--include <list>', 'comma list: block,blockEntityNbt,inventory,fluid,energy,entity')
        .option('--interval-ticks <n>', 'capture interval in ticks', '1')
        .action(async (opts) => {
        const api = getApi();
        const intervalTicks = Number(opts.intervalTicks);
        if (!Number.isInteger(intervalTicks) || intervalTicks < 1)
            throw new Error('--interval-ticks must be a positive integer');
        const r = await api.trace.start({
            box: await parseBox(opts),
            dim: opts.dim,
            include: parseInclude(opts.include),
            intervalTicks,
        });
        outputOneLineJson(r);
        await api.close();
    });
    trace
        .command('get')
        .description('read a trace without stopping it')
        .requiredOption('--trace-id <id>', 'trace id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.trace.get(opts.traceId);
        outputJson(r);
        await api.close();
    });
    trace
        .command('stop')
        .description('stop a trace and return all captured frames')
        .requiredOption('--trace-id <id>', 'trace id')
        .action(async (opts) => {
        const api = getApi();
        const r = await api.trace.stop(opts.traceId);
        outputJson(r);
        await api.close();
    });
}
async function parseBox(opts) {
    if (opts.box)
        return (await parseJsonArg(opts.box));
    if (!opts.from || !opts.to)
        throw new Error('trace start requires --box or both --from and --to');
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

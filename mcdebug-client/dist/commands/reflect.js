import { REFLECT_HELP } from './help-text.js';
import { parseJsonArg, outputJson } from './util.js';
export function registerReflectCommands(cmd, getApi) {
    cmd
        .command('reflect [class]')
        .description('inspect a class at runtime (fields/methods); accepts yarn or intermediary names')
        .addHelpText('after', REFLECT_HELP)
        .option('--members', 'list all fields and methods')
        .option('--field <name>', 'read a static field value (or --field with --ref)')
        .option('--method <name>', 'invoke a static method (args via --args)')
        .option('--args <json>', 'method args as JSON array, or @file')
        .option('--ref <n>', 'target an object reference returned by a previous call instead of the class')
        .action(async (className, opts) => {
        const api = getApi();
        const args = opts.args ? await parseJsonArg(opts.args) : undefined;
        let r;
        if (opts.ref !== undefined) {
            if (opts.field) {
                r = await api.rpc.call('reflect.get', { ref: Number(opts.ref), field: opts.field });
            }
            else if (opts.method) {
                r = await api.rpc.call('reflect.call', {
                    ref: Number(opts.ref),
                    method: opts.method,
                    args: Array.isArray(args) ? args : undefined,
                });
            }
            else {
                throw new Error('--ref requires --field or --method');
            }
        }
        else if (opts.field) {
            r = await api.rpc.call('reflect.get', { class: className, field: opts.field });
        }
        else if (opts.method) {
            r = await api.rpc.call('reflect.call', {
                class: className,
                method: opts.method,
                args: Array.isArray(args) ? args : undefined,
            });
        }
        else {
            r = await api.rpc.call('reflect.resolve', { class: className, members: opts.members ?? false });
        }
        outputJson(r);
        await api.close();
    });
    cmd
        .command('refs')
        .description('list live object references held by the reflect API')
        .action(async () => {
        const api = getApi();
        const r = await api.rpc.call('reflect.refs');
        outputJson(r);
        await api.close();
    });
    cmd
        .command('new <class>')
        .description('construct an object with a constructor (args via --args), returns a ref')
        .option('--args <json>', 'constructor args as JSON array, or @file')
        .action(async (className, opts) => {
        const api = getApi();
        const args = opts.args ? await parseJsonArg(opts.args) : undefined;
        const r = await api.rpc.call('reflect.new', {
            class: className,
            args: Array.isArray(args) ? args : undefined,
        });
        outputJson(r);
        await api.close();
    });
}

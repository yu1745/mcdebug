import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { FIND_ENTITIES_HELP } from './help-text.js';
import { parseTriplet, outputJson } from './util.js';

export function registerEntityCommands(cmd: Command, getApi: () => DebugApi): void {
  cmd
    .command('find-entities')
    .description('list all entities in a box')
    .addHelpText('after', FIND_ENTITIES_HELP)
    .requiredOption('--from <x,y,z>', 'box from-corner (inclusive), 3 ints')
    .requiredOption('--to <x,y,z>', 'box to-corner (inclusive), 3 ints')
    .option('--type <id>', 'filter by entity type (e.g. minecraft:chicken)')
    .option('--nbt', 'include full entity NBT')
    .option('--dim <id>', 'dimension id')
    .action(
      async (opts: {
        from: string;
        to: string;
        type?: string;
        nbt?: boolean;
        dim?: string;
      }) => {
        const api = getApi();
        const r = await api.scan.findEntities(
          { from: parseTriplet(opts.from, '--from'), to: parseTriplet(opts.to, '--to') },
          { type: opts.type, includeNbt: !!opts.nbt, dim: opts.dim },
        );
        outputJson(r);
        await api.close();
      },
    );
}

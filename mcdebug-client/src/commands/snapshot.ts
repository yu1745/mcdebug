import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import type { Box, SnapshotKind } from '../types.js';
import { outputJson, parseJsonArg, parseTriplet } from './util.js';

export function registerSnapshotCommands(parent: Command, getApi: () => DebugApi): void {
  const snapshot = parent.command('snapshot').description('capture and diff world-state snapshots');

  snapshot
    .command('capture')
    .description('capture a structured snapshot of a loaded box')
    .option('--box <json>', 'Box JSON or @file')
    .option('--from <x,y,z>', 'box minimum corner')
    .option('--to <x,y,z>', 'box maximum corner')
    .option('--dim <id>', 'dimension id')
    .option('--include <list>', 'comma list: block,blockEntityNbt,inventory,fluid,energy,entity')
    .action(async (opts: { box?: string; from?: string; to?: string; dim?: string; include?: string }) => {
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
    .action(async (opts: { before: string; after: string }) => {
      const api = getApi();
      const before = (await parseJsonArg(opts.before)) as never;
      const after = (await parseJsonArg(opts.after)) as never;
      const r = await api.snapshot.diff(before, after);
      outputJson(r);
      await api.close();
    });
}

async function parseBox(opts: { box?: string; from?: string; to?: string }): Promise<Box> {
  if (opts.box) return (await parseJsonArg(opts.box)) as Box;
  if (!opts.from || !opts.to) throw new Error('snapshot capture requires --box or both --from and --to');
  return {
    from: parseTriplet(opts.from, '--from'),
    to: parseTriplet(opts.to, '--to'),
  };
}

function parseInclude(value: string | undefined): SnapshotKind[] | undefined {
  if (!value) return undefined;
  return value.split(',').map((part) => part.trim()).filter(Boolean) as SnapshotKind[];
}

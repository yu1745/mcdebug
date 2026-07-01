import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import type { Box, FixtureJson } from '../types.js';
import { outputJson, outputOneLineJson, parseJsonArg, parseTriplet } from './util.js';

export function registerFixtureCommands(parent: Command, getApi: () => DebugApi): void {
  const fixture = parent.command('fixture').description('capture and load block-region fixtures');

  fixture
    .command('capture')
    .description('capture a loaded block box as a reloadable fixture JSON')
    .requiredOption('--from <x,y,z>', 'box minimum corner')
    .requiredOption('--to <x,y,z>', 'box maximum corner')
    .option('--dim <id>', 'dimension id')
    .option('--no-nbt', 'omit block entity NBT')
    .action(async (opts: { from: string; to: string; dim?: string; nbt?: boolean }) => {
      const api = getApi();
      const r = await api.fixture.capture(parseBox(opts.from, opts.to), { dim: opts.dim, includeNbt: opts.nbt !== false });
      outputJson(r);
      await api.close();
    });

  fixture
    .command('load')
    .description('load a fixture JSON, optionally at a new origin')
    .requiredOption('--fixture <json>', 'fixture JSON or @file')
    .option('--origin <x,y,z>', 'new origin; default fixture.origin')
    .option('--dim <id>', 'target dimension')
    .option('--flags <n>', 'setBlock flags')
    .action(async (opts: { fixture: string; origin?: string; dim?: string; flags?: string }) => {
      const api = getApi();
      const fixtureJson = (await parseJsonArg(opts.fixture)) as FixtureJson;
      const r = await api.fixture.load(fixtureJson, {
        origin: opts.origin ? parseTriplet(opts.origin, '--origin') : undefined,
        dim: opts.dim,
        flags: opts.flags ? Number(opts.flags) : undefined,
      });
      outputOneLineJson(r);
      await api.close();
    });
}

function parseBox(from: string, to: string): Box {
  return { from: parseTriplet(from, '--from'), to: parseTriplet(to, '--to') };
}

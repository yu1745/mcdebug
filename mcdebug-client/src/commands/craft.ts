import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { JsonNbt } from '../types.js';
import { CRAFT_DO_HELP, CRAFT_FIND_HELP, CRAFT_GROUP_HELP } from './help-text.js';
import { parseJsonArg, outputJson, outputOneLineJson } from './util.js';

type GridSlot = { item: string; count?: number; nbt?: JsonNbt } | null;

export function registerCraftCommands(cmd: Command, getApi: () => DebugApi): void {
  const group = cmd
    .command('craft')
    .description('simulate vanilla / modded crafting without placing a crafting table')
    .addHelpText('after', CRAFT_GROUP_HELP);

  group
    .command('do')
    .description('simulate one craft of a 3x3 grid; returns the result + per-slot remainder')
    .addHelpText('after', CRAFT_DO_HELP)
    .requiredOption('--grid <json>', '9-element JSON array of grid slots (row-major), or @file.json')
    .option('--recipe-id <id>', 'force a specific recipe id (use craft find to discover)')
    .option('--dim <id>', 'dimension (default minecraft:overworld)')
    .action(
      async (opts: { grid: string; recipeId?: string; dim?: string }) => {
        const api = getApi();
        const grid = (await parseJsonArg(opts.grid)) as GridSlot[];
        if (!Array.isArray(grid) || grid.length !== 9) {
          throw new Error(`--grid must be a 9-element JSON array, got ${Array.isArray(grid) ? grid.length : typeof grid}`);
        }
        const r = await api.craft.craft(grid, { recipeId: opts.recipeId, dim: opts.dim });
        outputJson(r);
        await api.close();
      },
    );

  group
    .command('find')
    .description('list every crafting recipe that matches a 3x3 grid (diagnostic)')
    .addHelpText('after', CRAFT_FIND_HELP)
    .requiredOption('--grid <json>', '9-element JSON array of grid slots (row-major), or @file.json')
    .option('--dim <id>', 'dimension (default minecraft:overworld)')
    .action(
      async (opts: { grid: string; dim?: string }) => {
        const api = getApi();
        const grid = (await parseJsonArg(opts.grid)) as GridSlot[];
        if (!Array.isArray(grid) || grid.length !== 9) {
          throw new Error(`--grid must be a 9-element JSON array, got ${Array.isArray(grid) ? grid.length : typeof grid}`);
        }
        const r = await api.craft.find(grid, { dim: opts.dim });
        outputOneLineJson(r);
        await api.close();
      },
    );
}

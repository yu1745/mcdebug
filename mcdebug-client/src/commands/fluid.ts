import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import {
  FLUID_GET_HELP,
  FLUID_GROUP_HELP,
  FLUID_INFO_HELP,
  FLUID_INSERT_HELP,
  FLUID_EXTRACT_HELP,
} from './help-text.js';
import { outputJson, outputOneLineJson, requirePos } from './util.js';

export function registerFluidCommands(parent: Command, getApi: () => DebugApi): void {
  const fluid = parent
    .command('fluid')
    .description('fluid storage operations (Fabric Transfer API)')
    .addHelpText('after', FLUID_GROUP_HELP);

  fluid
    .command('info')
    .description('enumerate the tanks at a position (one Storage may have multiple parts)')
    .addHelpText('after', FLUID_INFO_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .option('--side <id>', 'side: null|north|south|east|west|up|down (default null)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: { x: string; y: string; z: string; side?: string; dim?: string }) => {
        try {
          const api = getApi();
          const r = await api.fluid.info(requirePos(opts, 'fluid info'), {
            side: opts.side,
            dim: opts.dim,
          });
          outputJson(r);
          await api.close();
        } catch (e) {
          throw e;
        }
      },
    );

  fluid
    .command('get')
    .description('read a single tank at a position (use --index for multi-tank storages)')
    .addHelpText('after', FLUID_GET_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .option('--side <id>', 'side: null|north|south|east|west|up|down (default null)')
    .option('--index <n>', 'tank index (0-based); required if storage has multiple tanks')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: { x: string; y: string; z: string; side?: string; index?: string; dim?: string }) => {
        const api = getApi();
        const index = opts.index !== undefined ? Number(opts.index) : undefined;
        if (index !== undefined && !Number.isInteger(index)) throw new Error('--index must be an integer');
        const r = await api.fluid.get(requirePos(opts, 'fluid get'), {
          side: opts.side,
          index,
          dim: opts.dim,
        });
        outputJson(r);
        await api.close();
      },
    );

  fluid
    .command('insert')
    .description('insert fluid into a specific tank (precise: bypasses CombinedStorage auto-distribution)')
    .addHelpText('after', FLUID_INSERT_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .requiredOption('--fluid <id>', 'fluid id, e.g. minecraft:water or ic2_120:biofuel')
    .requiredOption('--amount <n>', 'amount in droplets (81000 droplets = 1 bucket)')
    .option('--side <id>', 'side: null|north|south|east|west|up|down (default null)')
    .option('--index <n>', 'tank index (0-based); required if storage has multiple tanks')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        x: string;
        y: string;
        z: string;
        fluid: string;
        amount: string;
        side?: string;
        index?: string;
        dim?: string;
      }) => {
        const api = getApi();
        const amount = Number(opts.amount);
        if (!Number.isInteger(amount) || amount < 0) throw new Error('--amount must be a non-negative integer');
        const index = opts.index !== undefined ? Number(opts.index) : undefined;
        if (index !== undefined && !Number.isInteger(index)) throw new Error('--index must be an integer');
        const r = await api.fluid.insert(requirePos(opts, 'fluid insert'), opts.fluid, amount, {
          side: opts.side,
          index,
          dim: opts.dim,
        });
        outputOneLineJson(r);
        await api.close();
      },
    );

  fluid
    .command('extract')
    .description('extract fluid from a specific tank')
    .addHelpText('after', FLUID_EXTRACT_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .requiredOption('--amount <n>', 'amount in droplets to extract (81000 = 1 bucket)')
    .option('--side <id>', 'side: null|north|south|east|west|up|down (default null)')
    .option('--index <n>', 'tank index (0-based); required if storage has multiple tanks')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        x: string;
        y: string;
        z: string;
        amount: string;
        side?: string;
        index?: string;
        dim?: string;
      }) => {
        const api = getApi();
        const amount = Number(opts.amount);
        if (!Number.isInteger(amount) || amount < 0) throw new Error('--amount must be a non-negative integer');
        const index = opts.index !== undefined ? Number(opts.index) : undefined;
        if (index !== undefined && !Number.isInteger(index)) throw new Error('--index must be an integer');
        const r = await api.fluid.extract(requirePos(opts, 'fluid extract'), amount, {
          side: opts.side,
          index,
          dim: opts.dim,
        });
        outputOneLineJson(r);
        await api.close();
      },
    );
}

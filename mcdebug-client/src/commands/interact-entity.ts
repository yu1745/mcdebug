import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { JsonNbt } from '../types.js';
import { ATTACK_ENTITY_HELP, INTERACT_ENTITY_HELP } from './help-text.js';
import { parseJsonArg, outputJson } from './util.js';

export function registerInteractEntityCommand(cmd: Command, getApi: () => DebugApi): void {
  cmd
    .command('interact-entity')
    .description('right-click (use) an entity with an item — Fabric UseEntityCallback + entity.interact + item.useOnEntity')
    .addHelpText('after', INTERACT_ENTITY_HELP)
    .requiredOption('--uuid <uuid>', 'UUID of the target entity (from find-entities)')
    .option('--item <id>', 'item id to hold (omit for empty hand)')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file')
    .option('--sneaking', 'simulate shift+right-click')
    .option('--player-facing <dir>', 'player facing: north|south|east|west (default: south)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        uuid: string;
        item?: string;
        count: string;
        nbt?: string;
        sneaking?: boolean;
        playerFacing?: string;
        dim?: string;
      }) => {
        const api = getApi();
        if (opts.playerFacing) {
          const pf = opts.playerFacing.toLowerCase();
          if (!['north', 'south', 'east', 'west'].includes(pf)) {
            throw new Error(`--player-facing must be one of north|south|east|west, got: ${opts.playerFacing}`);
          }
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.interactEntity(opts.uuid, {
          item: opts.item,
          count: Number(opts.count),
          nbt: nbt as JsonNbt | undefined,
          sneaking: opts.sneaking,
          playerFacing: opts.playerFacing as 'north' | 'south' | 'east' | 'west' | undefined,
          dim: opts.dim,
        });
        outputJson(r);
        await api.close();
      },
    );

  cmd
    .command('attack-entity')
    .description('left-click (attack) an entity — Fabric AttackEntityCallback + PlayerEntity.attack')
    .addHelpText('after', ATTACK_ENTITY_HELP)
    .requiredOption('--uuid <uuid>', 'UUID of the target entity (from find-entities)')
    .option('--item <id>', 'item id to hold (omit for empty hand)')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file')
    .option('--player-facing <dir>', 'player facing: north|south|east|west (default: south)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        uuid: string;
        item?: string;
        count: string;
        nbt?: string;
        playerFacing?: string;
        dim?: string;
      }) => {
        const api = getApi();
        if (opts.playerFacing) {
          const pf = opts.playerFacing.toLowerCase();
          if (!['north', 'south', 'east', 'west'].includes(pf)) {
            throw new Error(`--player-facing must be one of north|south|east|west, got: ${opts.playerFacing}`);
          }
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.attackEntity(opts.uuid, {
          item: opts.item,
          count: Number(opts.count),
          nbt: nbt as JsonNbt | undefined,
          playerFacing: opts.playerFacing as 'north' | 'south' | 'east' | 'west' | undefined,
          dim: opts.dim,
        });
        outputJson(r);
        await api.close();
      },
    );
}

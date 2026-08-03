import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { JsonNbt } from '../types.js';
import { USE_HELP, USE_ITEM_HELP, ATTACK_HELP, SHOOT_HELP } from './help-text.js';
import { parseJsonArg, requirePos, outputJson } from './util.js';

type ArmorSpec = Partial<Record<'head' | 'chest' | 'legs' | 'feet', { item: string; count?: number; nbt?: JsonNbt }>>;

function parsePos(s: string): [number, number, number] {
  const parts = s.split(',').map((v) => Number(v.trim()));
  if (parts.length !== 3 || parts.some((v) => Number.isNaN(v))) {
    throw new Error(`--pos must be x,y,z, got: ${s}`);
  }
  return parts as [number, number, number];
}

export function registerUseCommand(cmd: Command, getApi: () => DebugApi): void {
  cmd
    .command('shoot')
    .description('right-click (use) a ranged weapon, hold it for a while, then release — vanilla item-use lifecycle for bows/crossbows/modded guns')
    .addHelpText('after', SHOOT_HELP)
    .requiredOption('--item <id>', 'ranged weapon item id')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file (tool data, charge, mode, ...)')
    .option('--ammo <id>', 'ammo item id to place in offhand/inventory (arrows, bolts)')
    .option('--ammo-count <n>', 'ammo stack size (default 64)', '64')
    .option('--target <uuid>', 'UUID of the entity to aim at (looks at its eyes; get it via find-entities)')
    .option('--direction <dir>', 'fixed facing when no target: north|south|east|west|up|down')
    .option('--hold <n>', 'ticks to hold right-click before release (0 = short tap)', '20')
    .option('--repeat <n>', 'full use cycles (crossbows need 2: load then fire)', '1')
    .option('--pos <x,y,z>', 'fake player position (default 5 blocks in front of target)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        item: string;
        count: string;
        nbt?: string;
        ammo?: string;
        ammoCount: string;
        target?: string;
        direction?: string;
        hold: string;
        repeat: string;
        pos?: string;
        dim?: string;
      }) => {
        const api = getApi();
        if (!opts.target && !opts.direction) {
          throw new Error('one of --target or --direction is required (pass exactly one)');
        }
        if (opts.target && opts.direction) {
          throw new Error('--target and --direction are mutually exclusive (pass exactly one)');
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const pos = opts.pos ? parsePos(opts.pos) : undefined;
        const r = await api.world.useItemHold(opts.item, {
          count: Number(opts.count),
          nbt: nbt as JsonNbt | undefined,
          ammo: opts.ammo,
          ammoCount: Number(opts.ammoCount),
          targetUuid: opts.target,
          direction: opts.direction as 'north' | 'south' | 'east' | 'west' | 'up' | 'down' | undefined,
          holdTicks: Number(opts.hold),
          repeat: Number(opts.repeat),
          playerPos: pos,
          dim: opts.dim,
        });
        outputJson(r);
        await api.close();
      },
    );

  cmd
    .command('use-item')
    .description('right-click (use) an item in air — calls Item.use without a block/entity target')
    .addHelpText('after', USE_ITEM_HELP)
    .requiredOption('--item <id>', 'item id to hold and use')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file')
    .option('--sneaking', 'simulate shift+right-click')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        item: string;
        count: string;
        nbt?: string;
        sneaking?: boolean;
        dim?: string;
      }) => {
        const api = getApi();
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.useItem(opts.item, {
          count: Number(opts.count),
          nbt: nbt as JsonNbt | undefined,
          sneaking: opts.sneaking,
          dim: opts.dim,
        });
        outputJson(r);
        await api.close();
      },
    );

  cmd
    .command('use')
    .description('right-click (use) a block with an item in hand — fires Fabric API UseBlockCallback + vanilla Block.onUse + Item.useOnBlock pipeline')
    .addHelpText('after', USE_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .requiredOption(
      '--face <dir>',
      'which face to click: up|down|north|south|east|west',
    )
    .option('--item <id>', 'item id to hold (omit for empty hand)')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file')
    .option('--sneaking', 'simulate shift+right-click')
    .option('--player-facing <dir>', 'which direction the player is facing: north|south|east|west (default: face.opposite)')
    .option('--gamemode <mode>', 'interaction game mode: survival (held item is consumed / containers drain properly) or creative (default)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        x: string;
        y: string;
        z: string;
        face: string;
        item?: string;
        count: string;
        nbt?: string;
        sneaking?: boolean;
        playerFacing?: string;
        gamemode?: string;
        dim?: string;
      }) => {
        const api = getApi();
        const pos = requirePos(opts, 'use');
        const face = opts.face.toLowerCase();
        if (!['up', 'down', 'north', 'south', 'east', 'west'].includes(face)) {
          throw new Error(`--face must be one of up|down|north|south|east|west, got: ${opts.face}`);
        }
        if (opts.playerFacing) {
          const pf = opts.playerFacing.toLowerCase();
          if (!['north', 'south', 'east', 'west'].includes(pf)) {
            throw new Error(`--player-facing must be one of north|south|east|west, got: ${opts.playerFacing}`);
          }
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const r = await api.world.useOnBlock(
          pos,
          face as 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
          {
            item: opts.item,
            count: Number(opts.count),
            nbt: nbt as JsonNbt | undefined,
            sneaking: opts.sneaking,
            playerFacing: opts.playerFacing as 'north' | 'south' | 'east' | 'west' | undefined,
            gamemode: opts.gamemode,
            dim: opts.dim,
          },
        );
        outputJson(r);
        await api.close();
      },
    );

  cmd
    .command('attack')
    .description('left-click (attack) a block — triggers onBlockBreakStart and breaks the block in creative mode')
    .addHelpText('after', ATTACK_HELP)
    .requiredOption('--x <n>', 'x coordinate (integer)')
    .requiredOption('--y <n>', 'y coordinate (integer)')
    .requiredOption('--z <n>', 'z coordinate (integer)')
    .requiredOption('--face <dir>', 'which face was hit: up|down|north|south|east|west')
    .option('--item <id>', 'item id to hold (omit for empty hand)')
    .option('--count <n>', 'stack size (default 1)', '1')
    .option('--nbt <json>', 'item NBT, or @file')
    .option('--armor <json>', 'armor stacks object, or @file (head/chest/legs/feet)')
    .option('--gamemode <mode>', 'interaction game mode: survival or creative (default creative)')
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (opts: {
        x: string;
        y: string;
        z: string;
        face: string;
        item?: string;
        count: string;
        nbt?: string;
        armor?: string;
        gamemode?: string;
        dim?: string;
      }) => {
        const api = getApi();
        const pos = requirePos(opts, 'attack');
        const face = opts.face.toLowerCase();
        if (!['up', 'down', 'north', 'south', 'east', 'west'].includes(face)) {
          throw new Error(`--face must be one of up|down|north|south|east|west, got: ${opts.face}`);
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const armor = opts.armor ? await parseJsonArg(opts.armor) : undefined;
        if (opts.gamemode && !['survival', 'creative'].includes(opts.gamemode)) {
          throw new Error(`--gamemode must be survival or creative, got: ${opts.gamemode}`);
        }
        const r = await api.world.attackBlock(
          pos,
          face as 'up' | 'down' | 'north' | 'south' | 'east' | 'west',
          {
            item: opts.item,
            count: Number(opts.count),
            nbt: nbt as JsonNbt | undefined,
            armor: armor as ArmorSpec | undefined,
            gamemode: opts.gamemode as 'survival' | 'creative' | undefined,
            dim: opts.dim,
          },
        );
        outputJson(r);
        await api.close();
      },
    );
}

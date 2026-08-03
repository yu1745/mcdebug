import { ATTACK_ENTITY_HELP, INTERACT_ENTITY_HELP } from './help-text.js';
import { parseJsonArg, outputJson } from './util.js';
export function registerInteractEntityCommand(cmd, getApi) {
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
        .action(async (opts) => {
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
            nbt: nbt,
            sneaking: opts.sneaking,
            playerFacing: opts.playerFacing,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
    cmd
        .command('attack-entity')
        .description('left-click (attack) an entity — Fabric AttackEntityCallback + PlayerEntity.attack')
        .addHelpText('after', ATTACK_ENTITY_HELP)
        .requiredOption('--uuid <uuid>', 'UUID of the target entity (from find-entities)')
        .option('--item <id>', 'item id to hold (omit for empty hand)')
        .option('--count <n>', 'stack size (default 1)', '1')
        .option('--nbt <json>', 'item NBT, or @file')
        .option('--armor <json>', 'armor stacks object, or @file (head/chest/legs/feet)')
        .option('--player-facing <dir>', 'player facing: north|south|east|west (default: south)')
        .option('--dim <id>', 'dimension id (default minecraft:overworld)')
        .action(async (opts) => {
        const api = getApi();
        if (opts.playerFacing) {
            const pf = opts.playerFacing.toLowerCase();
            if (!['north', 'south', 'east', 'west'].includes(pf)) {
                throw new Error(`--player-facing must be one of north|south|east|west, got: ${opts.playerFacing}`);
            }
        }
        const nbt = opts.nbt ? await parseJsonArg(opts.nbt) : undefined;
        const armor = opts.armor ? await parseJsonArg(opts.armor) : undefined;
        const r = await api.world.attackEntity(opts.uuid, {
            item: opts.item,
            count: Number(opts.count),
            nbt: nbt,
            armor: armor,
            playerFacing: opts.playerFacing,
            dim: opts.dim,
        });
        outputJson(r);
        await api.close();
    });
}

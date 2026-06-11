import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { CMD_HELP } from './help-text.js';
import { outputOneLineJson } from './util.js';

export function registerCmdCommand(parent: Command, getApi: () => DebugApi): void {
  parent
    .command('cmd <command...>')
    .description('run a Minecraft server command (e.g. /time set day)')
    .addHelpText('after', CMD_HELP)
    .option('--dim <id>', 'dimension id (default minecraft:overworld)')
    .action(
      async (commandParts: string[], opts: { dim?: string }) => {
        try {
          const api = getApi();
          // Join multi-word commands: `mcdebug cmd time set day` → "time set day"
          // Add leading "/" if not present.
          let command = commandParts.join(' ');
          if (!command.startsWith('/')) command = '/' + command;
          const r = await api.server.runCommand(command, { dim: opts.dim });
          outputOneLineJson(r);
          await api.close();
        } catch (e) {
          throw e;
        }
      },
    );
}

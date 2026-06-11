import type { Command } from 'commander';
import { DebugApi } from '../api.js';
import { DIMS_HELP, STATUS_HELP } from './help-text.js';
import { outputJson } from './util.js';

export function registerServerCommand(cmd: Command, getApi: () => DebugApi): void {
  cmd
    .command('status')
    .description('show server status (mcVersion, dims, players, tick)')
    .addHelpText('after', STATUS_HELP)
    .action(async () => {
      const api = getApi();
      const r = await api.server.status();
      outputJson(r);
      await api.close();
    });

  cmd
    .command('dims')
    .description('list loaded dimensions')
    .addHelpText('after', DIMS_HELP)
    .action(async () => {
      const api = getApi();
      const r = await api.server.listDimensions();
      outputJson(r);
      await api.close();
    });
}

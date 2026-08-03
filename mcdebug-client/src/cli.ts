#!/usr/bin/env node
import { Command } from 'commander';
import { DebugApi } from './api.js';
import { RpcClient, RpcClientOptions } from './client.js';
import { registerBeCommands } from './commands/be.js';
import { registerCmdCommand } from './commands/cmd.js';
import { registerCraftCommands } from './commands/craft.js';
import { registerEntityCommands } from './commands/entity.js';
import { registerFluidCommands } from './commands/fluid.js';
import { registerFixtureCommands } from './commands/fixture.js';
import { registerInteractEntityCommand } from './commands/interact-entity.js';
import { registerGuideCommand } from './commands/guide.js';
import { registerInvCommands } from './commands/inv.js';
import { registerJarCommand } from './commands/jar.js';
import { startRepl } from './commands/repl.js';
import { registerScreenCommands } from './commands/screen.js';
import { registerRedstoneCommands } from './commands/redstone.js';
import { registerReflectCommands } from './commands/reflect.js';
import { registerServerCommand } from './commands/server.js';
import { registerSnapshotCommands } from './commands/snapshot.js';
import { registerStorageCommands } from './commands/storage.js';
import { registerTraceCommands } from './commands/trace.js';
import { registerUseCommand } from './commands/use.js';
import { registerWaitCommand } from './commands/wait.js';
import { registerWorldCommands } from './commands/world.js';
import { handleError, outputJson } from './commands/util.js';
import { GLOBAL_HELP_AFTER, RAW_HELP, REPL_HELP } from './commands/help-text.js';
import { version } from './version.js';

const program = new Command();
program
  .name('mcdebug')
  .description('TypeScript CLI for the mcdebug Minecraft debug server mod')
  .version(version)
  .option('--port <n>', 'explicit port (overrides MCDEBUG_PORT and port file)')
  .option('--port-file <path>', 'explicit port file path')
  .option('--host <addr>', 'host (default 127.0.0.1)')
  .option('--timeout <ms>', 'connection timeout in ms', '5000');

program.addHelpText('after', GLOBAL_HELP_AFTER);

function buildClient(): RpcClient {
  const opts = program.opts<{ port?: string; portFile?: string; host?: string; timeout?: string }>();
  const clientOpts: RpcClientOptions = {
    host: opts.host ?? '127.0.0.1',
    port: opts.port ? Number(opts.port) : undefined,
    portFile: opts.portFile,
    timeoutMs: opts.timeout ? Number(opts.timeout) : undefined,
  };
  return new RpcClient(clientOpts);
}

const getApi = (): DebugApi => new DebugApi(buildClient());

registerServerCommand(program, getApi);
registerWorldCommands(program, getApi);
registerBeCommands(program, getApi);
registerInvCommands(program, getApi);
registerFluidCommands(program, getApi);
registerCraftCommands(program, getApi);
registerCmdCommand(program, getApi);
registerReflectCommands(program, getApi);
registerWaitCommand(program, getApi);
registerGuideCommand(program);
registerJarCommand(program);
registerEntityCommands(program, getApi);
registerInteractEntityCommand(program, getApi);
registerUseCommand(program, getApi);
registerStorageCommands(program, getApi);
registerSnapshotCommands(program, getApi);
registerTraceCommands(program, getApi);
registerScreenCommands(program, getApi);
registerRedstoneCommands(program, getApi);
registerFixtureCommands(program, getApi);

program
  .command('repl')
  .description('start an interactive REPL (dbg.DebugApi is available)')
  .addHelpText('after', REPL_HELP)
  .action(async () => {
    try {
      await startRepl(getApi());
    } catch (e) {
      handleError(e);
    }
  });

program
  .command('raw <method> [jsonParams]')
  .description('call an arbitrary JSON-RPC method; jsonParams is a JSON object string or @file')
  .addHelpText('after', RAW_HELP)
  .action(async (method: string, jsonParams?: string) => {
    try {
      const api = getApi();
      let params: unknown = undefined;
      if (jsonParams) {
        if (jsonParams.startsWith('@')) {
          const fs = await import('node:fs/promises');
          params = JSON.parse(await fs.readFile(jsonParams.slice(1), 'utf8'));
        } else {
          params = JSON.parse(jsonParams);
        }
      }
      const r = await api.rpc.call(method, params);
      outputJson(r);
      await api.close();
    } catch (e) {
      handleError(e);
    }
  });

(async () => {
  try {
    await program.parseAsync(process.argv);
  } catch (e) {
    handleError(e);
  }
})();

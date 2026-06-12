import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const CLI = resolve(__dirname, '../dist/cli.js');

function runRepl(input: string) {
  return spawnSync(process.execPath, [CLI, 'repl'], {
    input,
    encoding: 'utf8',
    env: {
      ...process.env,
      MCDEBUG_PORT: process.env.MCDEBUG_PORT ?? '25580',
      MCDEBUG_HOST: process.env.MCDEBUG_HOST ?? '127.0.0.1',
    },
  });
}

describe('repl', () => {
  it('exposes dbg inside evaluated expressions', () => {
    const result = runRepl('typeof dbg\n.exit\n');
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /"object"/);
    assert.doesNotMatch(result.stderr, /dbg is not defined/);
  });

  it('can execute DebugApi calls', () => {
    const result = runRepl('await dbg.server.status()\n.exit\n');
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /"mcVersion": "1\.20\.1"/);
    assert.match(result.stdout, /"modLoader": "fabric"/);
  });
});

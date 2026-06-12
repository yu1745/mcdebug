import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { Command } from 'commander';
import { registerWorldCommands } from '../src/commands/world.js';
import type { DebugApi } from '../src/api.js';
import type { BlockStateSpec, Pos } from '../src/types.js';

type SetBlockCall = {
  pos: Pos;
  block: string;
  state?: BlockStateSpec;
  opts?: { dim?: string; flags?: number };
};

async function parsePlace(args: string[]): Promise<SetBlockCall[]> {
  const calls: SetBlockCall[] = [];
  const program = new Command();
  program.exitOverride();
  program.configureOutput({
    writeOut: () => undefined,
    writeErr: () => undefined,
  });

  const fakeApi = {
    world: {
      setBlock: async (
        pos: Pos,
        block: string,
        state?: BlockStateSpec,
        opts?: { dim?: string; flags?: number },
      ) => {
        calls.push({ pos, block, state, opts });
        return true;
      },
    },
    close: async () => undefined,
  } as unknown as DebugApi;

  const oldLog = console.log;
  console.log = () => undefined;
  try {
    registerWorldCommands(program, () => fakeApi);
    await program.parseAsync(['node', 'mcdebug', 'place', ...args]);
  } finally {
    console.log = oldLog;
  }

  return calls;
}

describe('cli world commands', () => {
  it('place accepts a single --state value', async () => {
    const calls = await parsePlace([
      '--block',
      'minecraft:furnace',
      '--x',
      '1',
      '--y',
      '2',
      '--z',
      '3',
      '--state',
      'facing=west',
    ]);

    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0]?.state, {
      name: 'minecraft:furnace',
      props: { facing: 'west' },
    });
  });

  it('place accumulates repeated --state values', async () => {
    const calls = await parsePlace([
      '--block',
      'minecraft:oak_door',
      '--x',
      '1',
      '--y',
      '2',
      '--z',
      '3',
      '--state',
      'facing=east',
      '--state',
      'half=lower',
    ]);

    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0]?.state, {
      name: 'minecraft:oak_door',
      props: { facing: 'east', half: 'lower' },
    });
  });
});

import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerFixtureCommands(parent: Command, getApi: () => DebugApi): void;

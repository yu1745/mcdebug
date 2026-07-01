import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerStorageCommands(parent: Command, getApi: () => DebugApi): void;

import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerEntityCommands(cmd: Command, getApi: () => DebugApi): void;

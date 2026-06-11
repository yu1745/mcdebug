import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerBeCommands(cmd: Command, getApi: () => DebugApi): void;

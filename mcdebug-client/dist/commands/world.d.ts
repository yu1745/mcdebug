import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerWorldCommands(cmd: Command, getApi: () => DebugApi): void;

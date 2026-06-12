import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerCraftCommands(cmd: Command, getApi: () => DebugApi): void;

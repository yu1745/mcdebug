import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerServerCommand(cmd: Command, getApi: () => DebugApi): void;

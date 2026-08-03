import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerReflectCommands(cmd: Command, getApi: () => DebugApi): void;

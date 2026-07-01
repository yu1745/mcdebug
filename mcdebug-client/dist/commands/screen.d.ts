import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerScreenCommands(parent: Command, getApi: () => DebugApi): void;

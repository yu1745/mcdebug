import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerFluidCommands(parent: Command, getApi: () => DebugApi): void;

import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerUseCommand(cmd: Command, getApi: () => DebugApi): void;

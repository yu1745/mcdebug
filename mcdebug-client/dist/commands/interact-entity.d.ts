import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerInteractEntityCommand(cmd: Command, getApi: () => DebugApi): void;

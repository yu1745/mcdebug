import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerCmdCommand(parent: Command, getApi: () => DebugApi): void;

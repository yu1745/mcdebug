import type { Command } from 'commander';
import { DebugApi } from '../api.js';
export declare function registerInvCommands(parent: Command, getApi: () => DebugApi): void;

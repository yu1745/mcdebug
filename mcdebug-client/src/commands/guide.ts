import type { Command } from 'commander';
import { getGuideText } from './help-text.js';

export function registerGuideCommand(program: Command): void {
  program
    .command('guide [section]')
    .description(
      'show conceptual documentation (connection, port-discovery, file-syntax, errors, wait-grammar, conventions)',
    )
    .action(async (section?: string) => {
      console.log(getGuideText(section));
    });
}

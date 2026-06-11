import { getGuideText } from './help-text.js';
export function registerGuideCommand(program) {
    program
        .command('guide [section]')
        .description('show conceptual documentation (connection, port-discovery, file-syntax, errors, wait-grammar, conventions)')
        .action(async (section) => {
        console.log(getGuideText(section));
    });
}

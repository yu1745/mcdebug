#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const VERSION_RE = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;

const version = process.argv[2];
if (!version || !VERSION_RE.test(version)) {
  console.error('Usage: node scripts/set-version.mjs <version>');
  console.error('Example: node scripts/set-version.mjs 0.4.12');
  process.exit(1);
}

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

async function updateText(relativePath, updater) {
  const file = path.join(root, relativePath);
  const before = await readFile(file, 'utf8');
  const after = updater(before);
  if (after === before) return false;
  await writeFile(file, after);
  return true;
}

async function updatePackage(relativePath) {
  const file = path.join(root, relativePath);
  const pkg = JSON.parse(await readFile(file, 'utf8'));
  pkg.version = version;
  await writeFile(file, `${JSON.stringify(pkg, null, 2)}\n`);
}

await updateText('gradle.properties', (text) =>
  text.replace(/^mod_version=.*$/m, `mod_version=${version}`),
);

await updatePackage('mcdebug-client/package.json');

await updateText('mcdebug-client/src/version.ts', (text) =>
  text.replace(
    /^export const version = ['"].*['"];$/m,
    `export const version = '${version}';`,
  ),
);

const build = spawnSync('pnpm', ['run', 'build'], {
  cwd: path.join(root, 'mcdebug-client'),
  stdio: 'inherit',
  shell: process.platform === 'win32',
});

if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

console.log(`mcdebug version set to ${version}`);

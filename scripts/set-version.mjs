#!/usr/bin/env node
// 版本同步：gradle.properties (mod_version) + 根 package.json（npm 壳包）。
//
// 历史说明：TS 版 mcdebug-client（子目录包）停在 0.4.16 不再发版，
// 0.5.0 起由根目录 Kotlin jar 壳包接替（pnpm dlx github:yu1745/mcdebug 直接拉根）。
// 版本号同时是两代包的区分信号，因此这里不再更新 mcdebug-client/package.json。
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const VERSION_RE = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;

const version = process.argv[2];
if (!version || !VERSION_RE.test(version)) {
  console.error('Usage: node scripts/set-version.mjs <version>');
  console.error('Example: node scripts/set-version.mjs 0.5.0');
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

await updatePackage('package.json');

console.log(`mcdebug version set to ${version}`);
console.log('发布流程：./gradlew :cli:copyCliJar && node scripts/set-version.mjs <v> && git add -A && git commit && git tag v<v> && git push --tags');

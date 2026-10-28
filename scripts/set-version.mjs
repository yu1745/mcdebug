#!/usr/bin/env node
// 版本号唯一来源：gradle.properties 的 mod_version（mod jar、CLI manifest、Release 都由它派生）。
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

await updateText('gradle.properties', (text) =>
  text.replace(/^mod_version=.*$/m, `mod_version=${version}`),
);

console.log(`mcdebug version set to ${version}`);
console.log('发布流程见 AGENTS.md §10：./gradlew build → commit → git tag -a v<v> → push 分支和 tag（release.yml 上传 jar）');

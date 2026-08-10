#!/usr/bin/env node
// 壳脚本：一行透传 java -jar mcdebug-cli.jar。
// jar 固定名、与 package.json 同目录（进 git，随 tag 分发），
// pnpm dlx 拉包时 jar 已在包内，无需任何下载逻辑。
'use strict';

const { spawnSync } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');

const jar = path.join(__dirname, '..', 'mcdebug-cli.jar');

if (!fs.existsSync(jar)) {
  console.error(`error: ${jar} not found — the npm package is missing the CLI jar`);
  process.exit(1);
}

// 优先 JAVA_HOME，兜底 PATH（跑 MC 的开发机必有 JDK，但可能不在 PATH）。
const java = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', 'java' + (process.platform === 'win32' ? '.exe' : ''))
  : 'java';

const r = spawnSync(java, ['-jar', jar, ...process.argv.slice(2)], {
  stdio: 'inherit',
});

process.exit(r.status ?? 1);

import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { get } from 'node:https';
import { version as CLI_VERSION } from '../version.js';
import { JAR_HELP } from './help-text.js';
const OWNER = 'yu1745';
const REPO = 'mcdebug';
export function registerJarCommand(program) {
    program
        .command('jar')
        .description('download the mcdebug mod JAR from GitHub Releases')
        .addHelpText('after', JAR_HELP)
        .option('--version <ver>', 'download a specific version (default: same as CLI version)')
        .option('--latest', 'download the latest release instead of matching CLI version')
        .option('--output <path>', 'output file path (default: mcdebug-{version}.jar)')
        .action(async (opts) => {
        const ver = opts.latest ? await fetchLatestVersion() : (opts.version ?? CLI_VERSION);
        const fileName = opts.output ?? `mcdebug-${ver}.jar`;
        const assetId = await fetchAssetId(ver);
        const filePath = resolve(fileName);
        await mkdir(dirname(filePath), { recursive: true });
        await downloadAsset(assetId, ver, filePath);
        console.error(`Downloaded mcdebug-${ver}.jar → ${filePath}`);
    });
}
async function fetchLatestVersion() {
    const url = `https://api.github.com/repos/${OWNER}/${REPO}/releases/latest`;
    const data = await fetchJson(url);
    const tag = data.tag_name;
    return tag.startsWith('v') ? tag.slice(1) : tag;
}
async function fetchAssetId(ver) {
    const tag = ver.startsWith('v') ? ver : `v${ver}`;
    const url = `https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${tag}`;
    const data = await fetchJson(url);
    const asset = data.assets.find((a) => a.name === `mcdebug-${ver}.jar`);
    if (!asset) {
        const available = data.assets.map((a) => a.name).join(', ') || '(none)';
        throw new Error(`Asset mcdebug-${ver}.jar not found in release ${tag}.\nAvailable assets: ${available}`);
    }
    return asset.id;
}
function downloadAsset(assetId, ver, filePath) {
    return new Promise((resolve, reject) => {
        const url = `https://github.com/${OWNER}/${REPO}/releases/download/v${ver}/mcdebug-${ver}.jar`;
        const file = createWriteStream(filePath);
        get(url, (res) => {
            if (res.statusCode === 302 && res.headers.location) {
                // GitHub releases redirect to the actual CDN URL
                get(res.headers.location, (redirectRes) => {
                    if (redirectRes.statusCode !== 200) {
                        reject(new Error(`Download failed with status ${redirectRes.statusCode}`));
                        return;
                    }
                    redirectRes.pipe(file);
                    file.on('finish', () => {
                        file.close();
                        resolve();
                    });
                }).on('error', reject);
            }
            else if (res.statusCode === 200) {
                res.pipe(file);
                file.on('finish', () => {
                    file.close();
                    resolve();
                });
            }
            else {
                reject(new Error(`Download failed with status ${res.statusCode}`));
            }
        }).on('error', reject);
    });
}
function fetchJson(url) {
    return new Promise((resolve, reject) => {
        get(url, { headers: { 'User-Agent': 'mcdebug-cli' } }, (res) => {
            if (res.statusCode !== 200) {
                const chunks = [];
                res.on('data', (c) => chunks.push(c));
                res.on('end', () => {
                    reject(new Error(`GitHub API returned ${res.statusCode}: ${Buffer.concat(chunks).toString()}`));
                });
                return;
            }
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => {
                try {
                    resolve(JSON.parse(Buffer.concat(chunks).toString()));
                }
                catch {
                    reject(new Error('Failed to parse GitHub API response'));
                }
            });
        }).on('error', reject);
    });
}

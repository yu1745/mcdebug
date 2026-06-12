import { createWriteStream } from 'node:fs';
import { mkdir, unlink } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { dirname, resolve } from 'node:path';
import { version as CLI_VERSION } from '../version.js';
import { JAR_HELP } from './help-text.js';
const OWNER = 'yu1745';
const REPO = 'mcdebug';
const jarName = (ver) => `mcdebug-${ver}.jar`;
const shaName = (ver) => `mcdebug-${ver}.jar.sha256`;
const releaseUrl = (ver) => `https://github.com/${OWNER}/${REPO}/releases/download/v${ver}/${jarName(ver)}`;
const shaUrl = (ver) => `https://github.com/${OWNER}/${REPO}/releases/download/v${ver}/${shaName(ver)}`;
const LATEST_REDIRECT = `https://github.com/${OWNER}/${REPO}/releases/latest`;
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
        const fileName = opts.output ?? jarName(ver);
        const filePath = resolve(fileName);
        await mkdir(dirname(filePath), { recursive: true });
        const expectedSha = await tryFetchSha256(ver);
        const actualSha = await downloadAndHash(ver, filePath);
        console.error(`Computed SHA256: ${actualSha}`);
        if (expectedSha) {
            if (actualSha !== expectedSha) {
                await unlink(filePath).catch(() => { });
                throw new Error(`SHA256 mismatch — the downloaded file is not the expected artifact.\n` +
                    `  expected: ${expectedSha}\n` +
                    `  actual:   ${actualSha}\n` +
                    `The downloaded file has been deleted.`);
            }
            console.error(`✓ SHA256 verified`);
        }
        else {
            console.error(`(no .sha256 sidecar in release — verification skipped)`);
        }
    });
}
/**
 * Resolve the latest released version by following the `/releases/latest` 302
 * redirect. The final URL has shape `.../releases/tag/vX.Y.Z`; we extract the
 * tag from there. No GitHub API call, so no rate-limit exposure.
 */
async function fetchLatestVersion() {
    const res = await fetch(LATEST_REDIRECT, { redirect: 'follow' });
    // Drain body — we don't need the HTML
    await res.text();
    const m = res.url.match(/\/releases\/tag\/(v?[\d.+a-z-]+)$/i);
    if (!m)
        throw new Error(`Cannot parse version from ${res.url}`);
    const tag = m[1];
    return tag.startsWith('v') ? tag.slice(1) : tag;
}
/**
 * Try to fetch the `<jar>.sha256` sidecar that the release workflow uploads.
 * Returns null if the sidecar is missing (404) or malformed — older releases
 * predate the sidecar convention. Never throws.
 */
async function tryFetchSha256(ver) {
    let res;
    try {
        res = await fetch(shaUrl(ver));
    }
    catch {
        return null;
    }
    if (res.status === 404)
        return null;
    if (!res.ok)
        return null;
    const text = (await res.text()).trim();
    // Format: "<hash>   <filename>\n" (sha256sum output) or just "<hash>\n"
    const hash = text.split(/\s+/)[0];
    return /^[a-f0-9]{64}$/i.test(hash) ? hash.toLowerCase() : null;
}
/**
 * Download the JAR, stream it to disk, and compute its SHA256 in one pass.
 * Rejects if the response is HTML (GitHub returns 200 + text/html for missing
 * assets under the release URL, instead of a real 404) or any non-200 status.
 */
async function downloadAndHash(ver, filePath) {
    const res = await fetch(releaseUrl(ver));
    if (!res.ok) {
        throw new Error(`Download failed: HTTP ${res.status}`);
    }
    if (!res.body) {
        throw new Error('Download failed: empty response body');
    }
    const ct = res.headers.get('content-type') ?? '';
    if (ct.startsWith('text/') || ct.includes('html')) {
        throw new Error(`Refusing to save: server returned Content-Type "${ct}" ` +
            `(this is usually a 404 page, not the JAR). ` +
            `Check that version "${ver}" exists and the asset name matches "${jarName(ver)}".`);
    }
    const hash = createHash('sha256');
    const file = createWriteStream(filePath);
    try {
        await pipeline(Readable.fromWeb(res.body), async function* (source) {
            for await (const chunk of source) {
                hash.update(chunk);
                yield chunk;
            }
        }, file);
    }
    catch (e) {
        await unlink(filePath).catch(() => { });
        throw e;
    }
    return hash.digest('hex');
}

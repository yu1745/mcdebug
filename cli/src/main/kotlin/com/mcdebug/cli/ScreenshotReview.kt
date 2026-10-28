package com.mcdebug.cli

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64

/**
 * The human (or reviewing agent) side of golden screenshots: a self-contained HTML
 * page with expected / actual / diff next to each other, and an explicit accept
 * that replaces the baseline only when asked. Nothing here runs implicitly.
 */
object ScreenshotReview {
    /** Writes [report] atomically; images are embedded, so the page can be moved or attached. */
    fun writeReport(report: Path, expected: Path?, actual: Path, diff: Path?, result: ScreenshotComparisonResult) {
        val inputs = listOfNotNull(expected, actual).map { it.toRealPath() }
        val output = ScreenshotComparison.safeOutput(report, inputs)
        fun image(title: String, path: Path?): String {
            val body = if (path != null && Files.isRegularFile(path)) {
                "<img alt=\"${esc(title)}\" src=\"data:image/png;base64,${Base64.getEncoder().encodeToString(Files.readAllBytes(path))}\">"
            } else "<p class=\"missing\">(none)</p>"
            return "<figure><figcaption>${esc(title)}<br><code>${esc(path?.toString() ?: "")}</code></figcaption>$body</figure>"
        }
        val verdict = if (result.matches) "MATCH" else "MISMATCH (${result.toJson().get("reason").asString})"
        val html = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Screenshot review</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#1a1a1a;--muted:#666;--ok:#1a7f37;--bad:#cf222e}
@media (prefers-color-scheme:dark){:root{--bg:#111;--fg:#eee;--muted:#999;--ok:#3fb950;--bad:#f85149}}
body{background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif;margin:16px}
h1{font-size:18px}.ok{color:var(--ok)}.bad{color:var(--bad)}
.row{display:flex;flex-wrap:wrap;gap:16px}figure{margin:0;max-width:100%}
figcaption{color:var(--muted);margin-bottom:4px}img{max-width:100%;image-rendering:pixelated;border:1px solid var(--muted)}
pre{overflow:auto;background:rgba(127,127,127,.12);padding:8px}
</style></head><body>
<h1 class="${if (result.matches) "ok" else "bad"}">${esc(verdict)}</h1>
<p>Diff legend: red = different, dimmed = equal, blue = ignored. Accepting the actual image as the new baseline is a separate, explicit step (<code>mcdebug gui compare --accept</code>).</p>
<div class="row">${image("expected", expected)}${image("actual", actual)}${image("diff", diff)}</div>
<h2>Result</h2><pre>${esc(gson.toJson(result.toJson()))}</pre>
</body></html>
"""
        val temporary = Files.createTempFile(output.parent, ".mcdebug-report-", ".html.tmp")
        try {
            Files.writeString(temporary, html)
            ScreenshotComparison.safeOutput(output, inputs)
            Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * Explicitly replaces (or creates) the golden [expected] with [actual].
     * Returns `created` / `replaced` / `unchanged`.
     */
    fun accept(expected: Path, actual: Path): String {
        val source = actual.toRealPath()
        PngCodec.read(source) // never bless a file that is not a readable PNG
        val target = expected.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(target)) { "expected must not be a symbolic link: $expected" }
        val exists = Files.exists(target, NOFOLLOW_LINKS)
        require(!exists || Files.isRegularFile(target, NOFOLLOW_LINKS)) { "expected must be a regular file: $expected" }
        if (exists && Files.isSameFile(target, source)) return "unchanged"
        if (exists && Files.mismatch(target, source) == -1L) return "unchanged"
        val parent = target.parent ?: throw IllegalArgumentException("expected must name a file: $expected")
        val temporary = Files.createTempFile(parent, ".mcdebug-golden-", ".png.tmp")
        try {
            Files.copy(source, temporary, REPLACE_EXISTING)
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return if (exists) "replaced" else "created"
    }

    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

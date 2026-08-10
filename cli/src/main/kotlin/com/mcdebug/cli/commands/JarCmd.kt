package com.mcdebug.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 下载 mcdebug mod jar（GitHub Releases + sha256 校验）。 */
class JarCmd : CliktCommand(name = "jar", help = "download the mcdebug mod JAR from GitHub Releases (sha256-verified)") {
    private val latest by option("--latest", help = "download the latest release instead of matching CLI version").flag()
    private val output by option("--output", help = "output file path (default: mcdebug-{version}.jar)")

    override fun run() {
        val ver = if (latest) fetchLatestVersion() else cliVersion()
        val filePath = Path.of(output ?: "mcdebug-$ver.jar")
        filePath.toAbsolutePath().parent?.let { Files.createDirectories(it) }

        val expectedSha = tryFetchSha256(ver)
        val actualSha = downloadAndHash(ver, filePath)
        System.err.println("Computed SHA256: $actualSha")

        if (expectedSha != null) {
            if (actualSha != expectedSha) {
                runCatching { Files.deleteIfExists(filePath) }
                throw IllegalStateException(
                    "SHA256 mismatch — the downloaded file is not the expected artifact.\n" +
                        "  expected: $expectedSha\n" +
                        "  actual:   $actualSha\n" +
                        "The downloaded file has been deleted.",
                )
            }
            System.err.println("SHA256 verified")
        } else {
            System.err.println("(no .sha256 sidecar in release — verification skipped)")
        }
    }

    private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    private fun cliVersion(): String =
        JarCmd::class.java.`package`.implementationVersion
            ?: error("cannot determine CLI version (not built from a tagged release); use --latest")

    private fun get(url: String): String =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    private fun download(url: String, file: Path): String {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        if (response.statusCode() >= 400) throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        val digest = MessageDigest.getInstance("SHA-256")
        response.body().use { input ->
            Files.newOutputStream(file).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchLatestVersion(): String {
        val res = http.send(
            HttpRequest.newBuilder(URI.create("https://github.com/yu1745/mcdebug/releases/latest")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val m = Regex("/releases/tag/(v?[\\d.+a-z-]+)$", RegexOption.IGNORE_CASE).find(res.uri().toString())
        val tag = m?.groupValues?.get(1) ?: error("cannot parse latest version from ${res.uri()}")
        return tag.removePrefix("v")
    }

    private fun tryFetchSha256(ver: String): String? = try {
        val body = get("https://github.com/yu1745/mcdebug/releases/download/v$ver/mcdebug-$ver.jar.sha256").trim()
        body.substringBefore(" ").takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun downloadAndHash(ver: String, file: Path): String =
        download("https://github.com/yu1745/mcdebug/releases/download/v$ver/mcdebug-$ver.jar", file)
}

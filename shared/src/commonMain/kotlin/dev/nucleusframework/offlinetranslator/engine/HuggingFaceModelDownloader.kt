package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.domain.DownloadError
import dev.nucleusframework.offlinetranslator.domain.DownloadFailedException
import dev.nucleusframework.offlinetranslator.domain.DownloadLog
import dev.nucleusframework.offlinetranslator.platform.IoDispatcher
import dev.nucleusframework.offlinetranslator.platform.Platform
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Below this size, one connection is already fast enough that splitting isn't worth the complexity. */
internal const val CHUNKED_MIN_BYTES = 32L * 1_000_000
internal const val CHUNK_COUNT = 4

/**
 * HF's model repos resolve through a Xet-bridge reconstruction endpoint
 * (`*.cdn.hf.co/xet-bridge-*`), not a static blob — it dynamically reassembles the file from a
 * content-addressed chunk store per request rather than serving fixed bytes off disk. Concurrent
 * Range requests against that endpoint produced silently corrupt reassemblies in testing (SHA
 * mismatches that survived the per-chunk completeness check, i.e. wrong bytes, not missing ones)
 * even after multiple retries. Disabled until we understand that backend's Range semantics well
 * enough to trust it — single-connection sequential download (still Range-resumable) is the only
 * path in the meantime. The chunked implementation stays in place and covered by
 * [HuggingFaceModelDownloaderTest], gated behind this flag rather than deleted.
 */
private const val CHUNKING_ENABLED = false

@ContributesBinding(AppScope::class)
@Inject
class HuggingFaceModelDownloader(private val httpClient: HttpClient) : ModelDownloader {

    override suspend fun download(
        destPath: String,
        url: String,
        expectedSha256: String,
        expectedBytes: Long,
        onConnect: () -> Unit,
        onVerify: () -> Unit,
        onProgress: (bytes: Long, total: Long, speedBps: Long, log: DownloadLog?) -> Unit,
    ): DownloadedModel = withContext(IoDispatcher) {
        val existing = Platform.fileSize(destPath)
        val skipSha = expectedSha256.isBlank()
        if (existing == expectedBytes && expectedBytes > 0) {
            if (skipSha) {
                onProgress(existing, expectedBytes, 0, DownloadLog.AlreadyPresent)
                return@withContext DownloadedModel(destPath, "", existing)
            }
            onVerify()
            val sha = Platform.sha256(destPath)
            if (sha != null && sha.equals(expectedSha256, ignoreCase = true)) {
                onProgress(existing, expectedBytes, 0, DownloadLog.AlreadyPresent)
                return@withContext DownloadedModel(destPath, sha, existing)
            }
        }

        val partial = "$destPath.partial"
        val chunksSidecar = "$partial.chunks"
        onConnect()

        try {
            val useChunked = CHUNKING_ENABLED && expectedBytes >= CHUNKED_MIN_BYTES && supportsRangeRequests(url)
            if (useChunked) {
                try {
                    downloadChunked(partial, chunksSidecar, url, expectedBytes, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A chunk failed even though the probe said Range was supported (flaky
                    // mirror, a proxy that mishandles concurrent ranged requests, a mid-request
                    // Range that silently degrades to 200, etc). Restart clean over a single
                    // connection rather than risk merging inconsistent chunk data.
                    Platform.delete(chunksSidecar)
                    Platform.truncate(partial)
                    downloadSingleStream(partial, url, 0L, expectedBytes, onProgress)
                }
            } else {
                // A stale sidecar means the last attempt preallocated (and possibly
                // sparse-filled) this file for chunking — its reported size no longer reflects
                // how many bytes are actually real, so a sequential resume can't trust it.
                val hadChunkSidecar = Platform.exists(chunksSidecar)
                Platform.delete(chunksSidecar)
                val downloaded = if (hadChunkSidecar) {
                    Platform.delete(partial)
                    0L
                } else {
                    Platform.fileSize(partial)
                }
                downloadSingleStream(partial, url, downloaded, expectedBytes, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadFailedException) {
            throw e
        } catch (e: Exception) {
            throw DownloadFailedException(DownloadError.Interrupted)
        }

        onVerify()
        if (skipSha) {
            Platform.delete(destPath)
            if (!Platform.rename(partial, destPath)) {
                throw DownloadFailedException(DownloadError.InstallFailed)
            }
            return@withContext DownloadedModel(destPath, "", Platform.fileSize(destPath), createdByApp = true)
        }
        val sha = Platform.sha256(partial) ?: throw DownloadFailedException(DownloadError.ShaCompute)
        if (!sha.equals(expectedSha256, ignoreCase = true)) {
            Platform.delete(partial)
            throw DownloadFailedException(DownloadError.ShaMismatch)
        }
        Platform.delete(destPath)
        if (!Platform.rename(partial, destPath)) {
            throw DownloadFailedException(DownloadError.InstallFailed)
        }
        DownloadedModel(destPath, sha, Platform.fileSize(destPath), createdByApp = true)
    }

    private suspend fun supportsRangeRequests(url: String): Boolean = try {
        httpClient.prepareGet(url) {
            header(HttpHeaders.UserAgent, "EdgeTranslator/1.0")
            header(HttpHeaders.AcceptEncoding, "identity")
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response -> response.status.value == 206 }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun downloadSingleStream(
        partial: String,
        url: String,
        startDownloaded: Long,
        expectedBytes: Long,
        onProgress: (bytes: Long, total: Long, speedBps: Long, log: DownloadLog?) -> Unit,
    ) {
        var downloaded = startDownloaded
        httpClient.prepareGet(url) {
            header(HttpHeaders.UserAgent, "EdgeTranslator/1.0")
            header(HttpHeaders.AcceptEncoding, "identity")
            if (downloaded > 0) header(HttpHeaders.Range, "bytes=$downloaded-")
        }.execute { response ->
            val status = response.status.value
            if (status == 200 && downloaded > 0) {
                Platform.truncate(partial)
                downloaded = 0
            } else if (!response.status.isSuccess()) {
                throw DownloadFailedException(httpError(status))
            }
            val remaining = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val total = when {
                status == 206 && remaining != null -> downloaded + remaining
                remaining != null && downloaded == 0L -> remaining
                else -> expectedBytes
            }
            onProgress(downloaded, total, 0, DownloadLog.Transfer)
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            var lastTick = Platform.now()
            var windowBytes = 0L
            var lastLogAt = 0L
            while (!channel.isClosedForRead) {
                coroutineContext.ensureActive()
                val n = channel.readAvailable(buffer)
                if (n <= 0) continue
                Platform.writeAppend(partial, buffer, 0, n)
                downloaded += n
                windowBytes += n
                val now = Platform.now()
                val dt = now - lastTick
                if (dt >= 200) {
                    val speed = if (dt > 0) windowBytes * 1000 / dt else 0
                    val log = if (now - lastLogAt >= 1500) {
                        lastLogAt = now
                        DownloadLog.ReceivedMb((downloaded / 1_000_000).toInt(), (total / 1_000_000).toInt())
                    } else {
                        null
                    }
                    onProgress(downloaded, total, speed, log)
                    lastTick = now
                    windowBytes = 0
                }
            }
        }
    }

    /**
     * Splits `[0, expectedBytes)` into [CHUNK_COUNT] contiguous ranges and fetches them over
     * concurrent connections into [partial] (pre-sized via [Platform.preallocate] so every
     * chunk's [Platform.writeAt] lands within the file's extent). Per-chunk progress is flushed
     * to [chunksSidecar] so a cancelled/paused download resumes only the missing bytes of each
     * range instead of restarting the whole file.
     */
    internal suspend fun downloadChunked(
        partial: String,
        chunksSidecar: String,
        url: String,
        expectedBytes: Long,
        onProgress: (bytes: Long, total: Long, speedBps: Long, log: DownloadLog?) -> Unit,
    ) {
        Platform.preallocate(partial, expectedBytes)
        val boundaries = chunkBoundaries(expectedBytes)
        val chunkBytes = readChunkProgress(chunksSidecar, boundaries.size)
        val progressMutex = Mutex()
        var lastTick = Platform.now()
        var windowBytes = 0L
        var lastLogAt = 0L
        var lastSidecarFlush = 0L

        suspend fun reportChunkProgress(index: Int, deliveredNow: Int) {
            progressMutex.withLock {
                chunkBytes[index] += deliveredNow
                windowBytes += deliveredNow
                val downloaded = chunkBytes.sum()
                val now = Platform.now()
                val dt = now - lastTick
                if (dt >= 200) {
                    val speed = if (dt > 0) windowBytes * 1000 / dt else 0
                    val log = if (now - lastLogAt >= 1500) {
                        lastLogAt = now
                        DownloadLog.ReceivedMb((downloaded / 1_000_000).toInt(), (expectedBytes / 1_000_000).toInt())
                    } else {
                        null
                    }
                    onProgress(downloaded, expectedBytes, speed, log)
                    lastTick = now
                    windowBytes = 0
                }
                if (now - lastSidecarFlush >= 1000) {
                    lastSidecarFlush = now
                    writeChunkProgress(chunksSidecar, chunkBytes)
                }
            }
        }

        onProgress(chunkBytes.sum(), expectedBytes, 0, DownloadLog.Transfer)

        coroutineScope {
            boundaries.forEachIndexed { index, range ->
                val already = chunkBytes[index]
                val start = range.first + already
                if (start > range.last) return@forEachIndexed
                launch {
                    httpClient.prepareGet(url) {
                        header(HttpHeaders.UserAgent, "EdgeTranslator/1.0")
                        header(HttpHeaders.AcceptEncoding, "identity")
                        header(HttpHeaders.Range, "bytes=$start-${range.last}")
                    }.execute { response ->
                        if (response.status.value != 206) {
                            throw DownloadFailedException(httpError(response.status.value))
                        }
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(256 * 1024)
                        var offset = start
                        while (!channel.isClosedForRead) {
                            coroutineContext.ensureActive()
                            val n = channel.readAvailable(buffer)
                            if (n <= 0) continue
                            Platform.writeAt(partial, offset, buffer, 0, n)
                            offset += n
                            reportChunkProgress(index, n)
                        }
                        // The channel can close early (dropped connection, a proxy truncating a
                        // ranged response) without Ktor surfacing an exception — silently leaving
                        // a zero-filled gap from preallocate(). Only trust a chunk that delivered
                        // every byte of its requested range; anything short is a real failure.
                        if (offset != range.last + 1) {
                            throw DownloadFailedException(DownloadError.Interrupted)
                        }
                    }
                }
            }
        }
        Platform.delete(chunksSidecar)
    }

    private fun chunkBoundaries(expectedBytes: Long): List<LongRange> {
        val chunkSize = expectedBytes / CHUNK_COUNT
        return (0 until CHUNK_COUNT).map { i ->
            val start = i * chunkSize
            val end = if (i == CHUNK_COUNT - 1) expectedBytes - 1 else start + chunkSize - 1
            start..end
        }
    }

    private fun readChunkProgress(path: String, count: Int): LongArray {
        val result = LongArray(count)
        val text = Platform.readText(path) ?: return result
        text.lineSequence().forEach { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val idx = parts[0].toIntOrNull() ?: return@forEach
            val bytes = parts[1].toLongOrNull() ?: return@forEach
            if (idx in result.indices) result[idx] = bytes
        }
        return result
    }

    private fun writeChunkProgress(path: String, chunkBytes: LongArray) {
        Platform.writeText(path, chunkBytes.mapIndexed { i, b -> "$i=$b" }.joinToString("\n"))
    }

    private fun httpError(status: Int): DownloadError = when (status) {
        401, 403 -> DownloadError.HttpDenied(status)
        404 -> DownloadError.NotFound
        else -> DownloadError.Http(status)
    }
}

package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.platform.Platform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class HuggingFaceModelDownloaderTest {

    private val url = "https://example.test/model.bin"

    private fun tempDest(): String = Files.createTempDirectory("hf-dl-").toFile().resolve("model.bin").absolutePath

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine) { engine { addHandler(handler) } }

    private fun parseRange(range: String, contentLength: Int): Pair<Int, Int> {
        val spec = range.removePrefix("bytes=")
        val start = spec.substringBefore('-').toInt()
        val end = spec.substringAfter('-').toIntOrNull() ?: (contentLength - 1)
        return start to end
    }

    private fun MockRequestHandleScope.respondRange(content: ByteArray, start: Int, end: Int): HttpResponseData {
        val slice = content.copyOfRange(start, end + 1)
        return respond(
            slice,
            HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${content.size}"),
        )
    }

    private fun assertBytesEqual(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "size mismatch")
        assertTrue(expected.contentEquals(actual), "content mismatch")
    }

    @Test
    fun smallFileUsesSingleStreamPath() = runTest {
        val content = Random(1).nextBytes(1000)
        val requestRanges = mutableListOf<String?>()
        val client = mockClient { request ->
            requestRanges += request.headers[HttpHeaders.Range]
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, content.size.toString()))
        }
        val downloader = HuggingFaceModelDownloader(client)
        val dest = tempDest()
        val result = downloader.download(
            destPath = dest,
            url = url,
            expectedSha256 = sha256(content),
            expectedBytes = content.size.toLong(),
            onConnect = {},
            onVerify = {},
            onProgress = { _, _, _, _ -> },
        )
        assertEquals(sha256(content), result.sha256)
        assertBytesEqual(content, File(dest).readBytes())
        assertEquals(listOf<String?>(null), requestRanges)
    }

    // The tests below call downloadChunked() directly rather than through download() — chunking
    // is currently disabled in production (CHUNKING_ENABLED = false, see its doc comment) because
    // HF's Xet-bridge reconstruction endpoint produced silently corrupt reassemblies under
    // concurrent Range requests. This logic is otherwise correct and stays covered so it's ready
    // to re-enable once that backend's Range semantics are better understood.

    @Test
    fun chunkedDownloadReassemblesCorrectly() = runTest {
        val content = Random(2).nextBytes(CHUNKED_MIN_BYTES.toInt())
        val requestRanges = mutableListOf<String>()
        val lock = Mutex()
        val client = mockClient { request ->
            val range = request.headers[HttpHeaders.Range]
            requireNotNull(range) { "downloadChunked should never issue a rangeless request" }
            lock.withLock { requestRanges += range }
            val (start, end) = parseRange(range, content.size)
            respondRange(content, start, end)
        }
        val downloader = HuggingFaceModelDownloader(client)
        val dest = tempDest()
        val partial = "$dest.partial"
        downloader.downloadChunked(partial, "$partial.chunks", url, content.size.toLong()) { _, _, _, _ -> }
        assertBytesEqual(content, File(partial).readBytes())
        assertEquals(CHUNK_COUNT, requestRanges.size)
    }

    @Test
    fun aFailingChunkThrowsSoTheCallerCanFallBackToSingleStream() = runTest {
        val content = Random(4).nextBytes(CHUNKED_MIN_BYTES.toInt())
        val chunkSize = content.size / CHUNK_COUNT
        val failingChunkStart = chunkSize * 2
        val client = mockClient { request ->
            val range = requireNotNull(request.headers[HttpHeaders.Range])
            val (start, end) = parseRange(range, content.size)
            if (start == failingChunkStart) {
                respond(ByteArray(0), HttpStatusCode.InternalServerError)
            } else {
                respondRange(content, start, end)
            }
        }
        val downloader = HuggingFaceModelDownloader(client)
        val dest = tempDest()
        val partial = "$dest.partial"
        assertFails {
            downloader.downloadChunked(partial, "$partial.chunks", url, content.size.toLong()) { _, _, _, _ -> }
        }
    }

    @Test
    fun aChunkThatDeliversFewerBytesThanRequestedThrowsSoTheCallerCanFallBack() = runTest {
        val content = Random(6).nextBytes(CHUNKED_MIN_BYTES.toInt())
        val chunkSize = content.size / CHUNK_COUNT
        val truncatedChunkStart = chunkSize
        val client = mockClient { request ->
            val range = requireNotNull(request.headers[HttpHeaders.Range])
            val (start, end) = parseRange(range, content.size)
            if (start == truncatedChunkStart) {
                // Connection drops after half the requested range — still 206, still a
                // "successful" status, but short of what was asked for.
                val short = content.copyOfRange(start, start + (end - start + 1) / 2)
                respond(
                    short,
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${content.size}"),
                )
            } else {
                respondRange(content, start, end)
            }
        }
        val downloader = HuggingFaceModelDownloader(client)
        val dest = tempDest()
        val partial = "$dest.partial"
        assertFails {
            downloader.downloadChunked(partial, "$partial.chunks", url, content.size.toLong()) { _, _, _, _ -> }
        }
    }

    @Test
    fun chunkedResumeSkipsCompletedChunks() = runTest {
        val content = Random(5).nextBytes(CHUNKED_MIN_BYTES.toInt())
        val chunkSize = content.size / CHUNK_COUNT
        val dest = tempDest()
        val partial = "$dest.partial"
        val chunksSidecar = "$partial.chunks"
        Platform.preallocate(partial, content.size.toLong())
        // Pre-seed: chunk 0 fully downloaded, chunk 1 half downloaded, chunks 2 & 3 untouched.
        Platform.writeAt(partial, 0, content, 0, chunkSize)
        Platform.writeAt(partial, chunkSize.toLong(), content, chunkSize, chunkSize / 2)
        Platform.writeText(chunksSidecar, "0=$chunkSize\n1=${chunkSize / 2}")

        val requestedRanges = mutableListOf<Pair<Int, Int>>()
        val lock = Mutex()
        val client = mockClient { request ->
            val range = requireNotNull(request.headers[HttpHeaders.Range])
            val (start, end) = parseRange(range, content.size)
            lock.withLock { requestedRanges += start to end }
            respondRange(content, start, end)
        }
        val downloader = HuggingFaceModelDownloader(client)
        downloader.downloadChunked(partial, chunksSidecar, url, content.size.toLong()) { _, _, _, _ -> }
        assertBytesEqual(content, File(partial).readBytes())
        // Chunk 0 was already fully downloaded before this call, so its *original* full range
        // must never be re-requested. Chunk 1 must resume mid-chunk, not from its original start.
        assertTrue(requestedRanges.none { (start, end) -> start == 0 && end > 0 })
        assertTrue(requestedRanges.any { (start, _) -> start == chunkSize + chunkSize / 2 })
        assertTrue(requestedRanges.none { (start, _) -> start == chunkSize })
    }
}

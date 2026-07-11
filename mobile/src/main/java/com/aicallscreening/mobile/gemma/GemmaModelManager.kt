package com.aicallscreening.mobile.gemma

import android.content.Context
import android.util.Log
import com.aicallscreening.common.ScamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the on-device Gemma 4 E2B `.litertlm` model file: checks presence,
 * downloads it on demand, and exposes the resolved path.
 *
 * The model (~2.58 GB, Apache-2.0) is not bundled with the APK. For development
 * it can also be pushed manually, e.g.
 * `adb push gemma4_e2b.litertlm /data/local/tmp/` then copied into app storage,
 * or placed directly in the app's files dir.
 */
class GemmaModelManager(private val context: Context) {

    sealed interface DownloadState {
        data class InProgress(val fraction: Float) : DownloadState
        data class Completed(val file: File) : DownloadState
        data class Error(val throwable: Throwable) : DownloadState
    }

    val modelFile: File
        get() = File(context.filesDir, ScamConfig.GEMMA_MODEL_FILE)

    val isModelAvailable: Boolean
        get() = modelFile.exists() && modelFile.length() >= ScamConfig.GEMMA_MODEL_MIN_VALID_BYTES

    /**
     * Downloads the model, emitting progress. HuggingFace redirects to a CDN and
     * `HttpURLConnection` drops non-standard headers across redirects, so
     * redirects are followed manually.
     */
    fun downloadModel(): Flow<DownloadState> = flow {
        if (isModelAvailable) {
            emit(DownloadState.Completed(modelFile))
            return@flow
        }
        emit(DownloadState.InProgress(0f))
        try {
            var currentUrl = ScamConfig.GEMMA_MODEL_URL
            var redirectCount = 0
            var connection: HttpURLConnection
            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 30_000
                }
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect with no Location header")
                    connection.disconnect()
                    if (++redirectCount > 10) error("Too many redirects")
                    currentUrl = location
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    val body = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    error("HTTP $code while downloading model: $body")
                }
                break
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val tempFile = File(context.filesDir, "${ScamConfig.GEMMA_MODEL_FILE}.tmp")
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            emit(DownloadState.InProgress(downloaded.toFloat() / totalBytes))
                        }
                    }
                }
            }
            if (!tempFile.renameTo(modelFile)) {
                error("Could not finalize model file")
            }
            Log.i(TAG, "Model downloaded to ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            emit(DownloadState.Completed(modelFile))
        } catch (error: Exception) {
            Log.e(TAG, "Model download failed", error)
            emit(DownloadState.Error(error))
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "GemmaModelManager"
    }
}

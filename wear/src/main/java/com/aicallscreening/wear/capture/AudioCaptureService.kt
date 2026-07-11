package com.aicallscreening.wear.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aicallscreening.common.AudioConfig
import com.aicallscreening.common.DataLayerPaths
import com.aicallscreening.wear.BuildConfig
import com.aicallscreening.wear.R
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class AudioCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private val bytesSent = AtomicLong(0)
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // Runtime override wins over the build-time flag so emulator testing can
        // switch sources without a rebuild. Falls back to BuildConfig otherwise.
        val useDebugClip = intent?.takeIf { it.hasExtra(EXTRA_USE_DEBUG_CLIP) }
            ?.getBooleanExtra(EXTRA_USE_DEBUG_CLIP, BuildConfig.USE_DEBUG_AUDIO_CLIP)
            ?: BuildConfig.USE_DEBUG_AUDIO_CLIP
        startCapture(useDebugClip)
        return START_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        _state.value = CaptureState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(useDebugClip: Boolean) {
        captureJob?.cancel()
        bytesSent.set(0)
        _state.update {
            CaptureState(
                isCapturing = true,
                status = "Connecting",
                usingDebugClip = useDebugClip,
            )
        }

        captureJob = serviceScope.launch {
            try {
                val phoneNodeId = resolvePhoneNodeId()
                if (phoneNodeId == null) {
                    _state.update { it.copy(status = "No phone connected", isCapturing = false) }
                    stopSelf()
                    return@launch
                }

                val channel = channelClient.openChannel(phoneNodeId, DataLayerPaths.AUDIO).await()
                val outputStream = channelClient.getOutputStream(channel).await()
                _state.update { it.copy(status = "Streaming") }
                Log.i(TAG, "Opened audio channel to $phoneNodeId (debugClip=$useDebugClip)")

                if (useDebugClip) {
                    streamDebugClip(outputStream)
                } else {
                    streamMicrophone(outputStream)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Capture failed", error)
                _state.update { it.copy(status = "Error: ${error.message}", isCapturing = false) }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun resolvePhoneNodeId(): String? {
        val nodes = nodeClient.connectedNodes.await()
        return nodes.firstOrNull()?.id
    }

    private suspend fun streamMicrophone(outputStream: OutputStream) {
        if (!hasRecordAudioPermission()) {
            _state.update { it.copy(status = "Microphone permission denied", isCapturing = false) }
            outputStream.close()
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            _state.update {
                it.copy(status = "Mic unavailable (bad audio config)", isCapturing = false)
            }
            outputStream.close()
            return
        }
        val bufferSize = maxOf(minBuffer, AudioConfig.CHUNK_SIZE_BYTES)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        // On emulators without host-audio input the recorder never initializes;
        // surface that instead of silently streaming nothing.
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${recorder.state})")
            recorder.release()
            _state.update {
                it.copy(status = "Mic unavailable — enable host audio input", isCapturing = false)
            }
            outputStream.close()
            return
        }

        val buffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not start (state=${recorder.recordingState})")
                _state.update { it.copy(status = "Mic failed to start", isCapturing = false) }
                return
            }
            _state.update { it.copy(status = "Listening") }
            while (captureJob?.isActive == true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) {
                    val message = readErrorMessage(read)
                    Log.e(TAG, "AudioRecord.read error: $message ($read)")
                    _state.update { it.copy(status = "Mic error: $message", isCapturing = false) }
                    break
                }
                if (read == 0) {
                    continue
                }
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                outputStream.write(chunk)
                outputStream.flush()
                bytesSent.addAndGet(read.toLong())
                val level = computeInputLevel(buffer, read)
                _state.update { it.copy(bytesSent = bytesSent.get(), inputLevel = level) }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
            outputStream.close()
            Log.i(TAG, "Microphone stream finished. bytes=${bytesSent.get()}")
        }
    }

    private fun readErrorMessage(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "invalid operation"
        AudioRecord.ERROR_BAD_VALUE -> "bad value"
        AudioRecord.ERROR_DEAD_OBJECT -> "recorder died"
        else -> "code $code"
    }

    /**
     * Peak amplitude of a PCM16 chunk normalized to 0..100 so the watch UI can
     * confirm the app is actually hearing input (not just the system mic icon).
     */
    private fun computeInputLevel(buffer: ByteArray, bytesRead: Int): Int {
        var peak = 0
        var i = 0
        val end = bytesRead - 1
        while (i < end) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val magnitude = kotlin.math.abs(sample)
            if (magnitude > peak) {
                peak = magnitude
            }
            i += 2
        }
        return (peak * 100 / Short.MAX_VALUE).coerceIn(0, 100)
    }

    private suspend fun streamDebugClip(outputStream: OutputStream) {
        val buffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
        var chunks = 0L
        try {
            while (captureJob?.isActive == true) {
                resources.openRawResource(R.raw.scam_demo_clip).use { inputStream ->
                    var read = inputStream.read(buffer)
                    while (captureJob?.isActive == true && read > 0) {
                        val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                        outputStream.write(chunk)
                        outputStream.flush()
                        bytesSent.addAndGet(read.toLong())
                        _state.update { it.copy(bytesSent = bytesSent.get()) }
                        chunks++
                        if (chunks % 30L == 1L) {
                            Log.i(TAG, "→ phone audio (debug clip): chunk #$chunks, ${bytesSent.get()} B total")
                        }
                        kotlinx.coroutines.delay(AudioConfig.CHUNK_DURATION_MS.toLong())
                        read = inputStream.read(buffer)
                    }
                }
            }
        } finally {
            outputStream.close()
            Log.i(TAG, "Debug clip stream finished. bytes=${bytesSent.get()}")
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_body))
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_CAPTURE,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_CAPTURE = "audio_capture"
        private const val NOTIFICATION_ID = 3001

        /** Optional boolean intent extra to override BuildConfig.USE_DEBUG_AUDIO_CLIP at runtime. */
        const val EXTRA_USE_DEBUG_CLIP = "extra_use_debug_clip"

        private val _state = MutableStateFlow(CaptureState())
        val state: StateFlow<CaptureState> = _state.asStateFlow()

        fun start(context: Context, useDebugClip: Boolean? = null) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                if (useDebugClip != null) {
                    putExtra(EXTRA_USE_DEBUG_CLIP, useDebugClip)
                }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioCaptureService::class.java))
        }

        fun markScamAlert() {
            _state.update { it.copy(isScamAlert = true, status = "SCAM ALERT") }
        }
    }
}

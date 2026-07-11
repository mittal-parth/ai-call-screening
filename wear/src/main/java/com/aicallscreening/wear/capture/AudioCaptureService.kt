package com.aicallscreening.wear.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
        startCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        _state.value = CaptureState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture() {
        captureJob?.cancel()
        bytesSent.set(0)
        _state.update {
            CaptureState(
                isCapturing = true,
                status = "Connecting",
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
                Log.i(TAG, "Opened audio channel to $phoneNodeId")

                if (BuildConfig.USE_DEBUG_AUDIO_CLIP) {
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
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, AudioConfig.CHUNK_SIZE_BYTES)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        val buffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
        recorder.startRecording()
        try {
            while (captureJob?.isActive == true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    break
                }
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                outputStream.write(chunk)
                outputStream.flush()
                bytesSent.addAndGet(read.toLong())
                _state.update { it.copy(bytesSent = bytesSent.get()) }
            }
        } finally {
            recorder.stop()
            recorder.release()
            outputStream.close()
            Log.i(TAG, "Microphone stream finished. bytes=${bytesSent.get()}")
        }
    }

    private suspend fun streamDebugClip(outputStream: OutputStream) {
        val buffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
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

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_CAPTURE = "audio_capture"
        private const val NOTIFICATION_ID = 3001

        private val _state = MutableStateFlow(CaptureState())
        val state: StateFlow<CaptureState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java)
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

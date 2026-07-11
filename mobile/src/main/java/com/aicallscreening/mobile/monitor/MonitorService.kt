package com.aicallscreening.mobile.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aicallscreening.common.AnalyzerEngine
import com.aicallscreening.common.DataLayerPaths
import com.aicallscreening.common.ScamVerdict
import com.aicallscreening.mobile.MainActivity
import com.aicallscreening.mobile.R
import com.aicallscreening.mobile.analysis.AnalyzerRouter
import com.google.android.gms.wearable.ChannelClient
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
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class MonitorState(
    val isMonitoring: Boolean = false,
    val connectionStatus: String = "Idle",
    val bytesReceived: Long = 0,
    val latestVerdict: String? = null,
    val latestReason: String? = null,
    val alertSent: Boolean = false,
    val engine: String = "cloud",
    val modelStatus: String? = null,
)

class MonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelClient by lazy { Wearable.getChannelClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    private val analyzerRouter by lazy {
        AnalyzerRouter(
            context = applicationContext,
            scope = serviceScope,
            onHighRisk = ::handleHighRiskAlert,
        )
    }
    private val bytesReceived = AtomicLong(0)
    private var observeJob: Job? = null
    private var routerStarted = false
    private val audioChannelJob = AtomicReference<Job?>(null)
    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path != DataLayerPaths.AUDIO) {
                return
            }
            Log.i(TAG, "Audio channel opened from ${channel.nodeId}")
            audioChannelJob.getAndSet(null)?.cancel()
            val job = serviceScope.launch {
                readAudioChannel(channel)
            }
            audioChannelJob.set(job)
        }

        override fun onChannelClosed(
            channel: ChannelClient.Channel,
            closeReason: Int,
            appSpecificErrorCode: Int,
        ) {
            if (channel.path == DataLayerPaths.AUDIO) {
                Log.i(TAG, "Audio channel closed: reason=$closeReason")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        channelClient.registerChannelCallback(channelCallback)
        createNotificationChannels()
        observeJob = serviceScope.launch {
            analyzerRouter.state.collect { routerState ->
                _state.update {
                    it.copy(
                        connectionStatus = routerState.status,
                        latestVerdict = routerState.latestVerdict?.risk?.name?.lowercase(),
                        latestReason = routerState.latestVerdict?.reason ?: routerState.transcript,
                        alertSent = routerState.alertTriggered || it.alertSent,
                        engine = if (routerState.engine == AnalyzerEngine.ON_DEVICE) "on-device" else "cloud",
                        modelStatus = routerState.modelStatus,
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID_MONITOR, buildMonitorNotification())
        if (intent?.action == ACTION_DOWNLOAD_MODEL) {
            analyzerRouter.downloadModel()
            return START_NOT_STICKY
        }
        _state.update { it.copy(isMonitoring = true, connectionStatus = "Waiting for audio") }
        if (!routerStarted) {
            routerStarted = true
            analyzerRouter.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        channelClient.unregisterChannelCallback(channelCallback)
        audioChannelJob.getAndSet(null)?.cancel()
        observeJob?.cancel()
        analyzerRouter.stop()
        routerStarted = false
        serviceScope.cancel()
        _state.value = MonitorState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun readAudioChannel(channel: ChannelClient.Channel) {
        val inputStream: InputStream = channelClient.getInputStream(channel).await()
        val buffer = ByteArray(3200)
        bytesReceived.set(0)
        _state.update { it.copy(connectionStatus = "Streaming audio") }

        try {
            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) {
                    break
                }
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                bytesReceived.addAndGet(read.toLong())
                _state.update { it.copy(bytesReceived = bytesReceived.get()) }
                analyzerRouter.onAudioChunk(chunk)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Audio channel read failed", error)
        } finally {
            inputStream.close()
            Log.i(TAG, "Audio channel finished. bytes=${bytesReceived.get()}")
        }
    }

    private fun handleHighRiskAlert(verdict: ScamVerdict) {
        Log.w(TAG, "High scam risk detected: ${verdict.reason}")
        showScamNotification(verdict.reason)
        serviceScope.launch {
            sendWatchAlert(verdict.reason)
        }
    }

    private suspend fun sendWatchAlert(reason: String) {
        val nodes = nodeClient.connectedNodes.await()
        val payload = reason.toByteArray()
        for (node in nodes) {
            messageClient.sendMessage(node.id, DataLayerPaths.ALERT, payload).await()
            Log.i(TAG, "Sent ${DataLayerPaths.ALERT} to ${node.displayName}")
        }
    }

    private fun showScamNotification(reason: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.scam_alert_title))
            .setContentText(reason.ifBlank { getString(R.string.scam_alert_body) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_ALERT, notification)
    }

    private fun buildMonitorNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_body))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITOR,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.monitor_channel_description)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Scam alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_MONITOR = "monitor"
        private const val CHANNEL_ALERT = "scam_alert"
        private const val NOTIFICATION_ID_MONITOR = 1001
        private const val NOTIFICATION_ID_ALERT = 1002
        private const val ACTION_DOWNLOAD_MODEL = "com.aicallscreening.mobile.action.DOWNLOAD_MODEL"

        private val _state = MutableStateFlow(MonitorState())
        val state: StateFlow<MonitorState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }

        fun downloadOfflineModel(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_DOWNLOAD_MODEL
            }
            context.startForegroundService(intent)
        }
    }
}

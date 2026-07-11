package com.aicallscreening.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aicallscreening.common.DataLayerPaths
import com.aicallscreening.wear.capture.AudioCaptureService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class CallListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            DataLayerPaths.INCOMING_CALL -> handleIncomingCall()
            DataLayerPaths.ALERT -> handleScamAlert(String(messageEvent.data, Charsets.UTF_8))
            DataLayerPaths.STOP_CAPTURE -> handleStopCapture()
            else -> Log.d(TAG, "Ignoring message path: ${messageEvent.path}")
        }
    }

    private fun handleIncomingCall() {
        Log.i(TAG, "Incoming call message received")
        createChannels()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_START, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.incoming_call_title))
            .setContentText(getString(R.string.incoming_call_body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_INCOMING, notification)
    }

    private fun handleScamAlert(reason: String) {
        Log.w(TAG, "Scam alert received: $reason")
        AudioCaptureService.markScamAlert()
        vibrateAlert()
        createChannels()
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.scam_alert_title))
            .setContentText(reason.ifBlank { getString(R.string.scam_alert_body) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ALERT, notification)
    }

    private fun handleStopCapture() {
        Log.i(TAG, "Stop capture message received")
        AudioCaptureService.stop(this)
    }

    private fun vibrateAlert() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = getSystemService(VibratorManager::class.java)
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 400, 200, 400), -1)
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH,
            ),
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
        const val EXTRA_AUTO_START = "auto_start_capture"
        private const val TAG = "CallListenerService"
        private const val CHANNEL_INCOMING = "incoming_call"
        private const val CHANNEL_ALERT = "scam_alert"
        private const val NOTIFICATION_INCOMING = 2001
        private const val NOTIFICATION_ALERT = 2002
    }
}

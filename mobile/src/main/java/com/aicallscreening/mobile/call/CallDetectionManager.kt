package com.aicallscreening.mobile.call

import android.content.Context
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.aicallscreening.common.DataLayerPaths
import com.aicallscreening.mobile.monitor.MonitorService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CallDetectionManager(
    private val context: Context,
) {
    private var scopeJob = SupervisorJob()
    private var scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val telephonyManager =
        context.getSystemService(TelephonyManager::class.java)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    private var callback: TelephonyCallback? = null
    private var lastNotifiedState: Int = TelephonyManager.CALL_STATE_IDLE
    private var autoMonitoringActive = false

    fun start() {
        if (callback != null) {
            return
        }

        scopeJob = SupervisorJob()
        scope = CoroutineScope(scopeJob + Dispatchers.IO)

        val listener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallState(state)
            }
        }
        callback = listener
        telephonyManager.registerTelephonyCallback(context.mainExecutor, listener)
        Log.i(TAG, "Telephony callback registered")
    }

    fun stop() {
        callback?.let {
            telephonyManager.unregisterTelephonyCallback(it)
            callback = null
        }
        scopeJob.cancel()
    }

    private fun handleCallState(state: Int) {
        if (state == lastNotifiedState) {
            return
        }
        lastNotifiedState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "Incoming call ringing")
                autoMonitoringActive = true
                scope.launch {
                    notifyWatchIncomingCall()
                    MonitorService.start(context)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Call active (offhook)")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (autoMonitoringActive) {
                    Log.i(TAG, "Call ended — stopping auto monitoring")
                    autoMonitoringActive = false
                    scope.launch {
                        notifyWatchStopCapture()
                    }
                    MonitorService.stop(context)
                }
            }
            else -> {
                Log.w(TAG, "Unhandled call state: $state")
            }
        }
    }

    private suspend fun notifyWatchIncomingCall() {
        sendMessageToWatchNodes(DataLayerPaths.INCOMING_CALL, byteArrayOf())
    }

    private suspend fun notifyWatchStopCapture() {
        sendMessageToWatchNodes(DataLayerPaths.STOP_CAPTURE, byteArrayOf())
    }

    private suspend fun sendMessageToWatchNodes(path: String, payload: ByteArray) {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch nodes for $path message")
            return
        }

        for (node in nodes) {
            messageClient.sendMessage(node.id, path, payload).await()
            Log.i(TAG, "Sent $path to ${node.displayName}")
        }
    }

    companion object {
        private const val TAG = "CallDetectionManager"
    }
}

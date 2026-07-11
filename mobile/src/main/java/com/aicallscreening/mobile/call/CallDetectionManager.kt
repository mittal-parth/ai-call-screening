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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val telephonyManager =
        context.getSystemService(TelephonyManager::class.java)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    private var callback: TelephonyCallback? = null
    private var lastNotifiedState: Int = TelephonyManager.CALL_STATE_IDLE

    fun start() {
        if (callback != null) {
            return
        }

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
    }

    private fun handleCallState(state: Int) {
        if (state == lastNotifiedState) {
            return
        }
        lastNotifiedState = state

        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK,
            -> {
                Log.i(TAG, "Incoming/active call detected: state=$state")
                scope.launch {
                    notifyWatchIncomingCall()
                    MonitorService.start(context)
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Call ended")
            }
            else -> {
                Log.w(TAG, "Unhandled call state: $state")
            }
        }
    }

    private suspend fun notifyWatchIncomingCall() {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.w(TAG, "No connected watch nodes for incoming_call message")
            return
        }

        for (node in nodes) {
            messageClient.sendMessage(
                node.id,
                DataLayerPaths.INCOMING_CALL,
                byteArrayOf(),
            ).await()
            Log.i(TAG, "Sent ${DataLayerPaths.INCOMING_CALL} to ${node.displayName}")
        }
    }

    companion object {
        private const val TAG = "CallDetectionManager"
    }
}

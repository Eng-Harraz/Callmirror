package com.example.callmirror

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            @Suppress("DEPRECATION")
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Log.d(TAG, "Phone state changed: $stateStr, number: $incomingNumber")

            if (stateStr != null) {
                // If TransmitterService is already running in foreground, dispatch to it
                if (TransmitterService.isRunning) {
                    val serviceIntent = Intent(context, TransmitterService::class.java).apply {
                        action = TransmitterService.ACTION_SEND_CALL_STATE
                        putExtra(TransmitterService.EXTRA_STATE, stateStr)
                        putExtra(TransmitterService.EXTRA_NUMBER, incomingNumber)
                    }
                    try {
                        context.startService(serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send to service: ${e.message}")
                    }
                    return
                }

                // Fallback: Even if ForegroundService is killed by OS, send the payload directly!
                val prefs = context.getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
                val currentMode = prefs.getInt("app_mode", 0)
                val isTransmitterMode = currentMode == 0 || (currentMode == 2 && prefs.getInt("cloud_role", 0) == 0)
                val receiverIp = prefs.getString("receiver_ip", "") ?: ""
                val receiverPort = prefs.getInt("receiver_port", 8080)
                val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""

                if (isTransmitterMode && (receiverIp.isNotBlank() || pairingCode.isNotBlank())) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            when (stateStr) {
                                TelephonyManager.EXTRA_STATE_RINGING -> {
                                    var callerName: String? = null
                                    if (!incomingNumber.isNullOrBlank()) {
                                        callerName = ContactUtils.getContactName(context, incomingNumber)
                                    }
                                    val payload = CallPayload(
                                        status = CallPayload.STATUS_RINGING,
                                        number = incomingNumber ?: "Unknown",
                                        name = callerName ?: "Unknown Caller"
                                    )
                                    if (receiverIp.isNotBlank()) {
                                        sendSocketPayload(receiverIp, receiverPort, payload)
                                    }
                                    if (pairingCode.isNotBlank()) {
                                        CloudMirrorManager(context, {}).sendPayload(payload)
                                    }
                                }
                                TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                                    val payload = CallPayload(status = CallPayload.STATUS_ENDED)
                                    if (receiverIp.isNotBlank()) {
                                        sendSocketPayload(receiverIp, receiverPort, payload)
                                    }
                                    if (pairingCode.isNotBlank()) {
                                        CloudMirrorManager(context, {}).sendPayload(payload)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Direct send failed: ${e.message}")
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private fun sendSocketPayload(ip: String, port: Int, payload: CallPayload) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "Receiver direct sending payload to $ip:$port: ${payload.toJson()}")
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 3000)
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
            writer.println(payload.toJson())
            writer.flush()
            Log.d(TAG, "Direct payload sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending payload direct: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (ignored: Exception) {}
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}

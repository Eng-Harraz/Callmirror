package com.example.callmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class ReceiverService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var serverSocket: ServerSocket? = null
    private lateinit var prefs: SharedPreferences
    private var overlayManager: OverlayCallManager? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    companion object {
        const val TAG = "ReceiverService"
        const val CHANNEL_ID = "receiver_service_channel"
        const val ALERT_CHANNEL_ID = "receiver_call_alert_channel"
        const val NOTIFICATION_ID = 2001
        const val ALERT_NOTIFICATION_ID = 2002

        const val ACTION_START = "com.example.callmirror.RECEIVER_START"
        const val ACTION_STOP = "com.example.callmirror.RECEIVER_STOP"
        const val ACTION_DISMISS = "com.example.callmirror.DISMISS_ALERT"

        var isRunning = false
            private set
        var lastTransmitterIp: String? = null
    }

    private var cloudMirrorManager: CloudMirrorManager? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        overlayManager = OverlayCallManager(
            context = applicationContext,
            onDismissRequested = {
                dismissCurrentAlert()
            }
        )
        initVibrator()
        createNotificationChannels()
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            cloudMirrorManager?.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_DISMISS) {
            dismissCurrentAlert()
            return START_STICKY
        }

        val isRemoteMode = prefs.getInt("app_mode", 0) == 2
        val enableReceiverCloud = prefs.getBoolean("enable_receiver_cloud", false)
        val receiverCloudCode = prefs.getString("receiver_cloud_code", "") ?: ""
        val cloudPairingCode = prefs.getString("cloud_pairing_code", "") ?: ""
        
        val activePairingCode = if (isRemoteMode) cloudPairingCode else (if (enableReceiverCloud) receiverCloudCode else "")

        if (activePairingCode.isNotBlank() && cloudMirrorManager == null) {
            Log.d(TAG, "Starting CloudMirrorManager for simultaneous cloud receiving with code: $activePairingCode")
            cloudMirrorManager = CloudMirrorManager(
                context = applicationContext,
                onMessageReceived = { payload ->
                    Log.d(TAG, "Receiver got Cloud message: ${payload.status}")
                    processPayload(payload)
                }
            )
            cloudMirrorManager?.connect(activePairingCode)
        }

        val port = prefs.getInt("listen_port", 8080)

        val notification = buildForegroundNotification(port)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            if (!isRunning || serverSocket == null || serverSocket?.isClosed == true) {
                isRunning = true
                startServer(port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receiver startForeground error: ${e.message}")
            isRunning = false
        }

        return START_STICKY
    }

    private fun startServer(port: Int) {
        scope.launch {
            try {
                if (serverSocket == null || serverSocket?.isClosed == true) {
                    serverSocket = ServerSocket(port)
                    Log.d(TAG, "Server socket successfully listening on port $port")
                }

                while (isRunning) {
                    val clientSocket: Socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.w(TAG, "accept() temporary error: ${e.message}")
                            delay(500)
                            continue
                        } else {
                            break
                        }
                    }

                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server exception on port $port: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val remoteIp = socket.inetAddress.hostAddress
            if (!remoteIp.isNullOrBlank()) {
                lastTransmitterIp = remoteIp
                prefs.edit().putString("last_transmitter_ip", remoteIp).apply()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val message = reader.readLine()
            Log.d(TAG, "Received message from $remoteIp (length: ${message?.length ?: 0})")

            if (!message.isNullOrBlank()) {
                val payload = CallPayload.fromJson(message)
                if (payload != null) {
                    processPayload(payload)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun processPayload(payload: CallPayload) {
        when (payload.status) {
            CallPayload.STATUS_RINGING -> {
                Log.d(TAG, "Incoming Call Triggered: ${payload.name} (${payload.number})")

                // Play system ringtone loudly via STREAM_MUSIC or STREAM_RING
                triggerRingtoneAndVibrate()

                // Display compact heads-up overlay banner
                val canDrawOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this)
                } else {
                    true
                }

                if (canDrawOverlay) {
                    overlayManager?.showOverlay(payload.name, payload.number)
                }

                showHeadsUpNotification(payload.name, payload.number)
            }
            CallPayload.STATUS_ENDED -> {
                Log.d(TAG, "Call ended or dismissed")
                dismissCurrentAlert()
            }
        }
    }

    @Synchronized
    private fun dismissCurrentAlert() {
        stopRingtoneAndVibrate()
        overlayManager?.dismissOverlay()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(ALERT_NOTIFICATION_ID)
        sendBroadcast(Intent(CallAlertActivity.ACTION_DISMISS_ALERT).setPackage(packageName))
    }

    @Synchronized
    private fun triggerRingtoneAndVibrate() {
        val soundEnabled = prefs.getBoolean("enable_sound", true)
        val vibrationEnabled = prefs.getBoolean("enable_vibration", true)

        if (soundEnabled) {
            try {
                // If already playing cleanly, do not start a duplicate instance!
                if (mediaPlayer?.isPlaying == true || currentRingtone?.isPlaying == true) {
                    Log.d(TAG, "Ringtone is already actively playing, skipping duplicate start")
                    return
                }
                stopRingtone()

                var ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_RINGTONE)
                if (ringtoneUri == null) {
                    ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }

                var playedSuccessfully = false
                try {
                    val player = MediaPlayer()
                    player.setDataSource(applicationContext, ringtoneUri)
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .build()
                    )
                    player.isLooping = true
                    player.prepare()
                    player.start()
                    mediaPlayer = player
                    playedSuccessfully = true
                    Log.d(TAG, "Ringtone started via MediaPlayer")
                } catch (mpException: Exception) {
                    Log.w(TAG, "MediaPlayer failed (${mpException.message}), falling back to RingtoneManager")
                }

                if (!playedSuccessfully) {
                    val ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                    if (ringtone != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ringtone.audioAttributes = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setLegacyStreamType(AudioManager.STREAM_RING)
                                .build()
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = true
                        }
                        ringtone.play()
                        currentRingtone = ringtone
                        Log.d(TAG, "Ringtone started via RingtoneManager fallback")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing ringtone: ${e.message}")
            }
        }

        if (vibrationEnabled) {
            try {
                val pattern = longArrayOf(0, 1000, 800, 1000, 800)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering vibration: ${e.message}")
            }
        }
    }

    @Synchronized
    private fun stopRingtone() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (ignored: Exception) {}
        mediaPlayer = null

        try {
            currentRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
        } catch (ignored: Exception) {}
        currentRingtone = null
    }

    @Synchronized
    private fun stopRingtoneAndVibrate() {
        stopRingtone()
        try {
            vibrator?.cancel()
        } catch (ignored: Exception) {}
    }

    private fun showHeadsUpNotification(name: String?, number: String?) {
        val stopIntent = Intent(this, ReceiverService::class.java).apply {
            action = ACTION_DISMISS
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            99,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val fullScreenIntent = Intent(this, CallAlertActivity::class.java).apply {
            putExtra(CallAlertActivity.EXTRA_CALLER_NAME, name)
            putExtra(CallAlertActivity.EXTRA_CALLER_NUMBER, number)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alertNotification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(name ?: "Incoming Call (Hotspot)")
            .setContentText(number ?: "Incoming Voice Call")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Silence", dismissPendingIntent)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(ALERT_NOTIFICATION_ID, alertNotification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Call Mirror Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the socket server listening for incoming calls"
            }

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Call Mirror Incoming Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when an incoming call is mirrored"
                setBypassDnd(true)
                enableVibration(false)
                setSound(null, null)
            }

            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(alertChannel)
        }
    }

    private fun buildForegroundNotification(port: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ReceiverService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 CallMirror: استقبال المكالمات قيد التشغيل")
            .setContentText("التطبيق نشط في الخلفية لاستقبال تنبيهات المكالمات والإشعارات.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("التطبيق نشط ويستمع عبر المنفذ $port لاستقبال تنبيهات المكالمات والإشعارات الواردة من جهازك الآخر."))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف الاستقبال", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopRingtoneAndVibrate()
        overlayManager?.dismissOverlay()
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.example.callmirror

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.callmirror.databinding.ActivityCallAlertBinding

class CallAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallAlertBinding

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS_ALERT) {
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val ACTION_DISMISS_ALERT = "com.example.callmirror.DISMISS_ALERT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turnScreenOnAndShowOnKeyguard()

        binding = ActivityCallAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown via Hotspot"
        val callerNumber = intent.getStringExtra(EXTRA_CALLER_NUMBER) ?: "No Number"

        binding.tvCallerName.text = callerName
        binding.tvCallerNumber.text = callerNumber

        binding.btnDismissCall.setOnClickListener {
            val stopIntent = Intent(this, ReceiverService::class.java).apply {
                action = ReceiverService.ACTION_DISMISS
            }
            startService(stopIntent)
            finish()
        }

        ContextCompat.registerReceiver(
            this,
            dismissReceiver,
            IntentFilter(ACTION_DISMISS_ALERT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun turnScreenOnAndShowOnKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(dismissReceiver)
        } catch (ignored: Exception) {}
    }
}

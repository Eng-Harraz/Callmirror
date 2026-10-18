package com.example.callmirror

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.callmirror.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var currentMode = MODE_TRANSMITTER

    companion object {
        const val MODE_TRANSMITTER = 0
        const val MODE_RECEIVER = 1
        const val MODE_REMOTE = 2

        const val PREFS_KEY_MODE = "app_mode"
        const val PREFS_KEY_RECEIVER_IP = "receiver_ip"
        const val PREFS_KEY_RECEIVER_PORT = "receiver_port"
        const val PREFS_KEY_LISTEN_PORT = "listen_port"
        const val PREFS_KEY_SOUND = "enable_sound"
        const val PREFS_KEY_VIBRATION = "enable_vibration"
        const val PREFS_KEY_PAIRING_CODE = "cloud_pairing_code"
        const val PREFS_KEY_CLOUD_ROLE = "cloud_role" // 0: Transmitter, 1: Receiver
        const val PREFS_KEY_MIRROR_NOTIFICATIONS = "mirror_notifications"
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
            updateUiState()
        } else {
            Toast.makeText(this, "Required permissions not granted.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        currentMode = prefs.getInt(PREFS_KEY_MODE, MODE_TRANSMITTER)

        setupListeners()
        loadSavedPreferences()
        updateUiState()
        showLegalDisclaimerIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshLocalIp()
        updateUiState()
    }

    private fun setupListeners() {
        binding.tabLayoutMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = tab?.position ?: MODE_TRANSMITTER
                prefs.edit().putInt(PREFS_KEY_MODE, currentMode).apply()
                updateUiState()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.tabLayoutMode.getTabAt(currentMode)?.select()

        binding.btnToggleService.setOnClickListener {
            toggleActiveService()
        }

        binding.btnTestMirror.setOnClickListener {
            sendTestCallSignal()
        }

        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_SOUND, isChecked).apply()
        }

        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_VIBRATION, isChecked).apply()
        }

        binding.switchEnableCloudTransmit.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_cloud_transmit", isChecked).apply()
            binding.layoutCloudTransmitCodeBox.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchEnableCloudReceive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_receiver_cloud", isChecked).apply()
            binding.layoutReceiverCloudCode.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnGenerateCode.setOnClickListener {
            val randomLetters = (1..4).map { ('A'..'Z').random() }.joinToString("")
            val randomDigits = (1000..9999).random()
            val newCode = "CALL-$randomLetters$randomDigits"
            binding.etPairingCode.setText(newCode)
            saveInputs()
            Toast.makeText(this, "Generated safe code: $newCode", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            if (code.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("CallMirror Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedPreferences() {
        val detectedClientIp = NetworkUtils.getConnectedHotspotClientIp()
        val savedIp = prefs.getString(PREFS_KEY_RECEIVER_IP, null) ?: detectedClientIp ?: "10.48.54.3"
        val savedPort = prefs.getInt(PREFS_KEY_RECEIVER_PORT, 8080)
        
        // Split saved IP string across the 4 fields
        val ipList = savedIp.split("[,;\\s]+".toRegex()).filter { it.isNotBlank() }
        binding.etIpAddress1.setText(ipList.getOrNull(0) ?: savedIp)
        binding.etIpAddress2.setText(ipList.getOrNull(1) ?: "")
        binding.etIpAddress3.setText(ipList.getOrNull(2) ?: "")
        binding.etIpAddress4.setText(ipList.getOrNull(3) ?: "")

        binding.etPort.setText(savedPort.toString())

        val savedCode = prefs.getString(PREFS_KEY_PAIRING_CODE, "CALL77")
        binding.etPairingCode.setText(savedCode)

        val enableCloudTransmit = prefs.getBoolean("enable_cloud_transmit", true)
        binding.switchEnableCloudTransmit.isChecked = enableCloudTransmit
        binding.layoutCloudTransmitCodeBox.visibility = if (enableCloudTransmit) View.VISIBLE else View.GONE

        binding.switchSound.isChecked = prefs.getBoolean(PREFS_KEY_SOUND, true)
        binding.switchVibration.isChecked = prefs.getBoolean(PREFS_KEY_VIBRATION, true)

        val enableRecCloud = prefs.getBoolean("enable_receiver_cloud", false)
        binding.switchEnableCloudReceive.isChecked = enableRecCloud
        binding.layoutReceiverCloudCode.visibility = if (enableRecCloud) View.VISIBLE else View.GONE
        binding.etReceiverCloudCode.setText(prefs.getString("receiver_cloud_code", savedCode))
    }

    private fun refreshLocalIp() {
        val localIp = NetworkUtils.getLocalIpAddress(this)
        if (localIp != null) {
            binding.tvDeviceIp.text = "IP: $localIp"
        } else {
            binding.tvDeviceIp.text = "IP: Offline / AP Mode"
        }
    }

    private fun getCombinedIps(): String {
        return listOf(
            binding.etIpAddress1.text.toString().trim(),
            binding.etIpAddress2.text.toString().trim(),
            binding.etIpAddress3.text.toString().trim(),
            binding.etIpAddress4.text.toString().trim()
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun saveInputs() {
        val combinedIps = getCombinedIps()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 8080
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        val recCloudCode = binding.etReceiverCloudCode.text.toString().trim().uppercase()

        val editor = prefs.edit()
        editor.putString(PREFS_KEY_PAIRING_CODE, code)
        editor.putString("receiver_cloud_code", recCloudCode.ifBlank { code })
        editor.putBoolean("enable_cloud_transmit", binding.switchEnableCloudTransmit.isChecked)
        editor.putBoolean("enable_receiver_cloud", binding.switchEnableCloudReceive.isChecked)

        if (currentMode == MODE_TRANSMITTER) {
            editor.putString(PREFS_KEY_RECEIVER_IP, combinedIps)
            editor.putInt(PREFS_KEY_RECEIVER_PORT, port)
        } else if (currentMode == MODE_RECEIVER) {
            editor.putInt(PREFS_KEY_LISTEN_PORT, port)
        }
        editor.apply()
    }

    private fun updateUiState() {
        refreshLocalIp()

        when (currentMode) {
            MODE_TRANSMITTER -> {
                binding.tvModeHeader.text = "Transmitter Mode (Hotspot Phone)"
                binding.tvModeDescription.text = "Runs on your hotspot phone. Detects incoming calls & WhatsApp and mirrors them to up to 4 receivers locally and/or over Cloud."
                binding.tilIpAddress.visibility = View.VISIBLE
                binding.tilPort.visibility = View.VISIBLE
                binding.layoutCloudSettings.visibility = View.VISIBLE
                binding.layoutReceiverToggles.visibility = View.GONE
                binding.btnTestMirror.visibility = View.VISIBLE
                binding.btnOverlayPermission.visibility = View.GONE

                val isRunning = TransmitterService.isRunning
                if (isRunning) {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                    binding.tvStatusTitle.text = "Transmitter Service Active"
                    binding.tvStatusSubtitle.text = "Forwarding calls & notifications (Hotspot & Cloud)"
                    binding.btnToggleService.text = "Stop Transmitter Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
                } else {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_amber)
                    binding.tvStatusTitle.text = "Transmitter Stopped"
                    binding.tvStatusSubtitle.text = "Press start to begin monitoring calls & messages"
                    binding.btnToggleService.text = "Start Transmitter Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
                }
            }
            MODE_RECEIVER -> {
                binding.tvModeHeader.text = "Receiver Mode (Primary Phone / Tablet)"
                binding.tvModeDescription.text = "Runs on your primary phone or tablet. Listens for incoming calls from hotspot and/or Cloud with full-screen overlay."
                binding.tilIpAddress.visibility = View.GONE
                binding.tilPort.visibility = View.VISIBLE
                binding.layoutCloudSettings.visibility = View.GONE
                binding.layoutReceiverToggles.visibility = View.VISIBLE
                binding.btnTestMirror.visibility = View.GONE

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val hasOverlay = Settings.canDrawOverlays(this)
                    binding.btnOverlayPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE
                }

                val isRunning = ReceiverService.isRunning
                val listenPort = prefs.getInt(PREFS_KEY_LISTEN_PORT, 8080)
                if (isRunning) {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                    binding.tvStatusTitle.text = "Receiver Service Active"
                    val isCloudActive = prefs.getBoolean("enable_receiver_cloud", false)
                    binding.tvStatusSubtitle.text = if (isCloudActive) "Listening on port $listenPort & Cloud" else "Listening on port $listenPort"
                    binding.btnToggleService.text = "Stop Receiver Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
                } else {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_amber)
                    binding.tvStatusTitle.text = "Receiver Stopped"
                    binding.tvStatusSubtitle.text = "Press start to begin listening"
                    binding.btnToggleService.text = "Start Receiver Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
                }
            }
        }
    }

    private fun toggleActiveService() {
        saveInputs()

        if (currentMode == MODE_TRANSMITTER) {
            if (TransmitterService.isRunning) {
                val intent = Intent(this, TransmitterService::class.java).apply {
                    action = TransmitterService.ACTION_STOP
                }
                startService(intent)
            } else {
                checkAndRequestTransmitterPermissions()
            }
        } else {
            if (ReceiverService.isRunning) {
                val intent = Intent(this, ReceiverService::class.java).apply {
                    action = ReceiverService.ACTION_STOP
                }
                startService(intent)
            } else {
                checkAndRequestReceiverPermissions()
            }
        }

        binding.root.postDelayed({ updateUiState() }, 300)
    }

    private fun checkAndRequestTransmitterPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } else {
            val intent = Intent(this, TransmitterService::class.java).apply {
                action = TransmitterService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Transmitter service started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestReceiverPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant 'Draw over other apps' overlay permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Receiver service started", Toast.LENGTH_SHORT).show()
    }

    private fun sendTestCallSignal() {
        saveInputs()
        val ipRaw = getCombinedIps()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 8080
        val isCloudEnabled = binding.switchEnableCloudTransmit.isChecked
        val pairingCode = binding.etPairingCode.text.toString().trim()

        val targetIps = if (ipRaw.isNotBlank()) {
            ipRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        } else {
            emptyList()
        }

        if (targetIps.isEmpty() && (!isCloudEnabled || pairingCode.isBlank())) {
            Toast.makeText(this, "Enter Receiver IP or enable Cloud Pairing Code", Toast.LENGTH_SHORT).show()
            return
        }

        val destinations = mutableListOf<String>()
        if (targetIps.isNotEmpty()) destinations.add(targetIps.joinToString(", "))
        if (isCloudEnabled && pairingCode.isNotBlank()) destinations.add("Cloud ($pairingCode)")

        Toast.makeText(this, "Testing: ${destinations.joinToString(" + ")}...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val payload = CallPayload(
                status = CallPayload.STATUS_RINGING,
                number = "+1 (555) 019-2834",
                name = "CallMirror Test Call"
            )

            var anySuccess = false
            var lastError = ""

            // Send to Cloud first if enabled
            if (isCloudEnabled && pairingCode.isNotBlank()) {
                try {
                    val cloudManager = CloudMirrorManager(this@MainActivity)
                    cloudManager.connect(pairingCode)
                    cloudManager.sendPayload(payload)
                    anySuccess = true
                } catch (e: Exception) {
                    lastError = "Cloud error: ${e.message}"
                }
            }

            // Send to local IPs
            targetIps.forEach { ip ->
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), 2500)
                    val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
                    writer.println(payload.toJson())
                    writer.flush()
                    socket.close()
                    anySuccess = true
                } catch (e: Exception) {
                    if (!anySuccess) {
                        lastError = "Local IP ($ip) failed: ${e.message ?: "Unreachable"}"
                    }
                }
            }

            runOnUiThread {
                if (anySuccess) {
                    val successMsg = if (isCloudEnabled && targetIps.isNotEmpty()) {
                        "Test sent! (Cloud sent, check receiver)"
                    } else if (isCloudEnabled) {
                        "Test sent to Cloud successfully! 🚀"
                    } else {
                        "Test signal sent to local IP successfully! 🚀"
                    }
                    Toast.makeText(this@MainActivity, successMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed: $lastError", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLegalDisclaimerIfNeeded() {
        val hasAccepted = prefs.getBoolean("eula_accepted_v1", false)
        if (hasAccepted) return

        val eulaMessage = """
⚖️ شروط الاستخدام وإخلاء المسؤولية القانونية:

هذا التطبيق مصمم ومخصص حصرياً كأداة شخصية للمستخدم لمزامنة المكالمات والإشعارات بين أجهزته الخاصة فقط.

وفقاً لقانون مكافحة جرائم تقنية المعلومات (رقم 175 لسنة 2018) وقوانين حماية البيانات الشخصية:
1. يُحظر تماماً تثبيت هذا التطبيق على جهاز أي شخص آخر بهدف مراقبته أو التصنت عليه دون علمه وموافقته الصريحة.
2. استخدامك لهذا التطبيق يعد إقراراً رسمياً بأنك المالك الوحيد والقانوني للجهازين المستخدمين.
3. المطور لا يتحمل أي مسؤولية قانونية أو جنائية أو مدنية ناتجة عن إساءة استخدام التطبيق في انتهاك خصوصية الآخرين.
4. التطبيق يطبق سياسة (Zero-Log) ولا يخزن أي بيانات شخصية، والبيانات مشفرة بالكامل بين جهازيك.

بالضغط على "أوافق وألتزم"، فإنك توافق على هذه الشروط وتتحمل المسؤولية القانونية الكاملة عن استخدامك.
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("إشعار قانوني هام (EULA)")
            .setMessage(eulaMessage)
            .setCancelable(false)
            .setPositiveButton("أوافق وألتزم") { dialog, _ ->
                prefs.edit().putBoolean("eula_accepted_v1", true).apply()
                dialog.dismiss()
            }
            .setNegativeButton("رفض وخروج") { _, _ ->
                finishAffinity()
            }
            .show()
    }
}


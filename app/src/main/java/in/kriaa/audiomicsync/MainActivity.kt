// Copyright (c) 2026 Kriaa
// SPDX-License-Identifier: MIT

package `in`.kriaa.audiomicsync

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : AppCompatActivity(), RecorderService.StatusListener {

    companion object {
        private const val TAG = "AudioMicSync/Main"
        private const val REQ_PERMISSIONS = 101
    }

    private var service: RecorderService? = null
    private var bound = false

    private lateinit var tvRecordingState: TextView
    private lateinit var tvUdpStatus: TextView
    private lateinit var tvPhoneIp: TextView
    private lateinit var tvPcIp: TextView
    private lateinit var btnRefreshPhoneIp: Button
    private lateinit var spinnerMic: Spinner
    private lateinit var btnRefreshMics: Button
    private lateinit var spinnerSampleRate: Spinner
    private lateinit var tvLastFile: TextView
    private lateinit var tvTransferStatus: TextView
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvLog: TextView

    private var orderedDevices: List<AudioDeviceInfo?> = emptyList()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "Service connected")
            service = (binder as RecorderService.ServiceBinder).getService()
            bound = true
            service?.setListener(this@MainActivity)
            refreshUiFromService()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Service disconnected")
            bound = false
            service = null
        }
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setContentView(R.layout.activity_main)
        supportActionBar?.title = "AudioMicSync"

        tvRecordingState = findViewById(R.id.tv_recording_state)
        tvUdpStatus      = findViewById(R.id.tv_udp_status)
        tvPhoneIp        = findViewById(R.id.tv_phone_ip)
        tvPcIp           = findViewById(R.id.tv_pc_ip)
        btnRefreshPhoneIp = findViewById(R.id.btn_refresh_phone_ip)
        spinnerMic       = findViewById(R.id.spinner_mic)
        btnRefreshMics   = findViewById(R.id.btn_refresh_mics)
        spinnerSampleRate = findViewById(R.id.spinner_sample_rate)
        tvLastFile       = findViewById(R.id.tv_last_file)
        tvTransferStatus = findViewById(R.id.tv_transfer_status)
        btnCopyLog       = findViewById(R.id.btn_copy_log)
        btnClearLog      = findViewById(R.id.btn_clear_log)
        tvLog            = findViewById(R.id.tv_log)

        refreshPhoneIp()
        setupSampleRateSpinner()
        refreshMics()
        btnRefreshPhoneIp.setOnClickListener { refreshPhoneIp() }
        btnRefreshMics.setOnClickListener { refreshMics() }
        btnCopyLog.setOnClickListener { copyLog() }
        btnClearLog.setOnClickListener { clearLog() }
        updatePcIpDisplay()

        findViewById<Button>(R.id.btn_test_start).setOnClickListener {
            Log.d(TAG, "Test Start button pressed")
            service?.simulatePacket("""{"cmd":"record_start","take":"TEST_01"}""")
        }
        findViewById<Button>(R.id.btn_test_stop).setOnClickListener {
            Log.d(TAG, "Test Stop button pressed")
            service?.simulatePacket("""{"cmd":"record_stop"}""")
        }

        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart — starting and binding service")
        val intent = Intent(this, RecorderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop — unbinding service")
        if (bound) {
            service?.setListener(null)
            unbindService(serviceConnection)
            bound = false
        }
    }

    // -----------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
            Log.d(TAG, "RECORD_AUDIO not granted — will request")
        } else {
            Log.d(TAG, "RECORD_AUDIO already granted")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
                Log.d(TAG, "POST_NOTIFICATIONS not granted — will request")
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS already granted")
            }
        }

        if (needed.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $needed")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        permissions.forEachIndexed { i, perm ->
            val granted = grantResults[i] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission result: $perm → ${if (granted) "GRANTED" else "DENIED"}")
        }

        // Restart service so it picks up notification permission if it was just granted
        if (bound) {
            service?.setListener(null)
            unbindService(serviceConnection)
            bound = false
        }
        val intent = Intent(this, RecorderService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // -----------------------------------------------------------------
    // Options menu
    // -----------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { showSettingsDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    // -----------------------------------------------------------------
    // StatusListener
    // -----------------------------------------------------------------

    override fun onStateChanged(state: RecorderService.State, takeName: String) {
        Log.d(TAG, "State changed: $state  take=$takeName")
        runOnUiThread {
            val (text, color) = when (state) {
                RecorderService.State.IDLE      -> "Idle — waiting for trigger"   to getColor(R.color.state_idle)
                RecorderService.State.RECORDING -> "● RECORDING: $takeName"       to getColor(R.color.state_recording)
                RecorderService.State.UPLOADING -> "↑ Uploading: $takeName"       to getColor(R.color.state_uploading)
            }
            tvRecordingState.text = text
            tvRecordingState.setTextColor(color)
            service?.lastFile?.takeIf { it.isNotEmpty() }?.let { tvLastFile.text = it }
        }
    }

    override fun onLogAdded(entry: String) {
        runOnUiThread { refreshLog() }
    }

    override fun onTransferProgress(percent: Int) {
        runOnUiThread {
            tvTransferStatus.text = if (percent < 100) "Uploading… $percent%" else ""
        }
    }

    // -----------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------

    private fun setupSampleRateSpinner() {
        val rates = listOf("44100", "48000")
        spinnerSampleRate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, rates
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val saved = prefs().getInt(RecorderService.PREF_SAMPLE_RATE, 48000).toString()
        spinnerSampleRate.setSelection(rates.indexOf(saved).coerceAtLeast(0))

        spinnerSampleRate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val rate = rates[pos].toInt()
                Log.d(TAG, "Sample rate selected: $rate")
                prefs().edit().putInt(RecorderService.PREF_SAMPLE_RATE, rate).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun refreshMics() {
        val am = getSystemService(AudioManager::class.java)
        val all = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        Log.d(TAG, "Input devices found: ${all.size}")
        all.forEach { Log.d(TAG, "  device id=${it.id} type=${it.type} name=${it.productName}") }

        val usb    = all.filter { isUsbDevice(it) }
        val others = all.filter { !isUsbDevice(it) && it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
        orderedDevices = listOf(null) + usb + others

        val labels = orderedDevices.map { d -> d?.let { deviceLabel(it) } ?: "Built-in Mic (default)" }
        spinnerMic.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val savedId = prefs().getInt(RecorderService.PREF_MIC_DEVICE_ID, -1)
        val savedKey = prefs().getString(RecorderService.PREF_MIC_DEVICE_KEY, "") ?: ""
        val hasSavedMic = savedId != -1 || savedKey.isNotBlank()
        val idIdx = if (savedId == -1) -1 else orderedDevices.indexOfFirst { it?.id == savedId }
        val keyIdx = if (savedKey.isBlank()) -1 else orderedDevices.indexOfFirst { micDeviceKey(it) == savedKey }
        val idx = when {
            idIdx >= 0 -> idIdx
            keyIdx >= 0 -> keyIdx
            else -> 0
        }
        spinnerMic.setSelection(idx)
        if (!hasSavedMic || idIdx >= 0 || keyIdx >= 0) persistSelectedMic(idx)
        Log.d(TAG, "Mic spinner: ${labels.size} entries, selected idx=$idx")

        spinnerMic.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val device = orderedDevices[pos]
                val deviceId = device?.id ?: -1
                Log.d(TAG, "Mic selected: pos=$pos deviceId=$deviceId")
                persistSelectedMic(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun isUsbDevice(d: AudioDeviceInfo) =
        d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE    -> "USB"
            AudioDeviceInfo.TYPE_USB_HEADSET   -> "USB Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_BUILTIN_MIC   -> "Built-in"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Type${d.type}"
        }
        return "$type: ${d.productName ?: "Device ${d.id}"}"
    }

    private fun persistSelectedMic(pos: Int) {
        val device = orderedDevices.getOrNull(pos)
        prefs().edit()
            .putInt(RecorderService.PREF_MIC_DEVICE_ID, device?.id ?: -1)
            .putString(RecorderService.PREF_MIC_DEVICE_KEY, micDeviceKey(device))
            .apply()
    }

    private fun micDeviceKey(d: AudioDeviceInfo?): String {
        if (d == null) return "builtin"
        return "${d.type}:${d.productName?.toString().orEmpty()}"
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etPcIp       = view.findViewById<EditText>(R.id.et_pc_ip)
        val etUploadPort = view.findViewById<EditText>(R.id.et_upload_port)
        val etUdpPort    = view.findViewById<EditText>(R.id.et_udp_port)

        val p = prefs()
        etPcIp.setText(p.getString(RecorderService.PREF_PC_IP, ""))
        etUploadPort.setText(p.getInt(RecorderService.PREF_UPLOAD_PORT, RecorderService.DEFAULT_UPLOAD_PORT).toString())
        etUdpPort.setText(p.getInt(RecorderService.PREF_UDP_PORT, RecorderService.DEFAULT_UDP_PORT).toString())

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val pcIp       = etPcIp.text.toString().trim()
                val uploadPort = etUploadPort.text.toString().toIntOrNull() ?: RecorderService.DEFAULT_UPLOAD_PORT
                val udpPort    = etUdpPort.text.toString().toIntOrNull()    ?: RecorderService.DEFAULT_UDP_PORT
                val oldUdp     = p.getInt(RecorderService.PREF_UDP_PORT, RecorderService.DEFAULT_UDP_PORT)

                Log.d(TAG, "Settings saved: pcIp=$pcIp uploadPort=$uploadPort udpPort=$udpPort")
                val saved = p.edit()
                    .putString(RecorderService.PREF_PC_IP, pcIp)
                    .putInt(RecorderService.PREF_UPLOAD_PORT, uploadPort)
                    .putInt(RecorderService.PREF_UDP_PORT, udpPort)
                    .commit()

                Log.d(TAG, "Settings commit result: $saved")
                refreshPhoneIp()
                updatePcIpDisplay()
                tvUdpStatus.text = "listening on :$udpPort"
                if (udpPort != oldUdp) service?.restartUdpListener()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshUiFromService() {
        val svc = service ?: return
        onStateChanged(svc.currentState, svc.currentTake)
        refreshLog()
        svc.lastFile.takeIf { it.isNotEmpty() }?.let { tvLastFile.text = it }
        val udpPort = prefs().getInt(RecorderService.PREF_UDP_PORT, RecorderService.DEFAULT_UDP_PORT)
        tvUdpStatus.text = "listening on :$udpPort"
        refreshPhoneIp()
        updatePcIpDisplay()
    }

    private fun refreshLog() {
        val svc = service ?: return
        synchronized(svc.eventLog) {
            tvLog.text = svc.eventLog.joinToString("\n").ifEmpty { "—" }
        }
    }

    private fun copyLog() {
        val text = tvLog.text.toString().takeIf { it != "—" }.orEmpty()
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("AudioMicSync log", text))
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        service?.let { svc ->
            synchronized(svc.eventLog) {
                svc.eventLog.clear()
            }
        }
        tvLog.text = "—"
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
    }

    private fun updatePcIpDisplay() {
        val p    = prefs()
        val ip   = p.getString(RecorderService.PREF_PC_IP, "") ?: ""
        val port = p.getInt(RecorderService.PREF_UPLOAD_PORT, RecorderService.DEFAULT_UPLOAD_PORT)
        tvPcIp.text = if (ip.isBlank()) "not configured" else "$ip:$port"
    }

    private fun refreshPhoneIp() {
        tvPhoneIp.text = getLocalIp()
    }

    private fun getLocalIp(): String {
        return try {
            val activeIp = getActiveNetworkIp()
            if (activeIp != null) {
                Log.d(TAG, "Active network IP: $activeIp")
                return activeIp
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { !it.isLoopback && it.isUp }
                .toList()
            val preferred = interfaces
                .filter { isPreferredNetworkInterface(it.name) }
                .flatMap { it.inetAddresses.asSequence().toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            val fallback = interfaces
                .flatMap { it.inetAddresses.asSequence().toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            val ip = preferred?.hostAddress ?: fallback?.hostAddress ?: "Unknown"
            Log.d(TAG, "Local IP: $ip")
            ip
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIp failed", e)
            "Unknown"
        }
    }

    private fun getActiveNetworkIp(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val preferredNetworks = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        val activeNetwork = cm.activeNetwork
        val networks = if (activeNetwork != null) {
            preferredNetworks + listOf(activeNetwork)
        } else {
            preferredNetworks
        }

        return networks.asSequence()
            .distinct()
            .mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .map { it.address }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun isPreferredNetworkInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.startsWith("wlan") ||
            n.startsWith("eth") ||
            n.startsWith("ap") ||
            n.startsWith("p2p")
    }

    private fun prefs() = getSharedPreferences(RecorderService.PREFS_NAME, MODE_PRIVATE)
}

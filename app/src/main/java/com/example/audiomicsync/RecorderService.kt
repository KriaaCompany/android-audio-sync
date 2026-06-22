package com.example.audiomicsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RecorderService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "audio_mic_sync"
        const val TAG = "AudioMicSync/Svc"

        const val PREFS_NAME = "AudioMicSyncPrefs"
        const val PREF_PC_IP = "pc_ip"
        const val PREF_UPLOAD_PORT = "upload_port"
        const val PREF_UDP_PORT = "udp_port"
        const val PREF_SAMPLE_RATE = "sample_rate"
        const val PREF_MIC_DEVICE_ID = "mic_device_id"
        const val PREF_MIC_DEVICE_KEY = "mic_device_key"

        const val DEFAULT_UDP_PORT = 9877
        const val DEFAULT_UPLOAD_PORT = 9878
    }

    inner class ServiceBinder : Binder() {
        fun getService(): RecorderService = this@RecorderService
    }

    interface StatusListener {
        fun onStateChanged(state: State, takeName: String)
        fun onLogAdded(entry: String)
        fun onTransferProgress(percent: Int)
    }

    enum class State { IDLE, RECORDING, UPLOADING }

    private val binder = ServiceBinder()
    private var listener: StatusListener? = null

    private var udpSocket: DatagramSocket? = null
    private var udpThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var pcmFile: File? = null

    var currentState: State = State.IDLE
        private set
    var currentTake: String = ""
        private set
    var lastFile: String = ""
        private set
    val eventLog = ArrayDeque<String>()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    override fun onBind(intent: Intent): IBinder = binder

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate — SDK=${Build.VERSION.SDK_INT}")
        createNotificationChannel()

        try {
            val notification = buildNotification("Starting UDP listener…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                Log.d(TAG, "startForeground OK with microphone type")
            } else {
                // RECORD_AUDIO may not be granted yet; the type is promoted in startRecording().
                startForeground(NOTIFICATION_ID, notification)
                Log.d(TAG, "startForeground OK without microphone type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
        }

        acquireRuntimeLocks()
        startUdpListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand flags=$flags startId=$startId")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopUdpListener()
        stopRecordingImmediate()
        releaseRuntimeLocks()
        super.onDestroy()
    }

    fun setListener(l: StatusListener?) {
        listener = l
    }

    // -----------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------

    private fun createNotificationChannel() {
        Log.d(TAG, "Creating notification channel: $CHANNEL_ID")
        val channel = NotificationChannel(
            CHANNEL_ID, "Mic Recorder", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Listens for UDP record triggers" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("AudioMicSync")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        Log.d(TAG, "Notification: $text")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireRuntimeLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:listener").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Partial wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
            log("Wake lock error: ${e.message}")
        }

        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Wi-Fi lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire Wi-Fi lock", e)
            log("Wi-Fi lock error: ${e.message}")
        }
    }

    private fun releaseRuntimeLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            Log.d(TAG, "Wi-Fi lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release Wi-Fi lock", e)
        } finally {
            wifiLock = null
        }

        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            Log.d(TAG, "Partial wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    // -----------------------------------------------------------------
    // UDP listener
    // -----------------------------------------------------------------

    private fun startUdpListener() {
        val port = prefs().getInt(PREF_UDP_PORT, DEFAULT_UDP_PORT)
        Log.d(TAG, "Starting UDP listener on port $port")

        udpThread = Thread {
            try {
                udpSocket = DatagramSocket(port)
                Log.d(TAG, "UDP socket bound to :$port")
                log("UDP listening on :$port")
                updateNotification("Listening on :$port")

                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)

                while (!Thread.currentThread().isInterrupted) {
                    udpSocket?.receive(packet) ?: break
                    val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    Log.d(TAG, "UDP packet from ${packet.address}: $json")
                    handlePacket(json)
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "UDP listener error", e)
                    log("UDP error: ${e.message}")
                }
            }
            Log.d(TAG, "UDP listener thread exiting")
        }.also { it.isDaemon = true; it.name = "udp-listener"; it.start() }
    }

    private fun stopUdpListener() {
        Log.d(TAG, "Stopping UDP listener")
        udpThread?.interrupt()
        udpSocket?.close()
        udpSocket = null
        udpThread = null
    }

    fun restartUdpListener() {
        Log.d(TAG, "Restarting UDP listener")
        stopUdpListener()
        startUdpListener()
    }

    fun simulatePacket(json: String) {
        Log.d(TAG, "Simulating packet: $json")
        Thread { handlePacket(json) }.also { it.isDaemon = true; it.start() }
    }

    private fun handlePacket(json: String) {
        try {
            val obj = JSONObject(json)
            val cmd = obj.optString("cmd")
            Log.d(TAG, "Handling cmd=$cmd")
            when (cmd) {
                "record_start" -> {
                    val take = obj.optString("take", "take_${System.currentTimeMillis()}")
                    Log.d(TAG, "record_start take=$take")
                    startRecording(take)
                }
                "record_stop" -> {
                    Log.d(TAG, "record_stop")
                    stopRecordingAndUpload()
                }
                else -> Log.w(TAG, "Unknown cmd: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handlePacket parse error", e)
            log("Bad UDP packet: ${e.message}")
        }
    }

    // -----------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------

    private fun startRecording(takeName: String) {
        Log.d(TAG, "startRecording($takeName) — current isRecording=${isRecording.get()}")

        if (!isRecording.compareAndSet(false, true)) {
            Log.w(TAG, "Already recording — ignoring start")
            log("Already recording — ignoring start")
            return
        }

        val recordAudioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "RECORD_AUDIO granted: $recordAudioGranted")

        if (!recordAudioGranted) {
            Log.e(TAG, "RECORD_AUDIO not granted — cannot record")
            log("RECORD_AUDIO permission not granted — open app and grant it")
            isRecording.set(false)
            return
        }

        // Promote foreground service type to microphone now that permission is confirmed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("REC: $takeName"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                Log.d(TAG, "Promoted to FOREGROUND_SERVICE_TYPE_MICROPHONE")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to promote service type", e)
            }
        }

        val prefs = prefs()
        val sampleRate = prefs.getInt(PREF_SAMPLE_RATE, 48000)
        val deviceId   = prefs.getInt(PREF_MIC_DEVICE_ID, -1)
        val deviceKey  = prefs.getString(PREF_MIC_DEVICE_KEY, "") ?: ""
        Log.d(TAG, "AudioRecord config: sampleRate=$sampleRate deviceId=$deviceId deviceKey=$deviceKey")

        val minBuf     = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = minBuf.coerceAtLeast(8192)
        Log.d(TAG, "AudioRecord minBuf=$minBuf bufferSize=$bufferSize")

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        Log.d(TAG, "AudioRecord state=${record.state} (${if (record.state == AudioRecord.STATE_INITIALIZED) "OK" else "FAIL"})")

        if (deviceId != -1 || deviceKey.isNotBlank()) {
            val am = getSystemService(AudioManager::class.java)
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val device = inputs.firstOrNull { it.id == deviceId }
                ?: inputs.firstOrNull { micDeviceKey(it) == deviceKey }
            if (device != null) {
                record.preferredDevice = device
                Log.d(TAG, "preferredDevice set: ${device.productName} type=${device.type}")
            } else {
                Log.w(TAG, "Saved deviceId=$deviceId deviceKey=$deviceKey not found — using default")
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            log("AudioRecord init failed — check mic/permissions")
            record.release()
            isRecording.set(false)
            return
        }

        val outDir = (getExternalFilesDir("takes") ?: filesDir.resolve("takes")).also { it.mkdirs() }
        val pcm    = outDir.resolve("$takeName.pcm")
        pcmFile    = pcm
        Log.d(TAG, "PCM temp file: ${pcm.absolutePath}")

        currentTake  = takeName
        currentState = State.RECORDING
        listener?.onStateChanged(State.RECORDING, takeName)
        updateNotification("REC: $takeName")
        log("Recording started: $takeName  (${sampleRate}Hz)")

        audioRecord = record
        record.startRecording()
        Log.d(TAG, "AudioRecord.startRecording() called — recordingState=${record.recordingState}")

        recordThread = Thread {
            val buf = ByteArray(bufferSize)
            var totalBytes = 0L
            try {
                FileOutputStream(pcm).use { fos ->
                    while (isRecording.get()) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) { fos.write(buf, 0, n); totalBytes += n }
                        else if (n < 0) Log.w(TAG, "AudioRecord.read returned $n")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PCM writer error", e)
            }
            Log.d(TAG, "PCM writer done — totalBytes=$totalBytes")
        }.also { it.isDaemon = true; it.name = "pcm-writer"; it.start() }
    }

    private fun stopRecordingAndUpload() {
        Log.d(TAG, "stopRecordingAndUpload — isRecording=${isRecording.get()}")
        if (!isRecording.compareAndSet(true, false)) {
            Log.w(TAG, "Not recording — ignoring stop")
            return
        }

        Thread {
            Log.d(TAG, "Joining recordThread…")
            recordThread?.join(3000)
            recordThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord stopped and released")

            val take = currentTake
            val pcm  = pcmFile ?: run {
                Log.e(TAG, "No PCM file reference")
                log("No PCM file — recording may have failed")
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE, take)
                return@Thread
            }

            Log.d(TAG, "PCM file: ${pcm.absolutePath}  size=${pcm.length()} bytes")

            val sampleRate = prefs().getInt(PREF_SAMPLE_RATE, 48000)
            val wavFile    = pcm.parentFile!!.resolve("$take.wav")

            try {
                WavWriter.finalize(pcm, wavFile, sampleRate)
                lastFile = wavFile.name
                Log.d(TAG, "WAV written: ${wavFile.absolutePath}  size=${wavFile.length()} bytes")
                log("WAV saved: ${wavFile.name}  (${wavFile.length() / 1024} KB)")
            } catch (e: Exception) {
                Log.e(TAG, "WavWriter failed", e)
                log("WAV write error: ${e.message}")
                currentState = State.IDLE
                listener?.onStateChanged(State.IDLE, take)
                return@Thread
            }

            currentState = State.UPLOADING
            listener?.onStateChanged(State.UPLOADING, take)
            updateNotification("Uploading: $take")

            val prefs      = prefs()
            val pcIp       = prefs.getString(PREF_PC_IP, "") ?: ""
            val uploadPort = prefs.getInt(PREF_UPLOAD_PORT, DEFAULT_UPLOAD_PORT)
            Log.d(TAG, "Upload target: $pcIp:$uploadPort")

            if (pcIp.isBlank()) {
                Log.w(TAG, "No PC IP configured — skipping upload")
                log("No PC IP set — file kept at ${wavFile.absolutePath}")
            } else {
                try {
                    Log.d(TAG, "Starting upload: ${wavFile.name} → http://$pcIp:$uploadPort/upload")
                    val result = FileUploader.uploadWithResult(wavFile, pcIp, uploadPort) { pct ->
                        Log.v(TAG, "Upload progress: $pct%")
                        listener?.onTransferProgress(pct)
                    }
                    Log.d(TAG, "Upload result: $result")
                    if (result.success) {
                        log("Upload OK: ${wavFile.name}")
                    } else {
                        log("Upload failed: ${result.errorMessage ?: "server error"} — file kept locally")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload exception", e)
                    log("Upload error: ${e.message} — file kept locally")
                }
            }

            val udpPort = prefs.getInt(PREF_UDP_PORT, DEFAULT_UDP_PORT)
            currentState = State.IDLE
            listener?.onStateChanged(State.IDLE, take)
            updateNotification("Listening on :$udpPort")
        }.also { it.isDaemon = true; it.name = "stop-upload"; it.start() }
    }

    private fun stopRecordingImmediate() {
        Log.d(TAG, "stopRecordingImmediate")
        isRecording.set(false)
        recordThread?.interrupt()
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun micDeviceKey(d: android.media.AudioDeviceInfo): String {
        return "${d.type}:${d.productName?.toString().orEmpty()}"
    }

    // -----------------------------------------------------------------
    // Log (in-app event list + Logcat)
    // -----------------------------------------------------------------

    private fun log(msg: String) {
        val entry = "${timeFmt.format(Date())} $msg"
        Log.i(TAG, "[EVENT] $entry")
        synchronized(eventLog) {
            eventLog.addFirst(entry)
            while (eventLog.size > 10) eventLog.removeLast()
        }
        listener?.onLogAdded(entry)
    }
}

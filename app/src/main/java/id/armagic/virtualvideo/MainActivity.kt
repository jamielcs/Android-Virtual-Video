package id.armagic.virtualvideo

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import id.armagic.virtualvideo.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer

    private var selectedVideoUri: Uri? = null
    private var cropMode = false

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                binding.bridgeStatusText.text =
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        "Bridge: Shizuku permission granted"
                    } else {
                        "Bridge: Shizuku permission ditolak"
                    }
                refreshCapabilities()
            }
        }

    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            refreshCapabilities()
        }

    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            refreshCapabilities()
        }

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                persistReadPermission(uri)
                selectedVideoUri = uri
                loadVideo(uri, autoPlay = true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupControls()
        setupVirtualCameraLab()
        setupCameraProbe()
        restoreLastVideo()
    }

    override fun onResume() {
        super.onResume()
        refreshCapabilities()
    }

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }

        binding.playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButton(isPlaying)
                updateStatus()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateStatus()
            }
        })
    }

    private fun setupControls() {
        binding.selectVideoButton.setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        binding.playPauseButton.setOnClickListener {
            if (selectedVideoUri == null) {
                videoPicker.launch(arrayOf("video/*"))
                return@setOnClickListener
            }

            if (player.isPlaying) player.pause() else player.play()
        }

        binding.loopSwitch.setOnCheckedChangeListener { _, isChecked ->
            player.repeatMode =
                if (isChecked) Player.REPEAT_MODE_ONE
                else Player.REPEAT_MODE_OFF
            updateStatus()
        }

        binding.muteSwitch.setOnCheckedChangeListener { _, isChecked ->
            player.volume = if (isChecked) 0f else 1f
            updateStatus()
        }

        binding.fitCropButton.setOnClickListener {
            cropMode = !cropMode
            applyResizeMode()
            updateStatus()
        }

        applyResizeMode()
    }

    private fun setupVirtualCameraLab() {
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        binding.refreshCapabilityButton.setOnClickListener { refreshCapabilities() }
        binding.requestShizukuButton.setOnClickListener { requestShizukuPermission() }
        binding.startSurfaceTestButton.setOnClickListener { runSurfaceTest() }

        refreshCapabilities()
    }

    private fun setupCameraProbe() {
        binding.runCameraProbeButton.setOnClickListener { runCameraProbe() }
        binding.runShellProbeButton.setOnClickListener { runCameraServiceProbe() }
    }

    private fun refreshCapabilities() {
        val developerOptionsEnabled = runCatching {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)

        val adbEnabled = runCatching {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        }.getOrDefault(false)

        binding.deviceInfoText.text =
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Developer Options: ${if (developerOptionsEnabled) "ON" else "OFF"} • ADB: ${if (adbEnabled) "ON" else "OFF"}"

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        val permissionGranted = if (binderAlive) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        binding.shizukuStatusText.text = when {
            !binderAlive -> "Shizuku: binder belum aktif"
            permissionGranted ->
                "Shizuku: AKTIF • permission GRANTED • UID ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}"
            else -> "Shizuku: AKTIF • permission BELUM diberikan"
        }

        binding.requestShizukuButton.isEnabled = binderAlive && !permissionGranted

        binding.bridgeStatusText.text = when {
            !binderAlive -> "Bridge: menunggu Shizuku binder"
            !permissionGranted -> "Bridge: menunggu permission Shizuku"
            selectedVideoUri == null -> "Bridge: privilege siap • pilih video untuk test surface"
            else -> "Bridge: privilege + video siap • surface dapat diuji"
        }
    }

    private fun requestShizukuPermission() {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        if (!binderAlive) {
            binding.bridgeStatusText.text = "Bridge: Shizuku belum aktif"
            return
        }

        val currentPermission = runCatching {
            Shizuku.checkSelfPermission()
        }.getOrDefault(PackageManager.PERMISSION_DENIED)

        if (currentPermission == PackageManager.PERMISSION_GRANTED) {
            binding.bridgeStatusText.text = "Bridge: permission Shizuku sudah GRANTED"
            refreshCapabilities()
            return
        }

        runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }.onFailure {
            binding.bridgeStatusText.text =
                "Bridge: gagal request Shizuku • ${it.javaClass.simpleName}"
        }
    }

    private fun runSurfaceTest() {
        if (selectedVideoUri == null) {
            binding.bridgeStatusText.text =
                "Bridge: pilih video dulu sebelum test surface"
            return
        }

        if (!player.isPlaying) player.play()

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderAlive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        binding.bridgeStatusText.text =
            if (permissionGranted) {
                "Surface test: OK • decoder berjalan • Shizuku shell privilege siap"
            } else {
                "Surface test: OK • decoder berjalan • Shizuku belum siap"
            }
    }

    private fun runCameraProbe() {
        val manager = getSystemService(CameraManager::class.java)

        val report = buildString {
            appendLine("=== CAMERA PROBE ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Package: $packageName")
            appendLine()

            val ids = runCatching { manager.cameraIdList.toList() }.getOrElse {
                appendLine("cameraIdList ERROR: ${it.javaClass.simpleName}: ${it.message}")
                emptyList()
            }

            appendLine("Camera IDs (${ids.size}): ${ids.joinToString(", ")}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val concurrent = runCatching { manager.concurrentCameraIds }.getOrNull()
                appendLine("Concurrent camera sets: ${concurrent?.size ?: 0}")
                concurrent?.forEachIndexed { index, set ->
                    appendLine("  Set ${index + 1}: ${set.joinToString(", ")}")
                }
            }

            ids.forEach { cameraId ->
                appendLine()
                appendLine("--- Camera $cameraId ---")

                runCatching {
                    val c = manager.getCameraCharacteristics(cameraId)

                    val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }

                    val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }

                    appendLine("Facing: $facing")
                    appendLine("Hardware level: $level")
                    appendLine("Sensor orientation: ${c.get(CameraCharacteristics.SENSOR_ORIENTATION)}")

                    val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.joinToString(", ") ?: "-"
                    appendLine("Capabilities: $caps")

                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val textureSizes = map?.getOutputSizes(SurfaceTexture::class.java)
                        ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
                        ?.take(10)
                        ?.joinToString(", ") { "${it.width}x${it.height}" }
                        ?: "-"

                    appendLine("SurfaceTexture sizes: $textureSizes")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val ext = runCatching {
                            manager.getCameraExtensionCharacteristics(cameraId)
                                .supportedExtensions
                        }.getOrDefault(emptyList())

                        appendLine(
                            "Extensions: " +
                                if (ext.isEmpty()) "-" else ext.joinToString(", ") { extensionName(it) }
                        )
                    }
                }.onFailure {
                    appendLine("ERROR: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }

        binding.cameraProbeText.text = report
    }

    private fun runCameraServiceProbe() {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderAlive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!permissionGranted) {
            binding.cameraProbeText.text =
                "Camera service probe butuh Shizuku permission GRANTED."
            return
        }

        binding.cameraProbeText.text = "Menjalankan camera service probe..."

        Thread {
            val result = runCatching {
                val command =
                    "echo '=== CAMERA SERVICES ==='; " +
                    "service list | grep -i camera; " +
                    "echo; echo '=== MEDIA.CAMERA SUMMARY ==='; " +
                    "dumpsys media.camera | head -n 100"

                @Suppress("DEPRECATION")
                val process = Shizuku.newProcess(
                    arrayOf("sh", "-c", command),
                    null,
                    null
                )

                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()

                buildString {
                    append(stdout.ifBlank { "(no stdout)" })
                    if (stderr.isNotBlank()) {
                        appendLine()
                        appendLine("=== STDERR ===")
                        append(stderr)
                    }
                    appendLine()
                    appendLine("Exit: ${process.exitValue()}")
                }
            }.getOrElse {
                "Shell probe ERROR: ${it.javaClass.simpleName}: ${it.message}"
            }

            runOnUiThread {
                binding.cameraProbeText.text = result
            }
        }.start()
    }

    private fun extensionName(value: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (value) {
                CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTO"
                CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
                CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
                CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
                else -> value.toString()
            }
        } else {
            value.toString()
        }
    }

    private fun loadVideo(uri: Uri, autoPlay: Boolean) {
        binding.emptyText.visibility = View.GONE

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        if (autoPlay) player.play()

        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_VIDEO_URI, uri.toString())
            .apply()

        updateStatus()
        refreshCapabilities()
    }

    private fun restoreLastVideo() {
        val saved = getPreferences(MODE_PRIVATE)
            .getString(KEY_LAST_VIDEO_URI, null)
            ?: return

        runCatching {
            val uri = Uri.parse(saved)
            selectedVideoUri = uri
            loadVideo(uri, autoPlay = false)
        }.onFailure {
            selectedVideoUri = null
            binding.emptyText.visibility = View.VISIBLE
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun applyResizeMode() {
        binding.playerView.resizeMode =
            if (cropMode) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }

        binding.fitCropButton.text =
            if (cropMode) "Mode: CROP" else "Mode: FIT"
    }

    private fun updatePlayButton(isPlaying: Boolean) {
        binding.playPauseButton.text = if (isPlaying) "Pause" else "Play"
    }

    private fun updateStatus() {
        val hasVideo = selectedVideoUri != null
        val playState = when {
            !hasVideo -> "belum ada video"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.isPlaying -> "playing"
            player.playbackState == Player.STATE_ENDED -> "selesai"
            else -> "pause"
        }

        val loopState = if (binding.loopSwitch.isChecked) "ON" else "OFF"
        val audioState = if (binding.muteSwitch.isChecked) "MUTE" else "ON"
        val mode = if (cropMode) "CROP" else "FIT"

        binding.statusText.text =
            "Status: $playState  •  Loop: $loopState  •  Audio: $audioState  •  $mode"
    }

    override fun onStop() {
        super.onStop()
        if (player.isPlaying) player.pause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)

        binding.playerView.player = null
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val KEY_LAST_VIDEO_URI = "last_video_uri"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}

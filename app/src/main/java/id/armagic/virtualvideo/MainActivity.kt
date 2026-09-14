package id.armagic.virtualvideo

import android.content.Intent
import android.content.pm.PackageManager
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

            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
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

        binding.refreshCapabilityButton.setOnClickListener {
            refreshCapabilities()
        }

        binding.requestShizukuButton.setOnClickListener {
            requestShizukuPermission()
        }

        binding.startSurfaceTestButton.setOnClickListener {
            runSurfaceTest()
        }

        refreshCapabilities()
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

        val binderAlive = runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)

        val permissionGranted = if (binderAlive) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }

        binding.shizukuStatusText.text = when {
            !binderAlive ->
                "Shizuku: binder belum aktif • jalankan Shizuku lalu refresh"
            permissionGranted ->
                "Shizuku: AKTIF • permission GRANTED • UID ${runCatching { Shizuku.getUid() }.getOrDefault(-1)}"
            else ->
                "Shizuku: AKTIF • permission BELUM diberikan"
        }

        binding.requestShizukuButton.isEnabled = binderAlive && !permissionGranted

        if (!binderAlive) {
            binding.bridgeStatusText.text =
                "Bridge: menunggu Shizuku binder"
        } else if (!permissionGranted) {
            binding.bridgeStatusText.text =
                "Bridge: menunggu permission Shizuku"
        } else if (selectedVideoUri == null) {
            binding.bridgeStatusText.text =
                "Bridge: privilege siap • pilih video untuk test surface"
        } else {
            binding.bridgeStatusText.text =
                "Bridge: privilege + video siap • surface dapat diuji"
        }
    }

    private fun requestShizukuPermission() {
        val binderAlive = runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)

        if (!binderAlive) {
            binding.bridgeStatusText.text =
                "Bridge: Shizuku belum aktif. Start Shizuku via Wireless Debugging/ADB."
            return
        }

        val currentPermission = runCatching {
            Shizuku.checkSelfPermission()
        }.getOrDefault(PackageManager.PERMISSION_DENIED)

        if (currentPermission == PackageManager.PERMISSION_GRANTED) {
            binding.bridgeStatusText.text =
                "Bridge: permission Shizuku sudah GRANTED"
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

        if (!player.isPlaying) {
            player.play()
        }

        val binderAlive = runCatching {
            Shizuku.pingBinder()
        }.getOrDefault(false)

        val permissionGranted = binderAlive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        binding.bridgeStatusText.text =
            if (permissionGranted) {
                "Surface test: OK • decoder berjalan • Shizuku shell privilege siap • global camera bridge belum diaktifkan"
            } else {
                "Surface test: OK • decoder berjalan • Shizuku belum siap"
            }
    }

    private fun loadVideo(uri: Uri, autoPlay: Boolean) {
        binding.emptyText.visibility = View.GONE

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        if (autoPlay) {
            player.play()
        }

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
        if (player.isPlaying) {
            player.pause()
        }
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

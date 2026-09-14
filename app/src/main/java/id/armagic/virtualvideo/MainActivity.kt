package id.armagic.virtualvideo

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import id.armagic.virtualvideo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private var selectedVideoUri: Uri? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val videoPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                selectedVideoUri = uri
                binding.emptyText.visibility = View.GONE
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                binding.directStatusText.text =
                    "Video: READY\nBackground engine: STOPPED"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }

        binding.playerView.player = player

        binding.selectVideoButton.setOnClickListener {
            videoPicker.launch(arrayOf("video/*"))
        }

        binding.loopSwitch.setOnCheckedChangeListener { _, checked ->
            player.repeatMode =
                if (checked) Player.REPEAT_MODE_ONE
                else Player.REPEAT_MODE_OFF
        }

        binding.muteSwitch.setOnCheckedChangeListener { _, checked ->
            player.volume = if (checked) 0f else 1f
        }

        binding.startDirectTestButton.setOnClickListener {
            startBackgroundEngineAndTest()
        }

        binding.openTikTokButton.setOnClickListener {
            openTikTok()
        }

        binding.stopEngineButton.setOnClickListener {
            stopBackgroundEngine()
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && selectedVideoUri != null) {
                    binding.directStatusText.text =
                        "Video: READY\nTekan Start Background Engine + TikTok Test"
                }
            }
        })

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
    }

    private fun startBackgroundEngineAndTest() {
        val uri = selectedVideoUri
        if (uri == null) {
            binding.directStatusText.text = "RESULT: PILIH VIDEO DULU"
            return
        }

        val serviceIntent = Intent(this, BackgroundVideoService::class.java).apply {
            action = BackgroundVideoService.ACTION_START
            putExtra(BackgroundVideoService.EXTRA_VIDEO_URI, uri.toString())
            putExtra(BackgroundVideoService.EXTRA_LOOP, binding.loopSwitch.isChecked)
            putExtra(BackgroundVideoService.EXTRA_MUTE, binding.muteSwitch.isChecked)
        }

        ContextCompat.startForegroundService(this, serviceIntent)

        binding.directStatusText.text =
            "Background engine: STARTING...\nDecoder akan tetap jalan saat app diminimize."

        binding.root.postDelayed({
            runDirectTikTokTest()
        }, 800)
    }

    private fun stopBackgroundEngine() {
        val intent = Intent(this, BackgroundVideoService::class.java).apply {
            action = BackgroundVideoService.ACTION_STOP
        }
        startService(intent)
        binding.directStatusText.text = "Background engine: STOPPED"
    }

    private fun runDirectTikTokTest() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraIds = runCatching {
            manager.cameraIdList.toList()
        }.getOrDefault(emptyList())

        val externalCameraIds = cameraIds.filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_EXTERNAL
            }.getOrDefault(false)
        }

        val injectPermission = checkSelfPermission(
            "android.permission.CAMERA_INJECT_EXTERNAL_CAMERA"
        ) == PackageManager.PERMISSION_GRANTED

        val tikTokPackage = findTikTokPackage()

        val injectMethodAvailable = runCatching {
            CameraManager::class.java.declaredMethods.any {
                it.name == "injectCamera"
            }
        }.getOrDefault(false)

        val result = buildString {
            appendLine("=== DIRECT TIKTOK TEST ===")
            appendLine("Background engine: RUNNING")
            appendLine("Video: READY")
            appendLine("TikTok: ${tikTokPackage ?: "TIDAK DITEMUKAN"}")
            appendLine("Camera IDs: ${cameraIds.joinToString(", ").ifBlank { "-" }}")
            appendLine("External camera: ${externalCameraIds.joinToString(", ").ifBlank { "NONE" }}")
            appendLine("injectCamera API: ${if (injectMethodAvailable) "ADA" else "TIDAK ADA"}")
            appendLine("Inject permission: ${if (injectPermission) "GRANTED" else "DENIED"}")
            appendLine()

            when {
                tikTokPackage == null ->
                    append("RESULT: TIKTOK TIDAK DITEMUKAN")
                !injectMethodAvailable ->
                    append("RESULT: BLOCKED - API INJECTION TIDAK TERSEDIA")
                externalCameraIds.isEmpty() ->
                    append("RESULT: NO EXTERNAL CAMERA - BACKGROUND DECODER TETAP RUNNING")
                !injectPermission ->
                    append("RESULT: PERMISSION DENIED - BACKGROUND DECODER TETAP RUNNING")
                else ->
                    append("RESULT: READY FOR REAL INJECTION ATTEMPT")
            }
        }

        binding.directStatusText.text = result

        if (tikTokPackage != null) {
            Toast.makeText(
                this,
                "Background engine aktif. TikTok akan dibuka.",
                Toast.LENGTH_SHORT
            ).show()

            binding.root.postDelayed({
                openTikTok()
            }, 1000)
        }
    }

    private fun findTikTokPackage(): String? {
        val candidates = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme"
        )

        return candidates.firstOrNull { pkg ->
            packageManager.getLaunchIntentForPackage(pkg) != null
        }
    }

    private fun openTikTok() {
        val pkg = findTikTokPackage()

        if (pkg == null) {
            binding.directStatusText.text =
                binding.directStatusText.text.toString() +
                    "\nTikTok launch: TIDAK DITEMUKAN"
            return
        }

        val intent = packageManager.getLaunchIntentForPackage(pkg)

        if (intent == null) {
            binding.directStatusText.text =
                binding.directStatusText.text.toString() +
                    "\nTikTok launch: GAGAL"
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()
        if (player.isPlaying) {
            player.pause()
        }
    }

    override fun onDestroy() {
        binding.playerView.player = null
        player.release()
        super.onDestroy()
    }
}

package id.armagic.virtualvideo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import id.armagic.virtualvideo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer

    private var selectedVideoUri: Uri? = null
    private var cropMode = false

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
        restoreLastVideo()
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
        binding.playerView.player = null
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val KEY_LAST_VIDEO_URI = "last_video_uri"
    }
}

package com.fongmi.android.tv.quest

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.service.PlaybackService
import com.fongmi.android.tv.setting.SubtitleSetting
import com.fongmi.android.tv.utils.CinemaMode
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SessionState
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature

/** A second presentation of the existing service player: no second decoder or URL handoff. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CinemaActivity : AppSystemActivity(), ServiceConnection {
    private var playbackService: PlaybackService? = null
    private var bound = false
    private var playerView: PlayerView? = null
    private var controls: View? = null
    private var screen: Entity? = null
    private var geometry = CinemaGeometry()
    private var focusPolicy = CinemaFocusPolicy(false)
    private var closing = false
    private var errorMessage: String? = null
    private val ui = Handler(Looper.getMainLooper())

    private val playerCallback = object : PlaybackService.PlayerCallback {
        override fun onPlayerRebuild(player: Player) { attachPlayer() }
        override fun onError(msg: String) {
            errorMessage = msg
            refreshControls()
        }
        override fun onPrepare() {
            errorMessage = null
            attachPlayer()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closing) return
            refreshControls()
            ui.postDelayed(this, 500)
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("quest-cinema", MODE_PRIVATE)
        geometry = CinemaGeometry.restored(
            prefs.getFloat("width", 6f), prefs.getFloat("distance", 6f),
        )
        focusPolicy = CinemaFocusPolicy(intent.getBooleanExtra(CinemaMode.EXTRA_RESUME, false))
        val serviceIntent = Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.LOCAL_BIND_ACTION)
        bound = bindService(serviceIntent, this, BIND_AUTO_CREATE)
        if (!bound) endMissingSession()
        ui.post(tick)
    }

    override fun registerPanels(): List<PanelRegistration> = listOf(
        LayoutXMLPanelRegistration(
            R.id.quest_screen_panel,
            layoutIdCreator = { R.layout.quest_cinema_screen },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 6f, height = 3.375f),
                    display = DpDisplayOptions(width = 1920f, height = 1080f, dpi = 160),
                )
            },
            panelSetupWithRootView = { root, _, _ ->
                runOnUiThread {
                    if (!closing) {
                        playerView = root as PlayerView
                        SubtitleSetting.applyStyle(playerView?.subtitleView)
                        attachPlayer()
                    }
                }
            },
        ),
        LayoutXMLPanelRegistration(
            R.id.quest_controls_panel,
            layoutIdCreator = { R.layout.quest_cinema_controls },
            settingsCreator = {
                UIPanelSettings(
                    shape = QuadShapeOptions(width = 2.4f, height = 0.6f),
                    display = DpDisplayOptions(width = 1280f, height = 320f, dpi = 160),
                )
            },
            panelSetupWithRootView = { root, _, _ ->
                runOnUiThread {
                    if (!closing) {
                        controls = root
                        configureControls(root)
                        refreshControls()
                    }
                }
            },
        ),
    )

    override fun onSceneReady() {
        super.onSceneReady()
        if (closing) return
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(
            ambientColor = Vector3(0f),
            sunColor = Vector3(0f),
            sunDirection = Vector3(0f, -1f, 0f),
        )
        screen = Entity.create(
            Panel(R.id.quest_screen_panel),
            Transform(Pose(Vector3(0f, 1.8f, geometry.distance))),
            Scale(Vector3(geometry.width / 6f)),
        )
        Entity.create(
            Panel(R.id.quest_controls_panel),
            Transform(Pose(Vector3(0f, 0.65f, 3f))),
        )
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        if (closing) return
        playbackService = (binder as? PlaybackService.LocalBinder)?.service
        val manager = playbackService?.player()
        // Do not silently resurrect an empty session after process death.
        if (manager == null || manager.isEmpty || manager.key != intent.getStringExtra(CinemaMode.EXTRA_KEY)) {
            endMissingSession()
            return
        }
        playbackService?.addPlayerCallback(playerCallback)
        attachPlayer()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        playerView?.player = null
        playbackService = null
        endMissingSession()
    }

    private fun attachPlayer() {
        if (closing) return
        val manager = playbackService?.player() ?: return
        val view = playerView ?: return
        manager.bindPlayerView(view)
        view.player = manager.player
        if (!focusPolicy.focused) manager.player.pause()
        else if (focusPolicy.shouldResume()) {
            manager.player.play()
            focusPolicy.resumed()
        }
        refreshControls()
    }

    private fun configureControls(root: View) {
        root.findViewById<Button>(R.id.quest_play).setOnClickListener {
            playbackService?.player()?.player?.let { player ->
                if (player.playWhenReady) player.pause()
                else if (focusPolicy.focused) player.play()
            }
            refreshControls()
        }
        root.findViewById<Button>(R.id.quest_rewind).setOnClickListener { seek(-10_000L) }
        root.findViewById<Button>(R.id.quest_forward).setOnClickListener { seek(10_000L) }
        root.findViewById<Button>(R.id.quest_exit).setOnClickListener { finish() }
        root.findViewById<Button>(R.id.quest_smaller).setOnClickListener { updateGeometry(geometry.resized(-0.5f)) }
        root.findViewById<Button>(R.id.quest_larger).setOnClickListener { updateGeometry(geometry.resized(0.5f)) }
        root.findViewById<Button>(R.id.quest_nearer).setOnClickListener { updateGeometry(geometry.moved(-0.5f)) }
        root.findViewById<Button>(R.id.quest_farther).setOnClickListener { updateGeometry(geometry.moved(0.5f)) }
        root.findViewById<Button>(R.id.quest_reset).setOnClickListener { updateGeometry(CinemaGeometry()) }
    }

    private fun updateGeometry(value: CinemaGeometry) {
        geometry = value
        screen?.setComponent(Transform(Pose(Vector3(0f, 1.8f, value.distance))))
        screen?.setComponent(Scale(Vector3(value.width / 6f)))
        getSharedPreferences("quest-cinema", MODE_PRIVATE).edit()
            .putFloat("width", value.width).putFloat("distance", value.distance).apply()
        refreshControls()
    }

    private fun seek(delta: Long) {
        val player = playbackService?.player()?.player ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0, end))
        refreshControls()
    }

    private fun refreshControls() {
        val root = controls ?: return
        val manager = playbackService?.player() ?: return
        val player = manager.player
        root.findViewById<TextView>(R.id.quest_title).text = manager.mediaTitle
        root.findViewById<Button>(R.id.quest_play).setText(
            if (player.playWhenReady) R.string.quest_pause else R.string.quest_play,
        )
        root.findViewById<TextView>(R.id.quest_status).text = errorMessage ?: getString(
            R.string.quest_status_format,
            time(player.currentPosition), time(player.duration), geometry.width, geometry.distance,
        )
    }

    private fun time(milliseconds: Long): String {
        if (milliseconds == C.TIME_UNSET || milliseconds < 0) return "--:--"
        val seconds = milliseconds / 1000
        return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        runOnUiThread {
            if (closing) return@runOnUiThread
            if (state == SessionState.FOCUSED) {
                focusPolicy.gain()
                attachPlayer()
            } else pauseForFocusLoss()
        }
    }

    private fun pauseForFocusLoss() {
        val player = playbackService?.player()?.player
        focusPolicy.lose(player?.playWhenReady)
        player?.pause()
    }

    override fun onPause() {
        pauseForFocusLoss()
        super.onPause()
    }

    private fun endMissingSession() {
        Toast.makeText(this, R.string.quest_no_session, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onSpatialShutdown() {
        // The service owns this player. Only detach the cinema's output.
        playerView?.player = null
        playerView = null
        controls = null
        screen = null
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        closing = true
        ui.removeCallbacksAndMessages(null)
        playerView?.player = null
        playerView = null
        playbackService?.removePlayerCallback(playerCallback)
        if (bound) unbindService(this)
        bound = false
        playbackService = null
        super.onDestroy()
    }
}

package com.fongmi.android.tv.quest

import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.fongmi.android.tv.R
import com.fongmi.android.tv.service.PlaybackService
import com.fongmi.android.tv.setting.DanmakuSetting
import com.github.catvod.net.OkHttp
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
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
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
    private var screenFrame: Entity? = null
    private var environment: CinemaEnvironment? = null
    private var appearance = CinemaAppearance()
    private var sceneStatus: String? = null
    private var geometry = CinemaGeometry()
    private var focusPolicy = CinemaFocusPolicy(false)
    private var closing = false
    private var previewMode = false
    private var errorMessage: String? = null
    private var episodeMessage: String? = null
    private var switchingEpisode = false
    private val ui = Handler(Looper.getMainLooper())

    private val playerCallback = object : PlaybackService.PlayerCallback {
        override fun onPlayerRebuild(player: Player) { attachPlayer() }
        override fun onDanmakuSourceChanged(uri: android.net.Uri?) { playerView?.setDanmakuSource(uri) }
        override fun onDanmakuConfigChanged(config: androidx.media3.ui.danmaku.DanmakuConfig) { playerView?.setDanmakuConfig(config) }
        override fun onDanmakuEnabledChanged(enabled: Boolean) { playerView?.setDanmakuEnabled(enabled) }
        override fun onDanmakuSent(text: String) { playerView?.sendDanmaku(text) }
        override fun onEpisodeBoundary(next: Boolean) {
            switchingEpisode = false
            episodeMessage = getString(if (next) R.string.quest_no_next_episode else R.string.quest_no_prev_episode)
            refreshControls()
        }
        override fun onEpisodeSwitching() {
            switchingEpisode = true
            errorMessage = null
            episodeMessage = getString(R.string.quest_episode_switching)
            refreshControls()
        }
        override fun onError(msg: String) {
            switchingEpisode = false
            episodeMessage = null
            errorMessage = msg
            refreshControls()
        }
        override fun onPrepare() {
            switchingEpisode = false
            episodeMessage = null
            errorMessage = null
            attachPlayer()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) { refreshControls() }
    }
    private var attachedPlayer: Player? = null

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
        appearance = CinemaAppearance(
            CinemaRoom.fromKey(prefs.getString("room", null)), prefs.getFloat("brightness", .35f),
            prefs.getBoolean("shadows", true), prefs.getBoolean("screen-glow", true),
            prefs.getBoolean("starlight", true), prefs.getFloat("star-brightness", .5f),
        ).normalized()
        previewMode = intent.getBooleanExtra("preview_scene", false)
        focusPolicy = CinemaFocusPolicy(intent.getBooleanExtra(CinemaMode.EXTRA_RESUME, false))
        if (previewMode) {
            ui.post(tick)
            return
        }
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
                    shape = QuadShapeOptions(width = 2.4f, height = 1.23f),
                    display = DpDisplayOptions(width = 1280f, height = 656f, dpi = 160),
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
        screen = Entity.create(
            Panel(R.id.quest_screen_panel),
            Transform(Pose(Vector3(0f, geometry.centerHeight, geometry.distance))),
            Scale(Vector3(geometry.width / 6f)),
        )
        screenFrame = Entity.create(
            Mesh(android.net.Uri.parse("apk:///scenes/screen_frame.glb"), hittable = MeshCollision.NoCollision),
            Transform(Pose(Vector3(0f, geometry.centerHeight, geometry.distance + .08f))),
            Scale(Vector3(geometry.width / 6f)),
        )
        Entity.create(Panel(R.id.quest_controls_panel), Transform(Pose(Vector3(0f, 0.55f, 3f))), Scale(Vector3(.8f)))
        environment = CinemaEnvironment(this, onRoomState = { _, loading, failed ->
            sceneStatus = when {
                loading -> getString(R.string.quest_scene_loading)
                failed -> getString(R.string.quest_scene_error)
                else -> null
            }
            refreshControls()
        }, onStarlightError = {
            sceneStatus = getString(R.string.quest_stars_error)
            refreshControls()
        }).also { it.start(screen!!, appearance) }
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
        if (attachedPlayer !== manager.player) {
            attachedPlayer?.removeListener(playerListener)
            attachedPlayer = manager.player
            attachedPlayer?.addListener(playerListener)
        }
        manager.bindPlayerView(view)
        view.player = manager.player
        view.setDanmakuOkHttpClient(OkHttp.player())
        view.setDanmakuConfig(DanmakuSetting.getConfig())
        view.setDanmakuEnabled(DanmakuSetting.isShow())
        view.setDanmakuSource(manager.selectedDanmakuUri)
        if (!focusPolicy.focused) manager.player.pause()
        else if (focusPolicy.shouldResume()) {
            manager.player.play()
            focusPolicy.resumed()
        }
        refreshControls()
    }

    private fun configureControls(root: View) {
        root.findViewById<View>(R.id.quest_episode_controls).visibility = if (previewMode) View.GONE else View.VISIBLE
        root.findViewById<Button>(R.id.quest_prev_episode).setOnClickListener { navigateEpisode(false) }
        root.findViewById<Button>(R.id.quest_next_episode).setOnClickListener { navigateEpisode(true) }
        root.findViewById<Button>(R.id.quest_auto_next).setOnClickListener {
            val prefs = getSharedPreferences("quest-cinema", MODE_PRIVATE)
            prefs.edit().putBoolean("auto-next", !CinemaMode.shouldAutoAdvance(this)).apply()
            refreshControls()
        }
        root.findViewById<Button>(R.id.quest_play).setOnClickListener {
            playbackService?.player()?.player?.let { player ->
                if (player.playWhenReady) player.pause()
                else if (focusPolicy.focused) {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
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
        root.findViewById<Button>(R.id.quest_scene_cinema).setOnClickListener {
            appearance = appearance.copy(room = CinemaRoom.PRIVATE_CINEMA)
            applyAppearance()
        }
        root.findViewById<Button>(R.id.quest_scene_space).setOnClickListener {
            appearance = appearance.copy(room = CinemaRoom.ORBITAL_LOUNGE)
            applyAppearance()
        }
        root.findViewById<Button>(R.id.quest_shadows).setOnClickListener {
            appearance = appearance.copy(shadows = !appearance.shadows)
            applyAppearance()
        }
        root.findViewById<Button>(R.id.quest_glow).setOnClickListener {
            appearance = appearance.copy(screenGlow = !appearance.screenGlow)
            applyAppearance()
        }
        root.findViewById<SeekBar>(R.id.quest_light_slider).apply {
            progress = (appearance.brightness * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    appearance = appearance.copy(brightness = value / 100f)
                    applyAppearance(save = false)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { saveAppearance() }
            })
        }
        root.findViewById<Button>(R.id.quest_stars).setOnClickListener {
            appearance = appearance.copy(starlight = !appearance.starlight)
            applyAppearance()
        }
        root.findViewById<SeekBar>(R.id.quest_star_slider).apply {
            progress = (appearance.starBrightness * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    appearance = appearance.copy(starBrightness = value / 100f)
                    applyAppearance(save = false)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { saveAppearance() }
            })
        }
        refreshAppearance()
    }

    private fun saveAppearance() {
        getSharedPreferences("quest-cinema", MODE_PRIVATE).edit().putString("room", appearance.room.key)
            .putFloat("brightness", appearance.brightness).putBoolean("shadows", appearance.shadows)
            .putBoolean("screen-glow", appearance.screenGlow)
            .putBoolean("starlight", appearance.starlight).putFloat("star-brightness", appearance.starBrightness).apply()
    }

    private fun applyAppearance(save: Boolean = true) {
        appearance = appearance.normalized()
        environment?.update(appearance)
        if (save) saveAppearance()
        refreshAppearance()
    }

    private fun refreshAppearance() {
        val root = controls ?: return
        val inCinema = appearance.room == CinemaRoom.PRIVATE_CINEMA
        root.findViewById<Button>(R.id.quest_stars).apply {
            setText(if (appearance.starlight) R.string.quest_stars_on else R.string.quest_stars_off)
            isEnabled = inCinema
        }
        root.findViewById<SeekBar>(R.id.quest_star_slider).isEnabled = inCinema
        root.findViewById<TextView>(R.id.quest_star_label).text = if (inCinema) {
            getString(R.string.quest_star_level, (appearance.starBrightness * 100).toInt())
        } else getString(R.string.quest_stars_cinema_only)
        root.findViewById<TextView>(R.id.quest_light_label).text = getString(R.string.quest_light_level, (appearance.brightness * 100).toInt())
        root.findViewById<Button>(R.id.quest_shadows).setText(if (appearance.shadows) R.string.quest_shadows_on else R.string.quest_shadows_off)
        root.findViewById<Button>(R.id.quest_glow).setText(if (appearance.screenGlow) R.string.quest_glow_on else R.string.quest_glow_off)
        listOf(R.id.quest_scene_cinema to CinemaRoom.PRIVATE_CINEMA, R.id.quest_scene_space to CinemaRoom.ORBITAL_LOUNGE).forEach { (id, room) ->
            root.findViewById<Button>(id).backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (room == appearance.room) "#30697B" else "#303F55"))
        }
    }

    private fun updateGeometry(value: CinemaGeometry) {
        geometry = value
        screen?.setComponent(Transform(Pose(Vector3(0f, value.centerHeight, value.distance))))
        screen?.setComponent(Scale(Vector3(value.width / 6f)))
        screenFrame?.setComponent(Transform(Pose(Vector3(0f, value.centerHeight, value.distance + .08f))))
        screenFrame?.setComponent(Scale(Vector3(value.width / 6f)))
        getSharedPreferences("quest-cinema", MODE_PRIVATE).edit()
            .putFloat("width", value.width).putFloat("distance", value.distance).apply()
        refreshControls()
    }

    private fun navigateEpisode(next: Boolean) {
        if (switchingEpisode) return
        val service = playbackService ?: return
        switchingEpisode = true
        errorMessage = null
        episodeMessage = getString(R.string.quest_episode_switching)
        refreshControls()
        if (next) service.dispatchNext() else service.dispatchPrev()
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
        val manager = playbackService?.player()
        if (manager == null) {
            if (previewMode) {
                root.findViewById<TextView>(R.id.quest_title).setText(R.string.quest_preview_entry)
                root.findViewById<TextView>(R.id.quest_status).text = sceneStatus ?: getString(R.string.quest_preview_hint)
                listOf(R.id.quest_play, R.id.quest_rewind, R.id.quest_forward).forEach { root.findViewById<Button>(it).isEnabled = false }
            }
            return
        }
        val player = manager.player
        root.findViewById<Button>(R.id.quest_prev_episode).isEnabled = !switchingEpisode
        root.findViewById<Button>(R.id.quest_next_episode).isEnabled = !switchingEpisode
        root.findViewById<Button>(R.id.quest_auto_next).setText(
            if (CinemaMode.shouldAutoAdvance(this)) R.string.quest_auto_next_on else R.string.quest_auto_next_off,
        )
        root.findViewById<TextView>(R.id.quest_title).text = manager.mediaTitle
        root.findViewById<Button>(R.id.quest_play).setText(
            if (player.playWhenReady) R.string.quest_pause else R.string.quest_play,
        )
        root.findViewById<TextView>(R.id.quest_status).text = errorMessage ?: episodeMessage ?: sceneStatus ?: if (player.playbackState == Player.STATE_ENDED) getString(R.string.quest_episode_ended) else getString(
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
        environment?.close()
        environment = null
        screenFrame?.destroy()
        screenFrame = null
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        // The service owns this player. Only detach the cinema's output.
        playerView?.player = null
        playerView?.danmakuController?.release()
        playerView = null
        controls = null
        screen = null
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        closing = true
        environment?.close()
        environment = null
        ui.removeCallbacksAndMessages(null)
        playerView?.player = null
        playerView?.danmakuController?.release()
        playerView = null
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
        playbackService?.removePlayerCallback(playerCallback)
        if (bound) unbindService(this)
        bound = false
        playbackService = null
        super.onDestroy()
    }
}

package com.fongmi.quest.cinema

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.SeekBar
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.fongmi.android.tv.quest.CinemaFocusPolicy
import com.fongmi.android.tv.quest.CinemaGeometry
import com.fongmi.android.tv.quest.CinemaAppearance
import com.fongmi.android.tv.quest.CinemaEnvironment
import com.fongmi.android.tv.quest.CinemaRoom
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
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.vr.VRFeature

/** Standalone receiver for FongMi's external-player protocol. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StandaloneCinemaActivity : AppSystemActivity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var controls: View? = null
    private var screen: Entity? = null
    private var screenFrame: Entity? = null
    private var environment: CinemaEnvironment? = null
    private var appearance = CinemaAppearance()
    private var sceneStatus: String? = null
    private var previewMode = false
    private var geometry = CinemaGeometry()
    private var focusPolicy = CinemaFocusPolicy(true)
    private var closing = false
    private var errorMessage: String? = null
    private var title = ""
    private var lastPosition = 0L
    private var completed = false
    private val ui = Handler(Looper.getMainLooper())
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
        val uri = intent.data
        previewMode = intent.getBooleanExtra("preview_scene", false)
        if (!previewMode && (uri == null || uri.scheme?.lowercase() !in setOf("http", "https", "content", "file"))) {
            finish()
            return
        }
        val prefs = getSharedPreferences("quest-cinema", MODE_PRIVATE)
        geometry = CinemaGeometry.restored(prefs.getFloat("width", 6f), prefs.getFloat("distance", 6f))
        appearance = CinemaAppearance(
            CinemaRoom.fromKey(prefs.getString("room", null)), prefs.getFloat("brightness", .35f),
            prefs.getBoolean("shadows", true), prefs.getBoolean("screen-glow", true),
            prefs.getBoolean("starlight", true), prefs.getFloat("star-brightness", .5f),
        ).normalized()
        title = intent.getCharSequenceExtra("title")?.toString()
            ?: intent.getStringExtra("name") ?: getString(R.string.quest_cinema_title)
        focusPolicy = CinemaFocusPolicy(savedInstanceState?.getBoolean("resume", true) ?: true)
        lastPosition = savedInstanceState?.getLong("position") ?: PlaybackRequest.startPosition(intent.extras?.get("position"))
        if (previewMode) {
            title = getString(R.string.cinema_scene_preview)
            ui.post(tick)
            return
        }
        val extraHeaders = intent.getBundleExtra("extra_headers")
        val additional = extraHeaders?.keySet()?.mapNotNull { key ->
            (extraHeaders.get(key) as? String)?.let { key to it }
        }?.toMap() ?: emptyMap()
        val headers = PlaybackRequest.headers(intent.getStringArrayExtra("headers"), additional)
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            intent.type?.takeIf { it != "text/plain" && !it.contains('*') }?.let { setMimeType(it) }
        }.build()
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Do not display the stream URL, headers, or authorization tokens.
                    errorMessage = getString(R.string.cinema_playback_error, error.errorCodeName)
                    refreshControls()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    completed = state == Player.STATE_ENDED
                    refreshControls()
                }
            })
            setMediaItem(mediaItem, lastPosition)
            prepare()
        }
        attachPlayer()
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
                    shape = QuadShapeOptions(width = 2.4f, height = 1.10f),
                    display = DpDisplayOptions(width = 1280f, height = 584f, dpi = 160),
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
        Entity.create(Panel(R.id.quest_controls_panel), Transform(Pose(Vector3(0f, 0.85f, 3f))))
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

    private fun attachPlayer() {
        if (closing) return
        playerView?.player = player
        if (player != null && playerView != null && focusPolicy.shouldResume()) {
            player?.play()
            focusPolicy.resumed()
        }
    }

    private fun configureControls(root: View) {
        root.findViewById<Button>(R.id.quest_play).setOnClickListener {
            player?.let {
                if (it.playWhenReady) it.pause()
                else if (focusPolicy.focused) {
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    if (it.playbackState == Player.STATE_IDLE) {
                        errorMessage = null
                        it.prepare()
                    }
                    it.play()
                }
            }
            refreshControls()
        }
        root.findViewById<Button>(R.id.quest_rewind).setOnClickListener { seek(-10_000) }
        root.findViewById<Button>(R.id.quest_forward).setOnClickListener { seek(10_000) }
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

    private fun seek(delta: Long) {
        val current = player ?: return
        if (!current.isCurrentMediaItemSeekable) return
        val end = current.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        current.seekTo((current.currentPosition + delta).coerceIn(0, end))
        refreshControls()
    }

    private fun refreshControls() {
        val root = controls ?: return
        val current = player
        if (current != null) lastPosition = current.currentPosition
        root.findViewById<TextView>(R.id.quest_title).text = title
        root.findViewById<Button>(R.id.quest_play).setText(if (current?.playWhenReady == true) R.string.quest_pause else R.string.quest_play)
        root.findViewById<Button>(R.id.quest_play).isEnabled = current != null
        root.findViewById<Button>(R.id.quest_rewind).isEnabled = current?.isCurrentMediaItemSeekable == true
        root.findViewById<Button>(R.id.quest_forward).isEnabled = current?.isCurrentMediaItemSeekable == true
        root.findViewById<TextView>(R.id.quest_status).text = errorMessage ?: sceneStatus ?: if (current == null) {
            getString(R.string.cinema_preview_hint)
        } else if (current.playbackState == Player.STATE_BUFFERING) {
            getString(R.string.cinema_buffering)
        } else getString(R.string.quest_status_format, time(current.currentPosition), time(current.duration), geometry.width, geometry.distance)
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
        focusPolicy.lose(player?.playWhenReady)
        player?.pause()
    }

    override fun onPause() {
        pauseForFocusLoss()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: lastPosition)
        outState.putBoolean("resume", player?.playWhenReady == true || focusPolicy.wantsResume)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        val result = Intent().putExtra("position", (player?.currentPosition ?: lastPosition).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .putExtra("end_by", if (completed) "playback_completion" else "user")
        setResult(RESULT_OK, result)
        super.finish()
    }

    override fun onSpatialShutdown() {
        environment?.close()
        environment = null
        screenFrame?.destroy()
        screenFrame = null
        pauseForFocusLoss()
        playerView?.player = null
        playerView = null
        screen = null
        controls = null
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        closing = true
        environment?.close()
        environment = null
        ui.removeCallbacksAndMessages(null)
        lastPosition = player?.currentPosition ?: lastPosition
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

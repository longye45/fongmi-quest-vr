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
import android.widget.LinearLayout
import android.widget.GridLayout
import android.text.TextUtils
import android.util.TypedValue
import com.fongmi.android.tv.playback.vod.CinemaPlaylist
import com.meta.spatial.toolkit.TransformParent
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
    private var roomRoot: Entity? = null
    private var controlEntity: Entity? = null
    private var placement = CinemaPlacement()
    private var anchor: Pose? = null
    private var restoreAnchor = false
    private var recenterSeconds = 0
    private var episodesOpen = false
    private var playlist = CinemaPlaylist.empty()
    private var browsedLine: String? = null
    private var episodePage = 0
    private var renderedSwitching = false
    private val episodesPerPage = 16
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
            if (focusPolicy.focused && restoreAnchor) restorePlacement()
            refreshControls()
            ui.postDelayed(this, 500)
        }
    }

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("quest-cinema", MODE_PRIVATE)
        loadProfile(CinemaRoom.fromKey(prefs.all["room"] as? String))
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
        roomRoot = Entity.create(Transform())
        screen = Entity.create(
            Panel(R.id.quest_screen_panel), TransformParent(roomRoot!!),
            Transform(placement.screenPose(geometry)),
            Scale(Vector3(geometry.width / 6f)),
        )
        screenFrame = Entity.create(
            Mesh(android.net.Uri.parse("apk:///scenes/screen_frame.glb"), hittable = MeshCollision.NoCollision),
            TransformParent(roomRoot!!), Transform(placement.screenPose(geometry, frame = true)),
            Scale(Vector3(geometry.width / 6f)),
        )
        controlEntity = Entity.create(Panel(R.id.quest_controls_panel), TransformParent(roomRoot!!),
            Transform(Pose(Vector3(0f, 0.55f, 3f))), Scale(Vector3(.8f)))
        // The first FOCUSED state may precede or follow scene creation.
        if (focusPolicy.focused) restorePlacement()
        environment = CinemaEnvironment(this, parent = roomRoot, onRoomState = { _, loading, failed ->
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
        root.findViewById<View>(R.id.quest_tabs).visibility = View.VISIBLE
        root.findViewById<View>(R.id.quest_placement_controls).visibility = View.VISIBLE
        root.findViewById<Button>(R.id.quest_tab_settings).setOnClickListener { showEpisodes(false) }
        root.findViewById<Button>(R.id.quest_tab_episodes).apply {
            isEnabled = !previewMode
            setOnClickListener { showEpisodes(true) }
        }
        root.findViewById<Button>(R.id.quest_episode_page_prev).setOnClickListener {
            episodePage = (episodePage - 1).coerceAtLeast(0)
            renderEpisodes()
        }
        root.findViewById<Button>(R.id.quest_episode_page_next).setOnClickListener {
            episodePage++
            renderEpisodes()
        }
        root.findViewById<Button>(R.id.quest_recenter).setOnClickListener { scheduleRecenter() }
        root.findViewById<Button>(R.id.quest_height_down).setOnClickListener { changePlacement(placement.copy(height = placement.height - .25f)) }
        root.findViewById<Button>(R.id.quest_height_up).setOnClickListener { changePlacement(placement.copy(height = placement.height + .25f)) }
        root.findViewById<Button>(R.id.quest_tilt_up).setOnClickListener { changePlacement(placement.copy(tilt = placement.tilt - 5f)) }
        root.findViewById<Button>(R.id.quest_tilt_down).setOnClickListener { changePlacement(placement.copy(tilt = placement.tilt + 5f)) }
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
        root.findViewById<Button>(R.id.quest_reset).setOnClickListener {
            cancelRecenter()
            placement = CinemaPlacement()
            anchor = null
            restoreAnchor = false
            updateGeometry(CinemaGeometry())
        }
        root.findViewById<Button>(R.id.quest_scene_cinema).setOnClickListener {
            switchRoom(CinemaRoom.PRIVATE_CINEMA)
        }
        root.findViewById<Button>(R.id.quest_scene_space).setOnClickListener {
            switchRoom(CinemaRoom.ORBITAL_LOUNGE)
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
        applyGeometry()
    }

    private fun loadProfile(room: CinemaRoom) {
        val profile = CinemaProfile.restored(getSharedPreferences("quest-cinema", MODE_PRIVATE).all, room)
        geometry = profile.geometry
        appearance = profile.appearance
        placement = profile.placement
        anchor = null
        restoreAnchor = placement.calibrated
    }

    private fun saveAppearance() {
        val editor = getSharedPreferences("quest-cinema", MODE_PRIVATE).edit()
        CinemaProfile(geometry, appearance, placement).values().forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        editor.apply()
    }

    private fun switchRoom(room: CinemaRoom) {
        cancelRecenter()
        saveAppearance()
        if (room != appearance.room) loadProfile(room)
        if (focusPolicy.focused) restorePlacement()
        applyGeometry()
        applyAppearance()
        controls?.findViewById<SeekBar>(R.id.quest_light_slider)?.progress = (appearance.brightness * 100).toInt()
        controls?.findViewById<SeekBar>(R.id.quest_star_slider)?.progress = (appearance.starBrightness * 100).toInt()
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
        geometry = CinemaGeometry.restored(value.width, value.distance)
        placement = placement.normalized(geometry)
        applyGeometry()
        saveAppearance()
        refreshControls()
    }

    private fun applyGeometry() {
        screen?.setComponent(Transform(placement.screenPose(geometry)))
        screen?.setComponent(Scale(Vector3(geometry.width / 6f)))
        screenFrame?.setComponent(Transform(placement.screenPose(geometry, frame = true)))
        screenFrame?.setComponent(Scale(Vector3(geometry.width / 6f)))
        roomRoot?.setComponent(Transform(anchor?.let { placement.roomPose(geometry, it) } ?: Pose()))
        controls?.findViewById<TextView>(R.id.quest_placement_hint)?.text = getString(
            R.string.quest_placement_status, placement.height, placement.tilt,
        )
    }

    private fun changePlacement(value: CinemaPlacement) {
        placement = value.normalized(geometry)
        applyGeometry()
        saveAppearance()
    }

    private fun restorePlacement() {
        if (roomRoot == null || !restoreAnchor) return
        val viewer = scene.getViewerPose()
        if (!CinemaPlacement.usable(viewer)) return
        anchor = placement.restoredAnchor(viewer)
        restoreAnchor = false
        applyGeometry()
    }

    private val recenterTick = object : Runnable {
        override fun run() {
            if (closing || !focusPolicy.focused || roomRoot == null) {
                cancelRecenter()
                return
            }
            if (recenterSeconds > 0) {
                controls?.findViewById<TextView>(R.id.quest_status)?.text = getString(R.string.quest_recenter_countdown, recenterSeconds)
                recenterSeconds--
                ui.postDelayed(this, 1000)
            } else {
                val viewer = scene.getViewerPose()
                if (!CinemaPlacement.usable(viewer)) {
                    cancelRecenter()
                    Toast.makeText(this@CinemaActivity, R.string.quest_recenter_cancelled, Toast.LENGTH_SHORT).show()
                    return
                }
                placement = CinemaPlacement.recentered(viewer).normalized(geometry)
                anchor = viewer.copy()
                restoreAnchor = false
                applyGeometry()
                saveAppearance()
                controls?.findViewById<Button>(R.id.quest_recenter)?.isEnabled = true
                Toast.makeText(this@CinemaActivity, R.string.quest_recenter_done, Toast.LENGTH_SHORT).show()
                refreshControls()
            }
        }
    }

    private fun scheduleRecenter() {
        if (!focusPolicy.focused || roomRoot == null) return
        ui.removeCallbacks(recenterTick)
        recenterSeconds = 3
        controls?.findViewById<Button>(R.id.quest_recenter)?.isEnabled = false
        Toast.makeText(this, getString(R.string.quest_recenter_countdown, 3), Toast.LENGTH_LONG).show()
        recenterTick.run()
    }

    private fun cancelRecenter() {
        ui.removeCallbacks(recenterTick)
        recenterSeconds = 0
        controls?.findViewById<Button>(R.id.quest_recenter)?.isEnabled = true
    }

    private fun showEpisodes(open: Boolean) {
        episodesOpen = open
        controls?.findViewById<View>(R.id.quest_settings_page)?.visibility = if (open) View.GONE else View.VISIBLE
        controls?.findViewById<View>(R.id.quest_episodes_page)?.visibility = if (open) View.VISIBLE else View.GONE
        if (open) updatePlaylist(jumpToSelected = true)
    }

    private fun updatePlaylist(jumpToSelected: Boolean = false) {
        if (!episodesOpen) return
        val updated = playbackService?.cinemaPlaylist() ?: CinemaPlaylist.empty()
        if (updated == playlist && !jumpToSelected && renderedSwitching == switchingEpisode) return
        val changedContext = updated.contextKey() != playlist.contextKey()
        playlist = updated
        if (jumpToSelected || changedContext || updated.lines().none { it.key() == browsedLine }) {
            val line = updated.lines().firstOrNull { it.selected() } ?: updated.lines().firstOrNull()
            browsedLine = line?.key()
            episodePage = ((line?.episodes()?.indexOfFirst { it.selected() } ?: 0).coerceAtLeast(0)) / episodesPerPage
        }
        renderEpisodes()
    }

    private fun episodeButton(label: String, selected: Boolean = false): Button = Button(controls?.context ?: this).apply {
        text = label
        textSize = 22f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (selected) "#30697B" else "#303F55"))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isEnabled = !switchingEpisode
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), (controls?.resources ?: resources).displayMetrics).toInt()

    private fun renderEpisodes() {
        val root = controls ?: return
        renderedSwitching = switchingEpisode
        val lines = root.findViewById<LinearLayout>(R.id.quest_lines)
        lines.removeAllViews()
        playlist.lines().forEach { line ->
            lines.addView(episodeButton(line.label(), line.key() == browsedLine).apply {
                layoutParams = LinearLayout.LayoutParams(dp(220), dp(64)).apply { marginEnd = dp(8) }
                setOnClickListener { browsedLine = line.key(); episodePage = 0; renderEpisodes() }
            })
        }
        val line = playlist.lines().firstOrNull { it.key() == browsedLine }
        val items = line?.episodes().orEmpty()
        val pages = ((items.size + episodesPerPage - 1) / episodesPerPage).coerceAtLeast(1)
        episodePage = episodePage.coerceIn(0, pages - 1)
        root.findViewById<TextView>(R.id.quest_episode_hint).text = if (items.isEmpty()) getString(R.string.quest_episode_empty)
            else getString(R.string.quest_episode_count, line?.label().orEmpty(), items.size)
        val grid = root.findViewById<GridLayout>(R.id.quest_episode_grid)
        grid.removeAllViews()
        val contextKey = playlist.contextKey()
        items.drop(episodePage * episodesPerPage).take(episodesPerPage).forEachIndexed { index, item ->
            val number = episodePage * episodesPerPage + index + 1
            val label = item.label().ifBlank { getString(R.string.quest_episode_number, number) }
            grid.addView(episodeButton(label, item.selected()).apply {
                contentDescription = if (item.selected()) getString(R.string.quest_episode_selected, label) else label
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(index / 4), GridLayout.spec(index % 4, 1f)).apply {
                    width = 0; height = dp(64); setMargins(dp(4), 0, dp(4), dp(4))
                }
                setOnClickListener {
                    if (switchingEpisode) return@setOnClickListener
                    errorMessage = null
                    if (playbackService?.selectCinemaEpisode(contextKey, line!!.key(), item.url()) != true) {
                        Toast.makeText(this@CinemaActivity, R.string.quest_episode_stale, Toast.LENGTH_SHORT).show()
                    }
                    updatePlaylist()
                    refreshControls()
                }
            })
        }
        root.findViewById<TextView>(R.id.quest_episode_page_label).text = getString(R.string.quest_page_label, episodePage + 1, pages)
        root.findViewById<Button>(R.id.quest_episode_page_prev).isEnabled = episodePage > 0 && !switchingEpisode
        root.findViewById<Button>(R.id.quest_episode_page_next).isEnabled = episodePage + 1 < pages && !switchingEpisode
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
        if (!root.findViewById<Button>(R.id.quest_recenter).isEnabled) return
        updatePlaylist()
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
                restorePlacement()
                attachPlayer()
            } else pauseForFocusLoss()
        }
    }

    private fun pauseForFocusLoss() {
        cancelRecenter()
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
        cancelRecenter()
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
        screen?.destroy()
        screen = null
        controlEntity?.destroy()
        controlEntity = null
        roomRoot?.destroy()
        roomRoot = null
        super.onSpatialShutdown()
    }

    override fun onDestroy() {
        closing = true
        saveAppearance()
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

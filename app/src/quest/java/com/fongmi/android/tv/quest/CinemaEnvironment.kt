package com.fongmi.android.tv.quest

import android.animation.ValueAnimator
import android.graphics.Color
import android.net.Uri
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.okhttp3.OkHttpAssetFetcher
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.NetworkedAssetLoader
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Light
import com.meta.spatial.toolkit.LightType
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.ProjectionLight
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import java.io.File

/** Two embedded, fully modeled rooms. Only two shadow-casting lights are active at a time. */
class CinemaEnvironment(
    private val activity: AppSystemActivity,
    private val onRoomState: (CinemaRoom, Boolean, Boolean) -> Unit,
    private val onStarlightError: () -> Unit = {},
) {
    private val rooms = mutableMapOf<CinemaRoom, Entity>()
    private val loaded = mutableSetOf<CinemaRoom>()
    private val lights = mutableListOf<Entity>()
    private var ceiling: Entity? = null
    private var ceilingMaterials: List<SceneMaterial> = emptyList()
    private var ceilingLoaded = false
    private var screen: Entity? = null
    private var desired = CinemaAppearance()
    private var ready = false
    private var closed = false
    private var shown: CinemaRoom? = null
    private var animator: ValueAnimator? = null
    private var currentBrightness = .35f

    fun start(screenEntity: Entity, appearance: CinemaAppearance) {
        if (closed || ready) return
        screen = screenEntity
        ready = true
        NetworkedAssetLoader.init(File(activity.cacheDir, "cinema-models").apply { mkdirs() }, OkHttpAssetFetcher())
        activity.scene.updateIBLEnvironment("scenes/studio.env")
        val locations = listOf(Vector3(-4.7f, 6.7f, -1f), Vector3(4.7f, 6.7f, -1f), Vector3(0f, 6.9f, 8f))
        locations.forEach { p -> lights += Entity.create(Light(), Transform(Pose(p))) }
        update(appearance, animate = false)
    }

    fun update(appearance: CinemaAppearance, animate: Boolean = true) {
        desired = appearance.normalized()
        if (!ready || closed) return
        requestRoom(desired.room)
        updateStarlight()
        screen?.setComponent(ProjectionLight(
            intensity = .85f,
            range = 20f,
            smoothingSpeed = .10f,
            jitterEnabled = false,
            enabled = desired.screenGlow,
            useGpuSampling = true,
        ))
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(currentBrightness, desired.brightness).apply {
                duration = 180
                addUpdateListener { if (!closed) applyLighting(it.animatedValue as Float) }
                start()
            }
        } else applyLighting(desired.brightness)
    }

    private fun updateStarlight() {
        if (closed) return
        if (ceiling == null && desired.starlightVisible) {
            val entity = Entity.create(
                Mesh(Uri.parse("apk:///scenes/starlight_ceiling.glb"), hittable = MeshCollision.NoCollision),
                Transform(), Visible(false),
            )
            ceiling = entity
            activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.whenComplete { object3d, error ->
                activity.runOnUiThread {
                    if (closed) return@runOnUiThread
                    val materials = object3d?.mesh?.materials
                    if (error != null || materials.isNullOrEmpty()) {
                        Log.e("CinemaEnvironment", "Unable to load starlight ceiling", error)
                        ceiling?.destroy()
                        ceiling = null
                        onStarlightError()
                    } else {
                        ceilingMaterials = materials.toList()
                        ceilingLoaded = true
                        updateStarlight()
                    }
                }
            }
        }
        val brightness = desired.starBrightness
        ceilingMaterials.forEach { it.setAlbedoColor(Color.valueOf(.68f * brightness, .82f * brightness, brightness, 1f)) }
        ceiling?.setComponent(Visible(ceilingLoaded && shown == CinemaRoom.PRIVATE_CINEMA && desired.starlightVisible))
    }

    private fun requestRoom(room: CinemaRoom) {
        if (room in loaded) {
            show(room)
            return
        }
        if (room in rooms) return
        onRoomState(room, true, false)
        val entity = Entity.create(
            Mesh(Uri.parse("apk:///scenes/${room.asset}"), hittable = MeshCollision.NoCollision),
            Transform(), Visible(false),
        )
        rooms[room] = entity
        activity.systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.whenComplete { object3d, error ->
            activity.runOnUiThread {
                if (closed) return@runOnUiThread
                if (error != null || object3d?.mesh == null) {
                    Log.e("CinemaEnvironment", "Unable to load ${room.asset}", error)
                    rooms.remove(room)?.destroy()
                    if (desired.room == room) onRoomState(room, false, true)
                } else {
                    loaded += room
                    if (desired.room == room) show(room)
                }
            }
        }
    }

    private fun show(room: CinemaRoom) {
        if (closed || room !in loaded || desired.room != room) return
        if (shown != room) {
            rooms.forEach { (key, entity) -> entity.setComponent(Visible(key == room)) }
            shown = room
        }
        updateStarlight()
        onRoomState(room, false, false)
    }

    private fun applyLighting(brightness: Float) {
        currentBrightness = brightness
        val color = if (desired.room == CinemaRoom.PRIVATE_CINEMA) Vector3(1f, .72f, .43f) else Vector3(.43f, .70f, 1f)
        val ambient = .012f + .14f * brightness
        activity.scene.setLightingEnvironment(
            ambientColor = Vector3(ambient, ambient, ambient),
            sunColor = Vector3(0f),
            sunDirection = Vector3(0f, -1f, 0f),
            environmentIntensity = .025f + .32f * brightness,
        )
        lights.forEachIndexed { index, entity ->
            entity.setComponent(Light(
                type = LightType.SPOT,
                color = color,
                intensity = brightness * if (index < 2) 18f else 8f,
                range = 23f,
                direction = Vector3(0f, -1f, -.12f),
                spotOuterAngle = 78f,
                spotInnerAngle = 40f,
                castsShadow = desired.shadows && index < 2,
                enabled = brightness > .001f,
            ))
        }
    }

    fun close() {
        if (closed) return
        closed = true
        animator?.cancel()
        animator = null
        screen?.tryRemoveComponent<ProjectionLight>()
        screen = null
        ceiling?.destroy()
        ceiling = null
        ceilingMaterials = emptyList()
        ceilingLoaded = false
        lights.forEach { it.destroy() }
        lights.clear()
        rooms.values.forEach { it.destroy() }
        rooms.clear()
        loaded.clear()
    }
}

package com.fongmi.android.tv.quest

/** Pure settings codec so scene independence and upgrades can be checked without a headset. */
data class CinemaProfile(
    val geometry: CinemaGeometry = CinemaGeometry(),
    val appearance: CinemaAppearance = CinemaAppearance(),
    val placement: CinemaPlacement = CinemaPlacement(),
) {
    fun normalized() = copy(
        geometry = CinemaGeometry.restored(geometry.width, geometry.distance),
        appearance = appearance.normalized(),
        placement = placement.normalized(CinemaGeometry.restored(geometry.width, geometry.distance)),
    )

    fun values(): Map<String, Any> {
        val profile = normalized()
        val a = profile.appearance
        val v = profile.placement
        val prefix = "profile.${a.room.key}."
        return mapOf(
            "version" to 1, "width" to profile.geometry.width, "distance" to profile.geometry.distance,
            "brightness" to a.brightness, "shadows" to a.shadows, "screen-glow" to a.screenGlow,
            "starlight" to a.starlight, "star-brightness" to a.starBrightness,
            "calibrated" to v.calibrated, "qw" to v.w, "qx" to v.x, "qy" to v.y, "qz" to v.z,
            "height" to v.height, "tilt" to v.tilt,
        ).mapKeys { prefix + it.key } + ("room" to a.room.key)
    }

    companion object {
        fun restored(values: Map<String, *>, room: CinemaRoom): CinemaProfile {
            val prefix = "profile.${room.key}."
            val hasProfile = values[prefix + "version"] is Number
            // Import old global settings only into the room they actually belonged to.
            val legacy = !hasProfile && room == CinemaRoom.fromKey(values["room"] as? String)
            fun value(key: String): Any? = when {
                hasProfile -> values[prefix + key]
                legacy -> values[key]
                else -> null
            }
            fun f(key: String, fallback: Float) = (value(key) as? Number)?.toFloat() ?: fallback
            fun b(key: String, fallback: Boolean) = value(key) as? Boolean ?: fallback
            return CinemaProfile(
                CinemaGeometry.restored(f("width", 6f), f("distance", 6f)),
                CinemaAppearance(room, f("brightness", .35f), b("shadows", true), b("screen-glow", true),
                    b("starlight", true), f("star-brightness", .5f)),
                CinemaPlacement(b("calibrated", false), f("qw", 1f), f("qx", 0f), f("qy", 0f),
                    f("qz", 0f), f("height", 0f), f("tilt", 0f)),
            ).normalized()
        }
    }
}

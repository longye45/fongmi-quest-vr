package com.fongmi.android.tv.quest

enum class CinemaRoom(val key: String, val asset: String) {
    PRIVATE_CINEMA("cinema", "private_cinema.glb"),
    ORBITAL_LOUNGE("space", "orbital_lounge.glb");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: PRIVATE_CINEMA
    }
}

data class CinemaAppearance(
    val room: CinemaRoom = CinemaRoom.PRIVATE_CINEMA,
    val brightness: Float = .35f,
    val shadows: Boolean = true,
    val screenGlow: Boolean = true,
) {
    fun normalized() = copy(brightness = brightness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f)
    fun dimmed(delta: Float) = copy(brightness = brightness + delta).normalized()
}

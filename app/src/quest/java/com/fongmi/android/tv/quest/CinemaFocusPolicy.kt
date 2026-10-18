package com.fongmi.android.tv.quest

/** Repeated VISIBLE/onPause events must not overwrite the intent to resume after the menu closes. */
class CinemaFocusPolicy(initialResume: Boolean) {
    var focused = false
        private set
    private var pendingResume = initialResume
    val wantsResume get() = pendingResume

    fun gain() { focused = true }
    fun shouldResume() = focused && pendingResume
    fun resumed() { pendingResume = false }
    fun lose(playWhenReady: Boolean?) {
        if (focused && !pendingResume) pendingResume = playWhenReady == true
        focused = false
    }
}

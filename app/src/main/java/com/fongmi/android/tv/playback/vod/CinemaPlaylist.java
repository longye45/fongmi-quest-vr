package com.fongmi.android.tv.playback.vod;

import java.util.List;

/** Immutable UI snapshot. Selection is checked again against the current controller state. */
public record CinemaPlaylist(String contextKey, List<Line> lines) {
    public CinemaPlaylist { lines = List.copyOf(lines); }
    public static CinemaPlaylist empty() { return new CinemaPlaylist("", List.of()); }
    public record Line(String key, String label, boolean selected, List<Item> episodes) {
        public Line { episodes = List.copyOf(episodes); }
    }
    public record Item(String label, String url, boolean selected) {}
}

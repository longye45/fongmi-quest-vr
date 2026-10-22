package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CinemaPlaylistTest {
    private static Episode episode(String name, String url, boolean selected) {
        Episode item = new Episode() {
            @Override public String getName() { return name; }
            @Override public String getUrl() { return url; }
        };
        item.setSelected(selected);
        return item;
    }

    private static Flag line(String key, Episode... episodes) {
        Flag flag = new Flag() {
            @Override public String getFlag() { return key; }
            @Override public String getShow() { return "Line " + key; }
        };
        flag.getEpisodes().addAll(List.of(episodes));
        return flag;
    }

    private VodPlaybackController controller(VodPlaybackState state) {
        VodPlaybackHost host = (VodPlaybackHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{VodPlaybackHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getHistoryKey")) return "site:movie";
                    if (method.getName().equals("isHostFinishing")) return false;
                    throw new AssertionError("Unexpected playback side effect: " + method.getName());
                });
        return new VodPlaybackController(host, null, state);
    }

    @Test public void snapshotsAreImmutableEvenWhenSourceListsChange() {
        List<CinemaPlaylist.Item> items = new ArrayList<>();
        items.add(new CinemaPlaylist.Item("Episode 1", "url1", true));
        List<CinemaPlaylist.Line> lines = new ArrayList<>();
        lines.add(new CinemaPlaylist.Line("a", "A", true, items));
        CinemaPlaylist snapshot = new CinemaPlaylist("movie", lines);
        items.clear(); lines.clear();
        assertEquals(1, snapshot.lines().get(0).episodes().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.lines().get(0).episodes().clear());
    }

    @Test public void changedMovieLineOrEpisodeIsRejectedBeforePlaybackSideEffects() {
        VodPlaybackState state = new VodPlaybackState();
        state.setFlags(List.of(line("a", episode("1", "url1", true))));
        VodPlaybackController controller = controller(state);
        assertFalse(controller.selectCinemaEpisode("different-movie", "a", "url1"));
        assertFalse(controller.selectCinemaEpisode("site:movie", "removed-line", "url1"));
        assertFalse(controller.selectCinemaEpisode("site:movie", "a", "removed-url"));
    }

    @Test public void selectingCurrentlyPlayingEpisodeDoesNotResolveOrRestartIt() {
        VodPlaybackState state = new VodPlaybackState();
        Flag selected = line("a", episode("1", "url1", true));
        selected.setSelected(selected);
        state.setFlags(List.of(selected));
        assertTrue(controller(state).selectCinemaEpisode("site:movie", "a", "url1"));
    }

    @Test public void refreshedEpisodesUseStableUrlsAndReturnFreshSelection() {
        VodPlaybackState state = new VodPlaybackState();
        Episode one = episode("First", "url1", true);
        Flag selected = line("a", one, episode("Second", "url2", false));
        selected.setSelected(selected);
        state.setFlags(List.of(selected));
        VodPlaybackController controller = controller(state);
        CinemaPlaylist before = controller.cinemaPlaylist();
        selected.getEpisodes().remove(0);
        assertFalse(controller.selectCinemaEpisode("site:movie", "a", "url1"));
        assertEquals("url1", before.lines().get(0).episodes().get(0).url());
        assertEquals("url2", controller.cinemaPlaylist().lines().get(0).episodes().get(0).url());
    }
}

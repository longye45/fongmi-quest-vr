package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

import com.fongmi.android.tv.BuildConfig;

/** Keeps the regular mobile and TV variants independent of the Quest runtime. */
public final class CinemaMode {
    public static final String EXTRA_KEY = "cinema.playbackKey";
    public static final String EXTRA_RESUME = "cinema.resume";

    private CinemaMode() {}

    public static boolean isAvailable() {
        return "quest".equals(BuildConfig.FLAVOR);
    }

    public static boolean start(Activity activity, String key, boolean resume) {
        if (!isAvailable()) return false;
        Intent intent = new Intent().setClassName(activity, "com.fongmi.android.tv.quest.CinemaActivity");
        intent.putExtra(EXTRA_KEY, key);
        intent.putExtra(EXTRA_RESUME, resume);
        intent.addCategory("com.oculus.intent.category.VR");
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}

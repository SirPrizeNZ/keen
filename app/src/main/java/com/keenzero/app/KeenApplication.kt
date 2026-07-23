package com.keenzero.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.continuity.ContinuityStore
import com.keenzero.app.diagnostics.MemoryPressureDiagnostics
import com.keenzero.app.favourites.FavouritesStore
import com.keenzero.app.sitepacks.SitePackRuntime

/**
 * Minimal process entry. The tiny bundled blocker compiles on its own worker;
 * first native frame stays independent of Chromium and filter parsing.
 */
class KeenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":torrent")) {
            Log.i(TAG, "Torrent process: skipping browser runtime initialisation")
            return
        }
        pruneSeededDemoContent()
        BlockingRuntime.initialize(this)
        SitePackRuntime.initialize(this)
        Log.i(TAG, "KeenApplication onCreate build=${BuildConfig.BUILD_ID}")
    }

    /**
     * One-time removal of the debug/lab "UI preview" seed (see
     * KeenActivity.EXTRA_LAB_UI_PREVIEW) that was left in a device's saved data after
     * a demo-video recording: four placeholder favourites and the "Nocturne" Continue
     * watching card. Runs once, guarded by a pref flag, and touches only those exact
     * demo hosts / contentId — real favourites (1337x, etc.) and real watch history
     * are untouched.
     */
    private fun pruneSeededDemoContent() {
        val flags = getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_PRUNED_DEMO_SEED, false)) return
        try {
            val favsRemoved = FavouritesStore(this).removeHosts(DEMO_FAV_HOSTS)
            val recentsRemoved = ContinuityStore(this).removeByContentId(DEMO_CONTENT_IDS)
            Log.i(TAG, "Pruned demo seed: favs=$favsRemoved recents=$recentsRemoved")
        } catch (t: Throwable) {
            Log.w(TAG, "Demo-seed prune failed", t)
        }
        flags.edit().putBoolean(KEY_PRUNED_DEMO_SEED, true).apply()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryPressureDiagnostics.record(this, level, "application")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryPressureDiagnostics.recordLowMemory(this, "application")
    }

    companion object {
        private const val TAG = "KeenZero"
        private const val MIGRATION_PREFS = "keen_migrations"
        private const val KEY_PRUNED_DEMO_SEED = "pruned_demo_seed_v1"
        /** Hosts seeded by the lab UI-preview intent (never real user favourites). */
        private val DEMO_FAV_HOSTS = setOf(
            "github.com",
            "en.wikipedia.org",
            "news.ycombinator.com",
            "www.nasa.gov",
        )
        /** contentId of the seeded "Nocturne" Continue watching card. */
        private val DEMO_CONTENT_IDS = setOf("keen-ui-preview")
    }
}

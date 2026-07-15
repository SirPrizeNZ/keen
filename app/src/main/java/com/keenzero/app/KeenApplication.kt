package com.keenzero.app

import android.app.Application
import android.util.Log
import com.keenzero.app.blocking.BlockingRuntime
import com.keenzero.app.sitepacks.SitePackRuntime

/**
 * Minimal process entry. The tiny bundled blocker compiles on its own worker;
 * first native frame stays independent of Chromium and filter parsing.
 */
class KeenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BlockingRuntime.initialize(this)
        SitePackRuntime.initialize(this)
        Log.i(TAG, "KeenApplication onCreate build=${BuildConfig.BUILD_ID}")
    }

    companion object {
        private const val TAG = "KeenZero"
    }
}

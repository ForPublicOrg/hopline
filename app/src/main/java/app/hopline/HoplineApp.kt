package app.hopline

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.hopline.service.Core

class HoplineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        // Track whether any screen is visible, so we don't notify about the chat you're reading.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var visible = 0
            override fun onActivityStarted(a: Activity) { visible++; Core.appVisible = true }
            override fun onActivityStopped(a: Activity) { visible--; if (visible <= 0) { visible = 0; Core.appVisible = false } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // The service restarts itself (START_STICKY); screens call Core.ensureRunning() when shown.
    }

    /** Leaving the screen (or memory getting tight) is when a phone may be killed: save now. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) Core.flushSave()
        // Long enough in the background to be a candidate for killing: the left group's chat that
        // was being read is only a copy of what is on disk, and is read again on the way back in.
        if (level >= TRIM_MEMORY_BACKGROUND) Core.dropArchive()
    }
}

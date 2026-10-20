package com.rendox.grocerygenius

import android.app.Application
import com.rendox.grocerygenius.sync.work.initializers.Sync
import com.rendox.grocerygenius.sync.work.initializers.WebDavSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GroceryGeniusApp : Application() {
    @Inject
    lateinit var webDavSyncScheduler: WebDavSyncScheduler

    override fun onCreate() {
        super.onCreate()
        // Initialize Sync; the system responsible for keeping data in the app up to date.
        Sync.initialize(context = this)
        // Keeps the user's lists in sync with their WebDAV server if they enabled it
        webDavSyncScheduler.initialize()
    }
}
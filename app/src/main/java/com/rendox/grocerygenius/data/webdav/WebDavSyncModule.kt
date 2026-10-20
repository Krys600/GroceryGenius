package com.rendox.grocerygenius.data.webdav

import android.content.Context
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.sync.webdav.FileSyncBaseStore
import com.rendox.grocerygenius.sync.webdav.LocalSyncStore
import com.rendox.grocerygenius.sync.webdav.SyncBaseStore
import com.rendox.grocerygenius.sync.webdav.SyncSnapshotSerializer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The directory in the app's files directory that holds the sync state.
 * It is excluded from backups together with the sync settings.
 */
const val WEBDAV_SYNC_DIRECTORY = "webdav_sync"

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavSyncModule {
    @Binds
    @Singleton
    abstract fun bindWebDavSyncRepository(webDavSyncRepositoryImpl: WebDavSyncRepositoryImpl): WebDavSyncRepository

    @Binds
    abstract fun bindLocalSyncStore(roomLocalSyncStore: RoomLocalSyncStore): LocalSyncStore
}

@Module
@InstallIn(SingletonComponent::class)
object WebDavSyncStorageModule {
    @Provides
    @Singleton
    fun provideSyncBaseStore(
        @ApplicationContext context: Context,
        serializer: SyncSnapshotSerializer,
        @Dispatcher(GroceryGeniusDispatchers.IO) ioDispatcher: CoroutineDispatcher
    ): SyncBaseStore = FileSyncBaseStore(
        file = File(File(context.filesDir, WEBDAV_SYNC_DIRECTORY), "base.json"),
        serializer = serializer,
        ioDispatcher = ioDispatcher
    )
}
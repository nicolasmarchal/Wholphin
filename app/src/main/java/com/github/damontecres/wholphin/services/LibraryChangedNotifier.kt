package com.github.damontecres.wholphin.services

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jellyfin.sdk.model.api.LibraryUpdateInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local signal for Jellyfin library changes received on the existing server WebSocket.
 *
 * Events are intentionally not replayed: consumers refresh when visible and use their bounded
 * polling path after lifecycle restarts. A slow visible consumer receives the most recent update.
 */
@Singleton
class LibraryChangedNotifier
    @Inject
    constructor() {
        private val _events =
            MutableSharedFlow<LibraryUpdateInfo>(
                replay = 0,
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        val events: SharedFlow<LibraryUpdateInfo> = _events.asSharedFlow()

        internal fun notify(update: LibraryUpdateInfo) {
            _events.tryEmit(update)
        }
    }

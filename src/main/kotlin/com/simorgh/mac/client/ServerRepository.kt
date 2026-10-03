package com.simorgh.mac.client

import com.simorgh.mac.data.ServerStore
import com.simorgh.mac.data.Subscription
import com.simorgh.mac.model.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The server list as the UI sees it — the Android repository, minus SQLite:
 * reads come straight off the JSON store, reloads collapse the same way.
 */
class ServerRepository private constructor(private val store: ServerStore, serverChanges: kotlinx.coroutines.flow.SharedFlow<Unit>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val reloads = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            merge(reloads, serverChanges).collect {
                _servers.value = store.all()
                _subscriptions.value = store.subscriptions()
            }
        }
        reload()
    }

    fun reload() {
        reloads.tryEmit(Unit)
    }

    fun setFavorite(key: String, favorite: Boolean) {
        scope.launch {
            store.setFavorite(key, favorite)
            _servers.value = store.all()
        }
    }

    fun setExcluded(key: String, excluded: Boolean) {
        scope.launch {
            store.setExcluded(key, excluded)
            _servers.value = store.all()
        }
    }

    fun delete(keys: Collection<String>) {
        scope.launch {
            store.delete(keys)
            _servers.value = store.all()
        }
    }

    companion object {
        @Volatile private var instance: ServerRepository? = null
        fun get(store: ServerStore, serverChanges: kotlinx.coroutines.flow.SharedFlow<Unit>): ServerRepository =
            instance ?: synchronized(this) { instance ?: ServerRepository(store, serverChanges).also { instance = it } }
    }
}

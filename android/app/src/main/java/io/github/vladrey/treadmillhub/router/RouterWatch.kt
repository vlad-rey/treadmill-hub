package io.github.vladrey.treadmillhub.router

import io.github.vladrey.treadmillhub.HubConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class RouterStatus(
    val configured: Boolean = false,
    val user: String? = null,
    val connected: Boolean = false,
    val model: String? = null,
    val error: String? = null,
    /** Wrong password: the hub won't retry until a new one is entered (otherwise the router locks out logins). */
    val waitingForPassword: Boolean = false,
    val lastOkMs: Long = 0,
    val clients: Int = 0,
    val online: Int = 0,
    /** Average internet speed between polls, Mbps. */
    val wanDownMbps: Double? = null,
    val wanUpMbps: Double? = null,
)

/** Polls the ASUS router every [intervalMs]: model, clients, traffic. Login and password are in the hub settings. */
class RouterWatch(private val config: HubConfig, private val host: () -> String?, private val intervalMs: Long = 5 * 60_000L) {
    @Volatile var status = RouterStatus(configured = config.routerPassword != null, user = config.routerUser)
        private set
    @Volatile var clients: List<RouterClient> = emptyList()
        private set
    private var client: AsusRouter? = null
    private var blocked = false
    private var prevWan: Pair<Long, Pair<Long, Long>>? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                poll()
                withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        }
    }

    /** New login and password: try logging in right away. An empty password disables the router. */
    fun setCredentials(user: String, password: String?) {
        synchronized(this) {
            client?.logout()
            client = null
            blocked = false
            prevWan = null
            config.routerUser = user.ifBlank { "admin" }
            config.routerPassword = password?.ifEmpty { null }
            status = RouterStatus(configured = config.routerPassword != null, user = config.routerUser)
            if (config.routerPassword == null) clients = emptyList()
        }
        wake.trySend(Unit)
    }

    /** An action against the router on behalf of the hub (debugging, speed test); null — router not configured. */
    fun <T> use(block: (AsusRouter) -> T): T? = synchronized(this) { connect()?.let(block) }

    private fun connect(): AsusRouter? {
        if (blocked) return null
        val pass = config.routerPassword ?: return null
        val h = host() ?: return null
        return client ?: AsusRouter(h, config.routerUser ?: "admin", pass).also { client = it }
    }

    private fun poll() = synchronized(this) {
        val c = connect()
        if (c == null) {
            if (!blocked) status = RouterStatus(configured = config.routerPassword != null, user = config.routerUser,
                error = if (config.routerPassword != null) "не знаю адрес роутера (нет Wi-Fi?)" else null)
            return@synchronized
        }
        try {
            val model = status.model ?: c.model()
            val list = c.clients()
            clients = list
            val now = System.currentTimeMillis()
            var down: Double? = null; var up: Double? = null
            c.wanBytes()?.let { cur ->
                prevWan?.let { (t, p) ->
                    val dt = (now - t) / 1000.0
                    if (dt > 0 && cur.first >= p.first && cur.second >= p.second) {
                        down = (cur.first - p.first) * 8 / dt / 1e6
                        up = (cur.second - p.second) * 8 / dt / 1e6
                    }
                }
                prevWan = now to cur
            }
            status = RouterStatus(true, config.routerUser, true, model, null, false, now, list.size, list.count { it.online },
                down?.let { Math.round(it * 10) / 10.0 }, up?.let { Math.round(it * 10) / 10.0 })
        } catch (e: RouterAuthException) {
            client = null
            if (e.permanent) blocked = true
            status = status.copy(configured = true, connected = false, error = e.message, waitingForPassword = e.permanent)
        } catch (e: Exception) {
            client = null
            status = status.copy(configured = true, connected = false, error = "роутер не ответил: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

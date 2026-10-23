package studio.cluvex.aether.custom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The latency and reachability of every saved config, as last measured.
 *
 * In memory only, on purpose: a ping is a statement about THIS network at THIS
 * moment. Persisting it would show a number from the user's home Wi-Fi while they
 * are on mobile data.
 *
 * A measurement never touches the tunnel: [RealDelay] runs the config's own
 * outbound in a separate Xray core (a child of this app, so outside the VPN, like
 * a direct session) and sends one HTTP request through it. A config that carries
 * nothing is then checked for DNS/TCP reachability by [ConfigPing.diagnose] to
 * say why.
 */
object ConfigPingStore {

    private val _results = MutableStateFlow<Map<String, ConfigPing.Report>>(emptyMap())
    val results: StateFlow<Map<String, ConfigPing.Report>> = _results.asStateFlow()

    private val _testingAll = MutableStateFlow(false)
    val testingAll: StateFlow<Boolean> = _testingAll.asStateFlow()

    /** Serialises test runs, so two taps never start two sets of probe cores. */
    private val running = Mutex()

    fun report(id: String): ConfigPing.Report = _results.value[id] ?: ConfigPing.Report()

    /** Drops every result, e.g. when the network changed. */
    fun clear() {
        _results.value = emptyMap()
    }

    /** Measures one config and publishes its result. */
    suspend fun test(context: Context, config: CustomConfig) {
        running.withLock { run(context.applicationContext, listOf(config)) }
    }

    /** Measures every config in [configs]; results appear one by one as they finish. */
    suspend fun testAll(context: Context, configs: List<CustomConfig>) {
        if (configs.isEmpty() || _testingAll.value) return
        _testingAll.value = true
        try {
            running.withLock { run(context.applicationContext, configs) }
            val ok = _results.value.count { (id, r) -> configs.any { it.id == id } && r.ok }
            DiagnosticsLog.i(TAG, "Real-delay test of ${configs.size} config(s): $ok answered.")
        } finally {
            _testingAll.value = false
        }
    }

    private suspend fun run(context: Context, configs: List<CustomConfig>) {
        configs.forEach { publish(it.id, report(it.id).copy(status = ConfigPing.PingStatus.TESTING)) }
        val up = withContext(Dispatchers.IO) { ConfigPing.networkUp(context) }
        if (!up) {
            configs.forEach { publish(it.id, failed(ConfigPing.PingStatus.NO_NETWORK)) }
            return
        }
        val runner = XrayProcess(context.applicationInfo.nativeLibraryDir, context.filesDir)
        if (!runner.available()) {
            // Only a build without libxray.so ends up here: say what can be said.
            DiagnosticsLog.w(TAG, XrayProcess.MISSING)
            for (cfg in configs) {
                val status = withContext(Dispatchers.IO) { ConfigPing.diagnose(ConfigEndpoints.of(cfg), up) }
                publish(cfg.id, failed(status))
            }
            return
        }
        val byId = configs.associateBy { it.id }
        val dns = withContext(Dispatchers.IO) { physicalDns(context) }
        val launcher = RealDelay.Launcher { json, tag ->
            val process = XrayProcess(context.applicationInfo.nativeLibraryDir, context.filesDir)
            process.start(json, tag)
            object : RealDelay.Core {
                override fun isAlive() = process.isAlive()
                override fun stop() = process.stop()
            }
        }
        val failures = mutableListOf<Pair<CustomConfig, RealDelay.Result>>()
        RealDelay.measureAll(
            items = configs.map { RealDelay.Item(it.id, it.outbound) },
            launcher = launcher,
            networkDns = dns,
            onResult = { id, result ->
                val cfg = byId[id] ?: return@measureAll
                when (result) {
                    is RealDelay.Result.Ok -> {
                        DiagnosticsLog.i(TAG, "Real delay ${cfg.name}: ${result.ms} ms")
                        publish(id, ConfigPing.Report(result.ms, ConfigPing.PingStatus.OK, at = now()))
                    }
                    else -> synchronized(failures) { failures += cfg to result }
                }
            },
        )
        // Explain the failures, a few at a time (each is one DNS lookup and one TCP connect).
        val gate = Semaphore(RealDelay.PARALLEL)
        coroutineScope {
            failures.map { (cfg, result) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val status = if (result is RealDelay.Result.Invalid) {
                            ConfigPing.PingStatus.INVALID
                        } else {
                            ConfigPing.diagnose(ConfigEndpoints.of(cfg), up)
                        }
                        val reason = when (result) {
                            is RealDelay.Result.Invalid -> result.reason
                            is RealDelay.Result.NoReply -> result.reason
                            else -> ""
                        }
                        DiagnosticsLog.i(TAG, "Real delay ${cfg.name}: -1 ($reason) -> ${status.name}")
                        publish(cfg.id, failed(status))
                    }
                }
            }.awaitAll()
        }
    }

    /** The resolvers of the networks the phone is really on, outside any VPN. */
    @Suppress("DEPRECATION")
    private fun physicalDns(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
        cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun failed(status: ConfigPing.PingStatus) = ConfigPing.Report(ConfigPing.FAILED_MS, status, at = now())

    private fun now() = System.currentTimeMillis()

    @Synchronized
    private fun publish(id: String, report: ConfigPing.Report) {
        _results.value = _results.value + (id to report)
    }

    private const val TAG = "ping"
}

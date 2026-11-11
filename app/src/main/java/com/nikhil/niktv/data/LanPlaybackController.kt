package com.nikhil.niktv.data
import kotlinx.coroutines.sync.withLock

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

data class LanDevice(val name: String, val host: String, val port: Int)
internal fun currentLanDevice(devices: List<LanDevice>, selected: LanDevice?): LanDevice? =
    selected?.let { previous -> devices.firstOrNull { it.name == previous.name } }
data class LanPlaybackState(
    val receiving: Boolean = false, val address: String = "", val port: Int = 0, val pin: String = "",
    val devices: List<LanDevice> = emptyList(), val target: LanTarget? = null,
    val busy: Boolean = false, val message: String = "", val remoteVideo: String? = null, val remoteMediaId: String? = null,
    val incomingPlayback: Long = 0, val pinSecondsRemaining: Int = 300,
    val pairedDevices: List<LanPairedDevice> = emptyList()
)

class LanPlaybackController(context: Context, private val scope: CoroutineScope,
    private val execute: suspend (LanCommand) -> LanReply) : AutoCloseable {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val appContext = context.applicationContext
    private val receiverPreferences = context.getSharedPreferences("niktv_lan_receiver", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(LanPlaybackState())
    val state = mutable.asStateFlow()
    @Volatile internal var sharedVideo: RemoteVideo? = null
        private set
    private var server: LanPlaybackServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var startJob: Job? = null
    private var receiverTicker: Job? = null
    private var foreground = false
    private val foregroundOwners = mutableSetOf<Any>()
    private var receiveRequested = false
    private val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolving: CompletableDeferred<Unit>? = null
    private val serviceType = "_niktvplay._tcp."
    init {
        scope.launch {
            for (info in services) {
                val done = CompletableDeferred<Unit>()
                resolving = done
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, error: Int) { done.complete(Unit) }
                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val host = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                                else service.host?.takeIf { it is Inet4Address }?.hostAddress
                            if (discovery != null && host != null && !(service.port == server?.port && host == mutable.value.address)) {
                                val device = LanDevice(service.serviceName, host, service.port)
                                mutable.update { it.copy(devices = (it.devices.filterNot { d -> d.name == device.name } + device).sortedBy { d -> d.name }) }
                            }
                            done.complete(Unit)
                        }
                    })
                    withTimeoutOrNull(10_000) { done.await() }
                } catch (_: Exception) { done.complete(Unit) }
            }
        }
    }
    fun foreground(owner: Any, active: Boolean) {
        if (active) foregroundOwners.add(owner) else foregroundOwners.remove(owner)
        foreground(foregroundOwners.isNotEmpty())
    }
    fun foreground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (!active) { stopReceiver(); stopDiscovery() }
        else if (receiveRequested) setReceiving(true)
    }
    fun setReceiving(enabled: Boolean) {
        receiveRequested = enabled
        stopReceiver()
        if (!enabled || !foreground) return
        // Receiving and sending are separate roles; never forward an incoming play back to another TV.
        mutable.update { it.copy(target = null) }
        startJob = scope.launch {
            try {
                val address = withContext(Dispatchers.IO) {
                    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress } ?: error("Connect to the same Wi-Fi network.")
                }
                val handler: (LanCommand) -> LanReply = { command ->
                    runBlocking { withContext(Dispatchers.Main) {
                        try { withTimeout(90_000) { execute(command) }.also { reply ->
                            if (command.action == "play" && reply.ok) mutable.update { it.copy(incomingPlayback = it.incomingPlayback + 1) }
                        } } catch (timeout: TimeoutCancellationException) {
                            LanReply(false, "Playback lookup timed out. Try again on the receiving device.")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { LanReply(false, "Playback failed on the receiving device. Check its screen and try again.") }
                    } }
                }
                val preferredPort = receiverPreferences.getInt("port", 0)
                val receiver = try { LanPlaybackServer(address, preferredPort, handler) }
                    catch (_: java.net.BindException) { LanPlaybackServer(address, execute = handler) }
                server = receiver
                receiverPreferences.edit().putInt("port", receiver.port).apply()
                mutable.update { it.copy(receiving = true, address = address.hostAddress.orEmpty(), port = receiver.port,
                    pin = receiver.pin, message = "Keep NikTV open. Pairing codes refresh automatically; paired devices stay connected.") }
                receiverTicker = scope.launch {
                    while (server === receiver) {
                        val playback = execute(LanCommand("status", ""))
                        playback.hasPlayback?.let(receiver::observePlayback)
                        val snapshot = receiver.snapshot
                        mutable.update { it.copy(pin = snapshot.pin, pinSecondsRemaining = snapshot.secondsRemaining,
                            pairedDevices = snapshot.devices) }
                        delay(1_000)
                    }
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                    override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                        mutable.update { it.copy(message = "Automatic discovery unavailable. Connect using the address below.") }
                    }
                    override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                    override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
                }
                registration = listener
                nsd.registerService(NsdServiceInfo().apply {
                    serviceName = "NikTV ${Build.MODEL}"
                    this.serviceType = this@LanPlaybackController.serviceType
                    port = receiver.port
                }, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { stopReceiver(); mutable.update { it.copy(message = "Cannot receive on this network. Check Wi-Fi and try again.") } }
        }
    }
    private fun stopReceiver() {
        receiverTicker?.cancel(); receiverTicker = null
        startJob?.cancel(); startJob = null
        registration?.let { runCatching { nsd.unregisterService(it) } }; registration = null
        server?.close(); server = null
        mutable.update { it.copy(receiving = false, address = "", port = 0, pin = "", pairedDevices = emptyList()) }
    }
    fun removePairedDevice(id: String) = scope.launch {
        val receiver = server ?: return@launch
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true) }
        try {
            val reply = withContext(Dispatchers.IO) { receiver.removeSender(id) }
            if (server === receiver) mutable.update { it.copy(message = reply.message, pairedDevices = receiver.snapshot.devices) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "Could not disconnect this device. Try again.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun discover() {
        if (discovery != null || !foreground) return
        mutable.update { it.copy(devices = emptyList()) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { if (info.serviceType.startsWith("_niktvplay.")) services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) { mutable.update { it.copy(devices = it.devices.filterNot { d -> d.name == info.serviceName }) } }
        }
        discovery = listener
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { stopDiscovery(); mutable.update { it.copy(message = "Discovery unavailable. Enter the receiver's address.") } }
    }
    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery = null
    }
    fun refreshDevices() { stopDiscovery(); discover() }
    fun pair(device: LanDevice, pin: String) = scope.launch {
        if (mutable.value.busy) return@launch
        if (mutable.value.receiving) return@launch
        mutable.update { it.copy(busy = true, message = "Connecting…") }
        try {
            require(pin.matches(Regex("[0-9]{6}"))) { "Enter the 6-digit code shown on the receiving device." }
            val installation = receiverPreferences.getString("sender_id", null) ?: java.util.UUID.randomUUID().toString().also {
                receiverPreferences.edit().putString("sender_id", it).apply()
            }
            val name = runCatching { android.provider.Settings.Global.getString(appContext.contentResolver, "device_name") }
                .getOrNull()?.takeIf(String::isNotBlank) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
            val response = withContext(Dispatchers.IO) { sendLanCommand(device.host, device.port,
                LanCommand("pair", pin, sender = name.take(64), senderId = installation)) }
            check(response.ok) { response.message }
            val token = requireNotNull(response.credential)
            mutable.update { it.copy(target = LanTarget(device.name, device.host, device.port, token), message = "Connected to ${device.name}. Use remote controls or choose media to play.") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(message = failure.message ?: "Cannot connect. Check Wi-Fi and the receiver code.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    private val inputCommands = kotlinx.coroutines.sync.Mutex()
    suspend fun send(action: String, video: RemoteVideo? = null, key: String? = null, text: String? = null): LanReply = inputCommands.withLock {
        video?.validate()
        val target = requireNotNull(mutable.value.target) { "Choose a receiving device first." }
        val reply = withContext(Dispatchers.IO) { sendLanCommand(target.host, target.port, LanCommand(action, target.credential, video = video, key = key, text = text)) }
        mutable.update { it.copy(message = reply.message) }
        check(reply.ok) { reply.message }
        reply
    }
    suspend fun playVideo(media: com.nikhil.niktv.model.PlayingMedia, at: Long, portal: String? = null) {
        check(media.streamDrm == null && media.separateAudioUrl == null) { "This protected or separate-audio source needs playback on this device." }
        val target = requireNotNull(mutable.value.target)
        mutable.update { it.copy(busy = true, message = "Starting ${media.media.title}…") }
        try {
            val video = LanMediaSharing.prepare(appContext, media, at, target).copy(portal = portal)
            send("play", video = video)
            sharedVideo = video
            mutable.update { it.copy(remoteVideo = media.media.title, remoteMediaId = media.media.id) }
        } catch (failure: Exception) { LanMediaSharing.stop(appContext); throw failure }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun reportFailure(message: String) { mutable.update { it.copy(message = message) } }
    fun sharingStopped() { sharedVideo = null; mutable.update { it.copy(remoteVideo = null, remoteMediaId = null) } }
    fun control(action: String) = scope.launch {
        if (mutable.value.busy) return@launch
        mutable.update { it.copy(busy = true) }
        try { send(action); if (action == "stop") { LanMediaSharing.stop(appContext); sharingStopped() } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(message = if (failure is IllegalStateException)
            failure.message ?: "Cannot control playback." else "Receiver unavailable. Keep NikTV open there, or pair again.") } }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun disconnect() { mutable.update { it.copy(target = null, remoteVideo = null, remoteMediaId = null, message = "Disconnected. Playback continues on the receiver; use the sharing notification to stop it.") } }
    override fun close() { receiveRequested = false; stopDiscovery(); stopReceiver(); services.close(); resolving?.complete(Unit) }
}

/** Pairing and a sender session survive switching between browser and external-video activities. */
object LanPlaybackSessions {
    private var controller: LanPlaybackController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receiverOwner: Any? = null
    private var receiver: (suspend (LanCommand) -> LanReply)? = null
    @Synchronized fun get(context: Context): LanPlaybackController = controller ?: LanPlaybackController(context.applicationContext, scope) {
        receiver?.invoke(it) ?: LanReply(false, "Open NikTV on the receiving device first.")
    }.also { controller = it }
    fun attach(owner: Any, execute: suspend (LanCommand) -> LanReply) { receiverOwner = owner; receiver = execute }
    fun detach(owner: Any) { if (receiverOwner === owner) { receiver = null; receiverOwner = null } }
}

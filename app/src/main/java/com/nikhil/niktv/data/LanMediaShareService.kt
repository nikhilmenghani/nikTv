package com.nikhil.niktv.data

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nikhil.niktv.model.PlayingMedia
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** The service owns the URI grant and stream, independently of the sender's visible activity. */
object LanMediaSharing {
    internal data class Request(val id: String, val media: PlayingMedia, val at: Long, val target: LanTarget,
        val ready: CompletableDeferred<RemoteVideo> = CompletableDeferred())
    internal val requests = ConcurrentHashMap<String, Request>()
    suspend fun prepare(context: Context, media: PlayingMedia, at: Long, target: LanTarget): RemoteVideo {
        val request = Request(java.util.UUID.randomUUID().toString(), media, at.coerceAtLeast(0), target)
        requests[request.id] = request
        try {
            ContextCompat.startForegroundService(context, Intent(context, LanMediaShareService::class.java).apply {
                action = "start"; data = Uri.parse(media.url)
                clipData = ClipData.newRawUri("Selected video", data)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("request", request.id)
            })
            return withTimeout(20_000) { request.ready.await() }
        } catch (failure: Exception) {
            requests.remove(request.id)
            runCatching { context.startService(Intent(context, LanMediaShareService::class.java).setAction("cancel").putExtra("request", request.id)) }
            throw failure
        }
    }
    fun stop(context: Context) { context.stopService(Intent(context, LanMediaShareService::class.java)) }
}

class LanMediaShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var share: AutoCloseable? = null
    private var active: LanMediaSharing.Request? = null
    private var loading: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("remote_video", "Remote video playback", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            loading?.cancel()
            scope.launch {
                active?.target?.let { target -> withContext(Dispatchers.IO) {
                    runCatching { sendLanCommand(target.host, target.port, LanCommand("stop", target.credential)) }
                } }
                LanPlaybackSessions.get(this@LanMediaShareService).sharingStopped()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == "cancel") {
            if (active == null || active?.id == intent.getStringExtra("request")) stopSelf()
            return START_NOT_STICKY
        }
        val request = LanMediaSharing.requests.remove(intent?.getStringExtra("request"))
        if (request == null) { stopSelf(startId); return START_NOT_STICKY }
        loading?.cancel(); share?.close(); share = null; releaseLocks()
        active = request
        val stop = PendingIntent.getService(this, 701, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 702, Intent(this, com.nikhil.niktv.ExternalVideoActivity::class.java)
            .putExtra("remote_controls", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "remote_video")
            .setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Playing on ${request.target.name}")
            .setContentText(request.media.media.title).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop sharing", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(701, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(701, notification)
        loading = scope.launch {
            var created: LanSelectedMediaServer? = null
            try {
                val selected = Uri.parse(request.media.url)
                val local = selected.scheme in setOf("content", "file") || selected.host in setOf("127.0.0.1", "localhost", "::1")
                val video = if (local) withContext(NonCancellable + Dispatchers.IO) {
                    val address = localLanAddress()
                    val relay = LanSelectedMediaServer(this@LanMediaShareService, address, request.media, request.target.host)
                    created = relay
                    RemoteVideo(relay.url, request.media.media.title.take(200), request.media.playbackFormat, positionMillis = request.at)
                } else RemoteVideo(request.media.url, request.media.media.title.take(200), request.media.playbackFormat,
                    request.media.streamRequestHeaders, request.at)
                currentCoroutineContext().ensureActive()
                share = created
                video.validate()
                if (local) {
                    wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NikTV:remote-video").apply { acquire(6 * 60 * 60 * 1000L) }
                    @Suppress("DEPRECATION")
                    wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NikTV:remote-video").apply { acquire() }
                }
                request.ready.complete(video)
            } catch (cancelled: CancellationException) { created?.close(); request.ready.cancel(); throw cancelled }
            catch (failure: Exception) { created?.close(); request.ready.completeExceptionally(failure); stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
    private fun releaseLocks() { wake?.let { if (it.isHeld) it.release() }; wake = null; wifi?.let { if (it.isHeld) it.release() }; wifi = null }
    override fun onDestroy() {
        loading?.cancel(); active?.ready?.cancel(); share?.close(); share = null; releaseLocks(); scope.cancel()
        super.onDestroy()
    }
}

internal fun localLanAddress(): String = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }.sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    ?: error("Connect both devices to the same Wi-Fi network.")

/** One selected resource, an unpredictable route, and only the paired receiver can read it. */
internal class LanSelectedMediaServer(private val context: Context, address: String, private val media: PlayingMedia,
    private val peer: String, private val onFailure: (Exception) -> Unit = {}) : NanoHTTPD(address, 0), AutoCloseable {
    private val route = "/" + ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } + "/"
    private val source = Uri.parse(media.url)
    private val proxy = if (source.scheme in setOf("http", "https")) LanLoopbackRelay(media.url, media.streamRequestHeaders, route) else null
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val handlers = ConcurrentHashMap.newKeySet<ClientHandler>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(4, 4, 20, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8), { task -> Thread(task, "NikTV video share").apply { isDaemon = true } })
    val url get() = "http://$hostname:$listeningPort${route}video"
    init {
        workers.allowCoreThreadTimeOut(true)
        setAsyncRunner(object : AsyncRunner {
            override fun closeAll() { handlers.toList().forEach(ClientHandler::close); handlers.clear(); workers.shutdownNow() }
            override fun closed(handler: ClientHandler) { handlers.remove(handler) }
            override fun exec(handler: ClientHandler) {
                if (closed.get()) { handler.close(); return }
                handlers.add(handler)
                try { workers.execute(handler) } catch (_: java.util.concurrent.RejectedExecutionException) { handlers.remove(handler); handler.close() }
            }
        })
        try { start(15_000, true) } catch (failure: Exception) { close(); throw failure }
    }
    override fun serve(request: IHTTPSession): Response {
        if (request.remoteIpAddress != peer || !request.uri.startsWith(route) || !request.queryParameterString.isNullOrBlank())
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        if (request.method !in setOf(Method.GET, Method.HEAD)) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "")
        return try {
            proxy?.let { return it.serve(request) }
            if (request.uri != route + "video") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(source, "r")) { "The file is no longer readable." }
            try {
                val size = descriptor.length.takeIf { it >= 0 } ?: descriptor.parcelFileDescriptor.statSize
                val range = if (size >= 0) try { lanMediaRange(request.headers["range"], size) } catch (_: IllegalArgumentException) {
                    descriptor.close()
                    return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "").apply { addHeader("Content-Range", "bytes */$size") }
                } else null
                val input = descriptor.createInputStream()
                val start = range?.start ?: 0L
                if (start > 0) try { input.channel.position(descriptor.startOffset + start) } catch (_: IOException) {
                    var left = start
                    while (left > 0) { val skipped = input.skip(left); if (skipped > 0) left -= skipped else { if (input.read() == -1) throw EOFException(); left-- } }
                }
                val mime = context.contentResolver.getType(source) ?: "application/octet-stream"
                val response = if (size >= 0) newFixedLengthResponse(if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT,
                    mime, input, range?.length ?: size) else newChunkedResponse(Response.Status.OK, mime, input)
                response.addHeader("Accept-Ranges", if (size >= 0) "bytes" else "none")
                response.addHeader("Cache-Control", "no-store")
                // Keep HEAD probes and subsequent range opens on separate descriptor lifetimes.
                response.addHeader("Connection", "close")
                if (range != null) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$size")
                return response
            } catch (failure: Exception) { descriptor.close(); throw failure }
        } catch (failure: Exception) { onFailure(failure); newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "The selected video is unavailable.") }
    }
    override fun close() { if (closed.compareAndSet(false, true)) { stop(); proxy?.close() } }
}

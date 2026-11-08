package app.opendisplay.android.net

import android.os.SystemClock
import android.util.Base64
import app.opendisplay.android.Log
import app.opendisplay.android.protocol.ClockSync
import app.opendisplay.android.protocol.Codec
import app.opendisplay.android.protocol.CursorSequence
import app.opendisplay.android.protocol.Demux
import app.opendisplay.android.protocol.Framing
import app.opendisplay.android.protocol.Messages
import app.opendisplay.android.protocol.PenInput
import app.opendisplay.android.protocol.WireProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Secured Wi-Fi receiver: listens on the fork-only TLS TCP port, serves one
 * authenticated sender at a time, speaks
 * hello/ping/kf/input, and hands video and the sender's control
 * messages to [listener]. Callbacks arrive on network threads.
 */
class ReceiverServer(
    private val listener: Listener,
    /** Builds the current `hello`; called on network threads. */
    private val helloFactory: (cursorPort: Int?) -> JSONObject,
    private val identity: ReceiverIdentity.Identity,
    private val context: android.content.Context,
    private val port: Int = WireProtocol.TCP_PORT,
) {
    interface Listener {
        fun onSessionStarted(transport: String)
        fun onSessionEnded()
        /** Called on the UI thread; approve only after comparing this code with the Mac. */
        fun onPairingRequested(code: String, macName: String, approved: (Boolean) -> Unit)
        fun onVideoFrame(payload: ByteArray)
        fun onStreamConfig(codec: Codec?, codecToken: String, width: Int, height: Int, fps: Int)
        fun onCursor(x: Double, y: Double, visible: Boolean)
        fun onCursorImage(png: ByteArray, nw: Double, nh: Double, ax: Double, ay: Double)
        fun onSenderSleeping()
        fun onSenderVersion(pv: Int)
        fun onUpdateRequired(message: String, store: String?)
        fun onSenderHealth(obj: JSONObject)
        /** Receiver-side telemetry for the sender's log, every ~5 s. */
        fun statsSnapshot(): JSONObject
    }

    val clock = ClockSync()
    private val cursorSeq = CursorSequence()

    private val lock = Any()
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    /** Volatile so the UDP cursor thread can check it without taking [lock]. */
    @Volatile private var current: Session? = null
    private var generation = 0
    private val candidates = mutableListOf<Socket>()
    private val authenticating = mutableSetOf<Socket>()
    private var timers: ScheduledExecutorService? = null
    private var cursorChannel: CursorChannel? = null
    private val pendingAuthentications = AtomicInteger(0)
    private val nextAuthAttempt = AtomicInteger(0)

    @Volatile var senderPv: Int = WireProtocol.ASSUMED_WHEN_ABSENT
        private set

    private val loggedUnknownTypes = HashSet<String>()

    fun start() {
        val gen = synchronized(lock) {
            if (running) return
            running = true
            ++generation
        }
        // The fork uses the single encrypted TCP stream for cursor updates;
        // never open the upstream unauthenticated UDP cursor port.
        timers = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "od-timers").apply { isDaemon = true }
        }.also { t ->
            t.scheduleWithFixedDelay(::tick, 2, 2, TimeUnit.SECONDS)
            t.scheduleWithFixedDelay(::sendStats, 5, 5, TimeUnit.SECONDS)
        }
        Thread({ acceptLoop(gen) }, "od-accept").apply { isDaemon = true }.start()
    }

    /** Ends the session with a best-effort courtesy message (`sleeping` or `closing`). */
    fun stop(announce: String?) {
        val session: Session?
        synchronized(lock) {
            if (!running) return
            running = false
            generation++ // a quick stop/start must not leave two accept loops
            session = current
            current = null
            candidates.forEach { closeQuietly(it) }
            candidates.clear()
            authenticating.forEach { closeQuietly(it) }
            authenticating.clear()
            closeQuietly(serverSocket)
            serverSocket = null
        }
        session?.closeAfter(announce?.let { Messages.simple(it) })
        timers?.shutdownNow()
        timers = null
        cursorChannel?.stop()
        cursorChannel = null
        if (session != null) listener.onSessionEnded()
    }

    val isConnected: Boolean get() = synchronized(lock) { current?.isOpen == true }

    val isOnAccessory: Boolean
        get() = synchronized(lock) { current?.takeIf { it.isOpen }?.link is AccessoryLink }

    fun disconnectCurrent() {
        val session = synchronized(lock) {
            current.also { current = null }
        }
        if (session != null) {
            session.close()
            listener.onSessionEnded()
        }
    }

    // region Accept and adopt

    private fun live(gen: Int) = running && gen == generation

    private fun acceptLoop(gen: Int) {
        while (live(gen)) {
            val server = try {
                (identity.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                    reuseAddress = true
                    needClientAuth = false
                    enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                    bind(InetSocketAddress(this@ReceiverServer.port))
                }
            } catch (e: IOException) {
                Log.w(TAG, "listen on $port failed: $e; retrying in 1 s")
                SystemClock.sleep(1000)
                continue
            }
            synchronized(lock) {
                if (!live(gen)) {
                    closeQuietly(server)
                    return
                }
                serverSocket = server
            }
            Log.i(TAG, "listening on TCP $port")
            try {
                while (live(gen)) {
                    val socket = server.accept() as SSLSocket
                    socket.useClientMode = false
                    socket.tcpNoDelay = true // tiny input packets must not wait for ACKs
                    socket.keepAlive = true
                    if (pendingAuthentications.incrementAndGet() > MAX_PENDING_AUTH) {
                        pendingAuthentications.decrementAndGet()
                        closeQuietly(socket)
                        continue
                    }
                    val attempt = nextAuthAttempt.incrementAndGet()
                    synchronized(lock) { authenticating.add(socket) }
                    Log.i(TAG, "auth[$attempt] accepted")
                    Thread({
                        try {
                            if (live(gen) && authorize(socket, attempt)) onAccepted(socket)
                            else closeQuietly(socket)
                        } finally {
                            synchronized(lock) { authenticating.remove(socket) }
                            pendingAuthentications.decrementAndGet()
                        }
                    }, "od-auth").apply { isDaemon = true }.start()
                }
            } catch (e: IOException) {
                if (live(gen)) Log.w(TAG, "accept failed: $e; relistening")
            } finally {
                closeQuietly(server)
            }
        }
    }

    /** TLS encrypts the stream; the remembered token authorizes the Mac before a session exists. */
    private fun authorize(socket: SSLSocket, attempt: Int): Boolean {
        val started = SystemClock.elapsedRealtime()
        var phase = "tls"
        try {
            socket.soTimeout = TLS_TIMEOUT_MS
            Log.i(TAG, "auth[$attempt] TLS handshake started")
            socket.startHandshake()
            Log.i(TAG, "auth[$attempt] TLS ready after ${SystemClock.elapsedRealtime() - started}ms " +
                "protocol=${socket.session.protocol} cipher=${socket.session.cipherSuite}")
            // The Mac displays a confirmation dialog before sending secureAuth.
            // Keep that human interval separate from the TLS handshake timeout.
            phase = "waiting_for_mac_confirmation"
            socket.soTimeout = MAC_CONFIRMATION_TIMEOUT_MS
            val deadline = timers?.schedule({
                Log.i(TAG, "auth[$attempt] Mac confirmation deadline expired")
                closeQuietly(socket)
            }, MAC_CONFIRMATION_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                ?: throw IOException("receiver authorization timer unavailable")
            val request = try {
                val input = DataInputStream(socket.inputStream)
                Framing.readFrame(input)?.let { JSONObject(String(it, Charsets.UTF_8)) }
                    ?: return false
            } finally {
                deadline.cancel(false)
            }
            phase = "secure_auth"
            Log.i(TAG, "auth[$attempt] secureAuth received after ${SystemClock.elapsedRealtime() - started}ms")
            if (request.optString("type") != "secureAuth") return false
            if (request.optInt("secureVersion", -1) != WireProtocol.SECURE_PROTOCOL_VERSION) {
                sendSecureReady(socket, false, "version_mismatch")
                return false
            }
            val mode = request.optString("mode")
            val macFingerprint = request.optString("macFingerprint").uppercase()
            val macName = request.optString("macName").ifBlank { "Mac" }
            val token = request.optString("token").uppercase()
            if (!FINGERPRINT.matches(macFingerprint) || !TOKEN.matches(token)) return false

            val prefs = context.getSharedPreferences(PAIRING_PREFS, android.content.Context.MODE_PRIVATE)
            val tokenKey = "token:$macFingerprint"
            var rejection = "pairing_rejected"
            val approved = when (mode) {
                "resume" -> (prefs.getString(tokenKey, null) == token).also {
                    if (!it) rejection = "pairing_missing"
                }
                "pair" -> {
                    val suppliedCode = request.optString("comparisonCode")
                    val expectedCode = ReceiverIdentity.comparisonCode(identity.fingerprint, macFingerprint)
                    if (!expectedCode.equals(suppliedCode, ignoreCase = true)) {
                        rejection = "comparison_mismatch"
                        false
                    }
                    else if (prefs.getString(tokenKey, null) == token) true
                    else if (prefs.contains(tokenKey)) {
                        rejection = "already_paired"
                        false
                    }
                    else {
                        val decision = AtomicBoolean(false)
                        val latch = CountDownLatch(1)
                        listener.onPairingRequested(expectedCode, macName) { allow ->
                            decision.set(allow)
                            latch.countDown()
                        }
                        phase = "waiting_for_tv_confirmation"
                        latch.await(PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS) && decision.get().also { allow ->
                            if (allow) prefs.edit().putString(tokenKey, token).apply()
                            else rejection = "pairing_declined"
                        }
                    }
                }
                else -> false
            }
            socket.soTimeout = 0
            sendSecureReady(socket, approved, if (approved) null else rejection)
            if (approved) Log.i(TAG, "auth[$attempt] authenticated paired Mac $macFingerprint")
            return approved
        } catch (e: Exception) {
            Log.i(TAG, "auth[$attempt] failed phase=$phase after ${SystemClock.elapsedRealtime() - started}ms: " +
                android.util.Log.getStackTraceString(e))
            return false
        }
    }

    private fun sendSecureReady(socket: Socket, approved: Boolean, reason: String?) {
        val response = JSONObject().put("type", "secureReady").put("ok", approved)
        reason?.let { response.put("reason", it) }
        DataOutputStream(socket.outputStream).apply {
            write(Framing.encode(response.toString().toByteArray(Charsets.UTF_8)))
            flush()
        }
    }

    private fun onAccepted(socket: Socket) {
        Log.i(TAG, "connection from ${socket.remoteSocketAddress}")
        val live = synchronized(lock) { current?.takeIf { it.isOpen } }
        if (live == null) {
            adopt(SocketLink(socket), null)
            return
        }
        // A Bonjour dial races IPv4 and IPv6 and the sender cancels the loser
        // within milliseconds; adopting every newcomer evicted the winner.
        // So a newcomer must prove itself with bytes first (iOS receiver
        // behavior). Its provisional hello carries no cursorPort, so its UDP
        // flow cannot start before it owns the session.
        synchronized(lock) { candidates.add(socket) }
        Thread({ proveCandidate(socket) }, "od-candidate").apply { isDaemon = true }.start()
    }

    private fun proveCandidate(socket: Socket) {
        try {
            socket.getOutputStream().apply {
                write(Framing.encode(Messages.bytes(helloFactory(null))))
                flush()
            }
            socket.soTimeout = 5000
            val buf = ByteArray(64 * 1024)
            val n = socket.getInputStream().read(buf)
            socket.soTimeout = 0
            val stillCandidate = synchronized(lock) { candidates.remove(socket) }
            if (n > 0 && stillCandidate && running) {
                Log.i(TAG, "newcomer proved itself; adopting it as the session")
                adopt(SocketLink(socket), buf.copyOf(n))
            } else {
                Log.i(TAG, "dropped a newcomer that sent nothing")
                closeQuietly(socket)
            }
        } catch (e: IOException) {
            synchronized(lock) { candidates.remove(socket) }
            Log.i(TAG, "dropped a newcomer: $e")
            closeQuietly(socket)
        }
    }

    /**
     * The sender switched this device into accessory mode: that is a fresh
     * cable session, adopted at once (no dial race to prove against). False
     * while stopped, so the caller keeps the accessory for later.
     */
    internal fun adoptAccessory(link: Link): Boolean {
        Log.w(TAG, "refusing unencrypted USB accessory path ${link.remote}; use authenticated Wi-Fi")
        closeQuietly(link)
        return false
    }

    private fun adopt(link: Link, initial: ByteArray?): Boolean {
        val session = Session(link, initial)
        val old: Session?
        synchronized(lock) {
            if (!running) {
                closeQuietly(link)
                return false
            }
            old = current
            current = session
            candidates.forEach { closeQuietly(it) }
            candidates.clear()
            // Per-session state: the sender restarts its cursor sequence, and
            // the old session's UDP flow must not leak into this one.
            cursorChannel?.retireFlow() // waits out an in-flight datagram
            cursorSeq.reset()
            clock.reset()
            senderPv = WireProtocol.ASSUMED_WHEN_ABSENT
        }
        old?.close()
        listener.onSessionStarted(link.transport)
        session.start()
        sendHello()
        return true
    }

    // endregion

    // region Outbound

    fun sendHello() {
        val session = synchronized(lock) { current } ?: return
        val hello = helloFactory(cursorChannel?.boundPort)
        session.send(hello)
        Log.i(TAG, "hello sent: $hello")
    }

    fun sendTouch(phase: String, x: Double, y: Double) {
        send(Messages.touch(phase, x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0), senderNow()))
    }

    fun sendScroll(dx: Double, dy: Double) = send(Messages.scroll(dx, dy))

    fun sendPencil(phase: String, s: PenInput.Sample) = send(Messages.pencil(phase,
        s.copy(x = s.x.coerceIn(0.0, 1.0), y = s.y.coerceIn(0.0, 1.0)), senderNow()))

    fun sendProximity(entering: Boolean, x: Double, y: Double) =
        send(Messages.proximity(entering, x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)))

    fun requestKeyframe() = send(Messages.simple("kf"))

    private fun senderNow(): Double? = clock.senderNow(System.currentTimeMillis().toDouble())

    private fun send(obj: JSONObject) {
        synchronized(lock) { current }?.send(obj)
    }

    private fun tick() {
        val session = synchronized(lock) { current } ?: return
        if (!session.isOpen) return
        if (SystemClock.elapsedRealtime() - session.lastReceive > 5000) {
            // A static screen sends no video, but the sender pings every 2 s.
            Log.i(TAG, "watchdog: nothing from the sender for >5 s; dropping the connection")
            session.close()
            return
        }
        session.send(Messages.ping(System.currentTimeMillis().toDouble()))
    }

    private fun sendStats() {
        val session = synchronized(lock) { current } ?: return
        try {
            session.send(listener.statsSnapshot().put("type", "stats"))
        } catch (e: Exception) {
            Log.w(TAG, "stats failed: $e")
        }
    }

    // endregion

    // region Inbound control

    private fun handleControl(payload: ByteArray) {
        val obj = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return // 6: unparseable control MUST be ignored
        }
        try {
            dispatchControl(obj)
        } catch (e: Exception) {
            // A malformed field (a string where a number belongs) is the
            // sender's bug, never a reason to drop the session or crash.
            Log.w(TAG, "ignored malformed ${obj.optString("type")}: $e")
        }
    }

    private fun dispatchControl(obj: JSONObject) {
        when (val type = obj.optString("type")) {
            "pong" -> {
                if (obj.has("t") && obj.has("mt")) {
                    clock.addSample(obj.getDouble("t"), obj.getDouble("mt"),
                        System.currentTimeMillis().toDouble())
                }
            }
            "ping" -> listener.onSenderHealth(obj)
            "cursor" -> applyCursor(obj)
            "cursorImg" -> {
                val png = try {
                    Base64.decode(obj.optString("png"), Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    return
                }
                if (png.isEmpty() || !obj.has("nw") || !obj.has("nh")) return
                listener.onCursorImage(png, obj.getDouble("nw"), obj.getDouble("nh"),
                    obj.optDouble("ax", 0.0), obj.optDouble("ay", 0.0))
            }
            "welcome" -> {
                val pv = obj.optInt("pv", WireProtocol.ASSUMED_WHEN_ABSENT)
                senderPv = pv
                listener.onSenderVersion(pv)
            }
            "streamConfig" -> {
                val token = obj.optString("codec", "h264").lowercase()
                listener.onStreamConfig(Codec.fromWire(token), token,
                    obj.optInt("width"), obj.optInt("height"), obj.optInt("framesPerSecond"))
            }
            "sleeping" -> listener.onSenderSleeping()
            "updateRequired" -> listener.onUpdateRequired(
                obj.optString("message", "Update OpenDisplay on this device to keep using it as a display."),
                obj.optString("store").ifEmpty { null })
            // Never offered in hello (6.6), so never obeyed.
            "power" -> Log.i(TAG, "ignored power ${obj.optString("action")}: not offered")
            else -> if (loggedUnknownTypes.add(type)) Log.i(TAG, "ignoring control type '$type'")
        }
    }

    /** Both cursor paths land here so the sequence floor orders them (6.3). */
    private fun applyCursor(obj: JSONObject): Boolean {
        val seq = if (obj.has("s")) obj.optLong("s") else null
        if (!cursorSeq.accept(seq)) return false
        listener.onCursor(obj.optDouble("x", 0.0), obj.optDouble("y", 0.0), obj.optInt("v", 0) == 1)
        return true
    }

    /**
     * Runs on the UDP thread while CursorChannel holds its lock, so adopt()'s
     * retireFlow() cannot interleave between the flow check and the apply
     * (an old session's high `s` would otherwise become the new floor).
     * Must not take [lock]: adopt() holds it while calling into the channel.
     */
    private fun onCursorDatagram(obj: JSONObject, newFlow: Boolean): Boolean {
        if (current == null) return false
        if (obj.optString("type") != "cursor") return false
        // The tracker resets on every new UDP flow (6.3).
        if (newFlow) cursorSeq.reset()
        return applyCursor(obj)
    }

    /** First accepted datagram of a flow: confirm delivery over TCP. */
    internal fun ackCursorFlow() = send(Messages.simple("cursorAck"))

    // endregion

    private inner class Session(val link: Link, private val initial: ByteArray?) {
        private val outbox = LinkedBlockingQueue<ByteArray>()
        @Volatile var isOpen = true
            private set
        @Volatile var lastReceive = SystemClock.elapsedRealtime()
            private set

        fun start() {
            Thread(::readLoop, "od-read").apply { isDaemon = true }.start()
            Thread(::writeLoop, "od-write").apply { isDaemon = true }.start()
        }

        fun send(obj: JSONObject) {
            if (!isOpen) return
            try {
                outbox.offer(Framing.encode(Messages.bytes(obj)))
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "dropped oversized ${obj.optString("type")}: $e")
            }
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            outbox.offer(POISON)
            closeQuietly(link)
        }

        /** Flush [last] (best effort, briefly) and then close. */
        fun closeAfter(last: JSONObject?) {
            if (last != null && isOpen) {
                send(last)
                isOpen = false
                outbox.offer(POISON)
                // The writer closes the link after the poison; don't wait long.
                Thread {
                    SystemClock.sleep(300)
                    closeQuietly(link)
                }.apply { isDaemon = true }.start()
            } else {
                close()
            }
        }

        private fun writeLoop() {
            try {
                val out = link.output
                while (true) {
                    val first = outbox.take()
                    if (first === POISON) break
                    out.write(first)
                    // Coalesce whatever queued up meanwhile into one flush.
                    while (true) {
                        val next = outbox.poll() ?: break
                        if (next === POISON) {
                            out.flush()
                            return
                        }
                        out.write(next)
                    }
                    out.flush()
                }
            } catch (e: IOException) {
                if (isOpen) Log.i(TAG, "write failed: $e")
            } catch (_: InterruptedException) {
            } finally {
                if (!isOpen) closeQuietly(link) else close()
            }
        }

        private fun readLoop() {
            try {
                // 8.2 counts silence in bytes, not frames: a big keyframe on
                // slow Wi-Fi can take longer than the watchdog to arrive.
                var stream: InputStream = object : FilterInputStream(link.input) {
                    override fun read(): Int = super.read().also { lastReceive = SystemClock.elapsedRealtime() }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        super.read(b, off, len).also { lastReceive = SystemClock.elapsedRealtime() }
                }
                if (initial != null) stream = SequenceInputStream(ByteArrayInputStream(initial), stream)
                val input = DataInputStream(BufferedInputStream(stream, 256 * 1024))
                while (isOpen) {
                    val payload = Framing.readFrame(input) ?: break
                    lastReceive = SystemClock.elapsedRealtime()
                    if (Demux.isControl(payload)) handleControl(payload) else listener.onVideoFrame(payload)
                }
            } catch (e: SocketTimeoutException) {
                Log.i(TAG, "read timed out")
            } catch (e: IOException) {
                if (isOpen) Log.i(TAG, "read ended: $e")
            } finally {
                close()
                val wasCurrent = synchronized(lock) {
                    if (current === this) {
                        current = null
                        true
                    } else false
                }
                if (wasCurrent) {
                    Log.i(TAG, "session ended")
                    listener.onSessionEnded()
                }
            }
        }
    }

    companion object {
        private const val TAG = "OpenDisplay"
        private const val PAIRING_PREFS = "trusted-macs"
        private const val PAIRING_TIMEOUT_SECONDS = 90L
        private const val TLS_TIMEOUT_MS = 10_000
        private const val MAC_CONFIRMATION_TIMEOUT_MS = 90_000
        private const val MAX_PENDING_AUTH = 2
        private val FINGERPRINT = Regex("[0-9A-F]{64}")
        private val TOKEN = Regex("[0-9A-F]{64}")
        private val POISON = ByteArray(0)

        fun pairedMacFingerprint(context: android.content.Context): String? =
            context.getSharedPreferences(PAIRING_PREFS, android.content.Context.MODE_PRIVATE)
                .all.keys.firstOrNull { it.startsWith("token:") }
                ?.removePrefix("token:")

        fun forgetPairedMacs(context: android.content.Context) {
            val prefs = context.getSharedPreferences(PAIRING_PREFS, android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }

        internal fun closeQuietly(c: java.io.Closeable?) {
            try {
                c?.close()
            } catch (_: IOException) {
            }
        }
    }
}

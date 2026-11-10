package io.github.dhananjaygoyani.speakereq

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Wireless import over the local network. Every running app listens on PORT, advertises itself with
 * DNS-SD (found by "Send to TV…") and serves a small page for browsers. A 4-digit code shown on the
 * receiving screen must accompany every EQ, so other people on the Wi-Fi can't change it.
 */
object Wireless {
    const val PORT = 8765
    private const val TYPE = "_speakereq._tcp."
    private const val MAX_BODY = 64 * 1024
    val code = (1000..9999).random().toString()
    @Volatile var onReceived: ((Saved) -> Unit)? = null // the open screen refreshes itself
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null

    class Response(val status: Int, val type: String, val body: String, val filters: List<Dsp.Filter>? = null)

    /** Pure request handling (unit-tested): GET / = browser page, POST /eq?code=NNNN = EQ text. */
    fun handle(method: String, target: String, body: String, code: String): Response {
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        return when {
            method == "GET" && path == "/" -> Response(200, "text/html", PAGE)
            method == "POST" && path == "/eq" -> when {
                query["code"] != code -> Response(403, "text/plain", "Wrong code: use the 4-digit code shown on the receiving screen.")
                body.length > MAX_BODY -> Response(413, "text/plain", "File too large.")
                else -> Dsp.parseApo(body).let {
                    if (it.isEmpty()) Response(400, "text/plain", "No EQ filters found. Send a file exported from Speaker EQ, AutoEQ or Equalizer APO.")
                    else Response(200, "text/plain", "Sent: ${it.size} filters applied.", it)
                }
            }
            else -> Response(404, "text/plain", "Not found")
        }
    }

    @Synchronized fun start(ctx: Context) {
        if (server != null) return
        val ss = try { ServerSocket(PORT) } catch (e: IOException) { return } // port busy: wireless import unavailable
        server = ss
        val app = ctx.applicationContext
        thread(isDaemon = true, name = "eq-server") {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { serve(app, sock) }
            }
        }
        val info = NsdServiceInfo().apply { serviceName = "Speaker EQ on ${Build.MODEL}"; serviceType = TYPE; port = PORT }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {} // still reachable by address
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
        }
        app.getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    @Synchronized fun stop(ctx: Context) {
        server?.close()
        server = null
        registration?.let { runCatching { ctx.getSystemService(NsdManager::class.java).unregisterService(it) } }
        registration = null
    }

    private fun serve(ctx: Context, sock: Socket) = sock.use {
        try {
            sock.soTimeout = 5000
            val inp = sock.getInputStream()
            val head = readHead(inp) ?: return@use
            val lines = head.split("\r\n")
            val parts = lines[0].split(' ')
            if (parts.size < 2) return@use
            val len = lines.drop(1).firstOrNull { it.startsWith("content-length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = if (len in 1..MAX_BODY) String(readN(inp, len), Charsets.UTF_8) else if (len > MAX_BODY) "x".repeat(MAX_BODY + 1) else ""
            val r = handle(parts[0], parts[1], body, code)
            r.filters?.let { f ->
                val s = Store.load(ctx).copy(auto = f, dev = null, autoOn = true, amount = 100f,
                    diag = "Received ${f.size} filters wirelessly from ${sock.inetAddress.hostAddress}.\nThe Tone sliders still work on top.")
                Store.save(ctx, s)
                EqEngine.apply(s)
                Handler(Looper.getMainLooper()).post { onReceived?.invoke(s) }
            }
            val bytes = r.body.toByteArray()
            val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 413 to "Payload Too Large")[r.status]
            sock.getOutputStream().apply {
                write(("HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(bytes)
                flush()
            }
        } catch (e: IOException) {
            // client went away or timed out: nothing to do
        }
    }

    private fun readHead(inp: InputStream): String? { // up to the blank line, max 8 KB
        val buf = ByteArrayOutputStream()
        while (buf.size() < 8192) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            val a = buf.toByteArray()
            if (a.size >= 4 && a[a.size - 4] == '\r'.code.toByte() && a[a.size - 3] == '\n'.code.toByte() &&
                a[a.size - 2] == '\r'.code.toByte() && a[a.size - 1] == '\n'.code.toByte()) return String(a, Charsets.ISO_8859_1).trimEnd()
        }
        return null
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) { val k = inp.read(out, off, n - off); if (k < 0) break; off += k }
        return out.copyOf(off)
    }

    fun localAddress(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress?.let { "$it:$PORT" }

    /** Finds other Speaker EQ devices on the Wi-Fi; returns a function that stops the search. */
    fun discover(ctx: Context, onFound: (name: String, address: String) -> Unit): () -> Unit {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("speakereq")
        lock.setReferenceCounted(false)
        lock.acquire() // some phones drop mDNS replies without it
        val main = Handler(Looper.getMainLooper())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val host = r.host?.hostAddress ?: return
                        main.post { onFound(r.serviceName, "$host:${r.port}") }
                    }
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return { runCatching { nsd.stopServiceDiscovery(listener) }; lock.release() }
    }

    /** Sends EQ text to another device; returns its reply (call off the main thread). */
    fun send(address: String, code: String, text: String): String = try {
        val c = URL("http://${address.trim()}/eq?code=${URLEncoder.encode(code.trim(), "UTF-8")}").openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(text.toByteArray()) }
        val reply = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        reply.ifEmpty { "No reply (HTTP ${c.responseCode})" }
    } catch (e: IOException) {
        "Could not reach $address. Check both devices are on the same Wi-Fi and Speaker EQ is open there."
    }

    private val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Speaker EQ</title>
<style>body{font-family:-apple-system,system-ui,"Segoe UI",sans-serif;background:#f2f2f7;color:#1c1c1e;margin:0;padding:24px}
.c{background:#fff;border-radius:14px;padding:20px;max-width:560px;margin:auto;box-shadow:0 1px 2px #0002}
h1{margin:0 0 8px}p{color:#3a3a3c}textarea{width:100%;height:150px;box-sizing:border-box;border:1px solid #d1d1d6;border-radius:10px;padding:8px;font:13px monospace}
input#k{font-size:20px;width:6em;padding:6px;border:1px solid #d1d1d6;border-radius:8px}
button{background:#007aff;color:#fff;border:0;border-radius:22px;padding:12px 28px;font-size:16px;font-weight:600}
#r{font-weight:600}</style></head><body><div class="c"><h1>Speaker EQ</h1>
<p>Send an EQ to this device: a file exported from Speaker EQ, AutoEQ or Equalizer APO.</p>
<p><input type="file" id="f" accept=".txt,text/plain"></p>
<p><textarea id="t" placeholder="…or paste the file contents here"></textarea></p>
<p><label>Code shown on the receiving screen: <input id="k" inputmode="numeric" maxlength="4"></label></p>
<button onclick="go()">Send</button><p id="r"></p></div>
<script>document.getElementById('f').onchange=async e=>{document.getElementById('t').value=await e.target.files[0].text()};
async function go(){const r=document.getElementById('r');try{const x=await fetch('/eq?code='+encodeURIComponent(document.getElementById('k').value),{method:'POST',body:document.getElementById('t').value});r.textContent=await x.text()}catch(e){r.textContent='Could not reach the device.'}}</script>
</body></html>"""
}

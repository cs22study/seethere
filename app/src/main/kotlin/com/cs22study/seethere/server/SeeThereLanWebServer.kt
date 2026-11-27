package com.cs22study.seethere.server

import com.cs22study.seethere.core.CameraCodec
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.CameraNavigator
import com.cs22study.seethere.core.StreamSecuritySanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Lightweight zero-dependency LAN HTTP admin server (adapted from androidtv-news-fork620 LocalWebServer):
 * - Binds user-specified PORT on same Wi-Fi LAN
 * - Enforces private RFC1918/loopback IP allowlist (403 Forbidden for WAN)
 * - Allows adding single camera, batch CSV import, CSV export, and remote channel switch
 */
object SeeThereLanWebServer {

    interface Listener {
        fun getActiveCameras(): List<CameraItem>
        fun onLanAddCamera(title: String, url: String, region: String, city: String, favorite: Boolean): Pair<Boolean, String>
        fun onLanImportCsv(csv: String): Pair<Int, Int>
        fun onLanPlayCamera(cameraId: String): Boolean
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var activePort: Int = 8080
        private set

    @Volatile
    var localIp: String = "127.0.0.1"
        private set

    val serverUrl: String
        get() = "http://$localIp:$activePort"

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(port: Int, listener: Listener): Result<String> = runCatching {
        require(CameraNavigator.isValidServerPort(port)) { "Port 必須介於 1024–65535" }
        if (isRunning && activePort == port) {
            return@runCatching serverUrl
        }
        stop()
        localIp = getLocalIpAddress()
        val socket = ServerSocket(port)
        serverSocket = socket
        activePort = socket.localPort
        isRunning = true

        acceptJob = serverScope.launch {
            while (isRunning && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client, listener) }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
        serverUrl
    }

    @Synchronized
    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private fun handleClient(socket: Socket, listener: Listener) {
        socket.use { s ->
            s.soTimeout = 10_000
            val remoteIp = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress.orEmpty()
            if (!CameraNavigator.isPrivateOrLoopbackIp(remoteIp)) {
                sendResponse(s, 403, "text/plain; charset=utf-8", "Forbidden: Local LAN access only".toByteArray())
                return
            }

            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore("?")

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon != -1) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength in 1..2_097_152) {
                val buf = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val read = input.read(buf, offset, contentLength - offset)
                    if (read == -1) break
                    offset += read
                }
                buf
            } else {
                ByteArray(0)
            }
            val body = String(bodyBytes, StandardCharsets.UTF_8)
            dispatchRoute(s, method, path, body, listener)
        }
    }

    private fun dispatchRoute(
        socket: Socket,
        method: String,
        path: String,
        body: String,
        listener: Listener,
    ) {
        try {
            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(socket, 200, "text/html; charset=utf-8", buildDashboardHtml().toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && path == "/api/status" -> {
                    val obj = JSONObject().apply {
                        put("status", "ok")
                        put("url", serverUrl)
                        put("cameraCount", listener.getActiveCameras().size)
                    }
                    sendResponse(socket, 200, "application/json; charset=utf-8", obj.toString().toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && path == "/api/cameras" -> {
                    val arr = JSONArray()
                    listener.getActiveCameras().forEach { cam ->
                        arr.put(JSONObject().apply {
                            put("id", cam.id)
                            put("title", cam.title)
                            put("region", cam.region)
                            put("city", cam.city)
                            put("streamUrl", cam.streamUrl)
                            put("isFavorite", cam.isFavorite)
                        })
                    }
                    sendResponse(socket, 200, "application/json; charset=utf-8", arr.toString().toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && path == "/api/export.csv" -> {
                    val csv = "\uFEFF" + CameraCodec.exportToCsv(listener.getActiveCameras())
                    sendResponse(
                        socket,
                        200,
                        "text/csv; charset=utf-8",
                        csv.toByteArray(StandardCharsets.UTF_8),
                        mapOf("Content-Disposition" to "attachment; filename=\"seethere-cameras.csv\""),
                    )
                }
                method == "POST" && path == "/api/camera/add" -> {
                    val json = JSONObject(body)
                    val title = StreamSecuritySanitizer.sanitizeLabel(json.optString("title"))
                    val url = json.optString("url").trim()
                    val region = StreamSecuritySanitizer.sanitizeLabel(json.optString("region", "區網新增"))
                    val city = StreamSecuritySanitizer.sanitizeLabel(json.optString("city", "區網管理"))
                    val fav = json.optBoolean("favorite", true)
                    val (ok, msg) = listener.onLanAddCamera(title, url, region, city, fav)
                    val resp = JSONObject().put("ok", ok).put("message", msg)
                    sendResponse(socket, if (ok) 200 else 400, "application/json; charset=utf-8", resp.toString().toByteArray(StandardCharsets.UTF_8))
                }
                method == "POST" && path == "/api/import.csv" -> {
                    val (accepted, rejected) = listener.onLanImportCsv(body)
                    val resp = JSONObject().put("ok", true).put("accepted", accepted).put("rejected", rejected)
                    sendResponse(socket, 200, "application/json; charset=utf-8", resp.toString().toByteArray(StandardCharsets.UTF_8))
                }
                method == "POST" && path == "/api/camera/play" -> {
                    val json = JSONObject(body)
                    val id = json.optString("id")
                    val ok = listener.onLanPlayCamera(id)
                    val resp = JSONObject().put("ok", ok)
                    sendResponse(socket, if (ok) 200 else 404, "application/json; charset=utf-8", resp.toString().toByteArray(StandardCharsets.UTF_8))
                }
                else -> {
                    sendResponse(socket, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                }
            }
        } catch (ex: Exception) {
            val err = JSONObject().put("ok", false).put("message", ex.message ?: "Server error")
            sendResponse(socket, 400, "application/json; charset=utf-8", err.toString().toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("UTF-8")
    }

    private fun sendResponse(
        socket: Socket,
        statusCode: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $statusCode $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Cache-Control: no-store\r\n")
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    fun getLocalIpAddress(): String {
        runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    private fun buildDashboardHtml(): String = """
        <!DOCTYPE html>
        <html lang="zh-Hant">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>SeeThere 區網遙控匯入管理後台</title>
          <style>
            body{font-family:system-ui,sans-serif;background:#090d16;color:#f8fafc;margin:0;padding:16px}
            .wrap{max-width:780px;margin:auto;display:grid;gap:14px}
            .card{background:#111827;border:1px solid #1e293b;border-radius:12px;padding:14px}
            h1,h2{margin:0 0 10px 0;color:#38bdf8}
            input,textarea{width:100%;box-sizing:border-box;background:#0f172a;color:#fff;border:1px solid #334155;border-radius:8px;padding:8px;margin:4px 0 10px}
            button{background:#38bdf8;color:#0f172a;border:0;border-radius:8px;padding:9px 14px;font-weight:700;cursor:pointer;margin-right:8px}
            button.sec{background:#facc15}
            .row{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #1e293b;gap:8px}
            .msg{padding:8px;border-radius:8px;background:#1e293b;color:#facc15;display:none}
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="card">
              <h1>SeeThere 到處看看 · 區網管理後台</h1>
              <div id="statusText">載入中...</div>
              <div style="margin-top:8px">
                <a href="/api/export.csv"><button class="sec" type="button">下載匯出 CSV 清單</button></a>
              </div>
              <div id="msg" class="msg"></div>
            </div>
            <div class="card">
              <h2>新增單筆即時影像 (支援 .m3u8 / YouTube / tw.live)</h2>
              <input id="camUrl" placeholder="https://www.youtube.com/watch?v=... 或 .m3u8">
              <input id="camTitle" placeholder="鏡頭名稱（YouTube 可留空自動抓取）">
              <input id="camRegion" placeholder="區域" value="區網新增">
              <button onclick="addCam()">資安檢查並新增播放</button>
            </div>
            <div class="card">
              <h2>批次匯入 CSV</h2>
              <textarea id="csvText" rows="5" placeholder="貼上 CSV 內容..."></textarea>
              <button onclick="importCsv()">執行批次匯入</button>
            </div>
            <div class="card">
              <h2>目前裝置鏡頭清單</h2>
              <div id="camList"></div>
            </div>
          </div>
          <script>
            function esc(s){return String(s||'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));}
            function showMsg(t){const el=document.getElementById('msg');el.style.display='block';el.textContent=t;}
            async function loadAll(){
              const r=await fetch('/api/cameras'); const list=await r.json();
              document.getElementById('statusText').textContent='連線正常 · 共 '+list.length+' 支鏡頭';
              document.getElementById('camList').innerHTML=list.map(c=>
                '<div class="row"><div><b>'+esc(c.title)+'</b><br><small>'+esc(c.region)+' · '+esc(c.city)+'</small></div>'+
                '<button onclick="playCam(\''+esc(c.id)+'\')">在裝置播放</button></div>'
              ).join('');
            }
            async function addCam(){
              const res=await fetch('/api/camera/add',{method:'POST',headers:{'Content-Type':'application/json'},
                body:JSON.stringify({url:document.getElementById('camUrl').value,title:document.getElementById('camTitle').value,region:document.getElementById('camRegion').value,favorite:true})});
              const d=await res.json(); showMsg(d.message||'完成'); if(d.ok) loadAll();
            }
            async function importCsv(){
              const res=await fetch('/api/import.csv',{method:'POST',body:document.getElementById('csvText').value});
              const d=await res.json(); showMsg('匯入成功 '+d.accepted+' 筆，阻擋 '+d.rejected+' 筆'); loadAll();
            }
            async function playCam(id){
              await fetch('/api/camera/play',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id})});
              showMsg('已通知裝置切換播放');
            }
            loadAll();
          </script>
        </body>
        </html>
    """.trimIndent()
}

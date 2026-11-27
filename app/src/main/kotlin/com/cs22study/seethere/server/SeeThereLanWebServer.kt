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
                method == "OPTIONS" -> {
                    sendResponse(socket, 204, "text/plain; charset=utf-8", ByteArray(0))
                }
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    sendResponse(socket, 200, "text/html; charset=utf-8", buildDashboardHtml().toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && path == "/api/status" -> {
                    val json = """{"status":"ok","url":${jsonString(serverUrl)},"cameraCount":${listener.getActiveCameras().size}}"""
                    sendResponse(socket, 200, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
                }
                method == "GET" && path == "/api/cameras" -> {
                    val itemsJson = listener.getActiveCameras().joinToString(",") { cam ->
                        """{"id":${jsonString(cam.id)},"title":${jsonString(cam.title)},"region":${jsonString(cam.region)},"city":${jsonString(cam.city)},"streamUrl":${jsonString(cam.streamUrl)},"isFavorite":${cam.isFavorite}}"""
                    }
                    sendResponse(socket, 200, "application/json; charset=utf-8", "[$itemsJson]".toByteArray(StandardCharsets.UTF_8))
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
                    val fields = parseFlatJsonObject(body)
                    val title = StreamSecuritySanitizer.sanitizeLabel(fields["title"].orEmpty())
                    val url = fields["url"].orEmpty().trim()
                    val region = StreamSecuritySanitizer.sanitizeLabel(fields["region"].orEmpty().ifBlank { "區網新增" })
                    val city = StreamSecuritySanitizer.sanitizeLabel(fields["city"].orEmpty().ifBlank { "區網管理" })
                    val fav = fields["favorite"]?.lowercase() != "false"
                    val (ok, msg) = listener.onLanAddCamera(title, url, region, city, fav)
                    val resp = """{"ok":$ok,"message":${jsonString(msg)}}"""
                    sendResponse(socket, if (ok) 200 else 400, "application/json; charset=utf-8", resp.toByteArray(StandardCharsets.UTF_8))
                }
                method == "POST" && path == "/api/import.csv" -> {
                    val (accepted, rejected) = listener.onLanImportCsv(body)
                    val resp = """{"ok":true,"accepted":$accepted,"rejected":$rejected}"""
                    sendResponse(socket, 200, "application/json; charset=utf-8", resp.toByteArray(StandardCharsets.UTF_8))
                }
                method == "POST" && path == "/api/camera/play" -> {
                    val fields = parseFlatJsonObject(body)
                    val id = fields["id"].orEmpty()
                    val ok = listener.onLanPlayCamera(id)
                    val resp = """{"ok":$ok}"""
                    sendResponse(socket, if (ok) 200 else 404, "application/json; charset=utf-8", resp.toByteArray(StandardCharsets.UTF_8))
                }
                else -> {
                    sendResponse(socket, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
                }
            }
        } catch (t: Throwable) {
            val err = """{"ok":false,"message":${jsonString(t.message ?: "Server error")}}"""
            sendResponse(socket, 400, "application/json; charset=utf-8", err.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    internal fun parseFlatJsonObject(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = raw.length
        fun skipWs() {
            while (i < n && raw[i].isWhitespace()) i++
        }
        fun readJsonString(): String? {
            skipWs()
            if (i >= n || raw[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = raw[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < n) {
                    val esc = raw[i++]
                    when (esc) {
                        '"', '\\', '/' -> sb.append(esc)
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> if (i + 4 <= n) {
                            val code = raw.substring(i, i + 4).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        skipWs()
        if (i >= n || raw[i] != '{') return out
        i++
        while (i < n) {
            skipWs()
            if (i < n && raw[i] == '}') break
            if (i < n && raw[i] == ',') {
                i++
                continue
            }
            val key = readJsonString() ?: break
            skipWs()
            if (i >= n || raw[i] != ':') break
            i++
            skipWs()
            if (i >= n) break
            val value = if (raw[i] == '"') {
                readJsonString().orEmpty()
            } else {
                val start = i
                while (i < n && raw[i] != ',' && raw[i] != '}') i++
                raw.substring(start, i).trim()
            }
            out[key] = value
        }
        return out
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
            204 -> "No Content"
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
        sb.append("Referrer-Policy: no-referrer\r\n")
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) {
            out.write(body)
        }
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
                        if (CameraNavigator.isPrivateOrLoopbackIp(host)) {
                            return host
                        }
                    }
                }
            }
        }
        return "127.0.0.1"
    }

    internal fun buildDashboardHtml(): String = """
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
            button:disabled{opacity:.55;cursor:not-allowed}
            button.sec{background:#facc15}
            .row{display:flex;justify-content:space-between;align-items:center;padding:8px 0;border-bottom:1px solid #1e293b;gap:8px}
            .msg{margin-top:10px;padding:10px;border-radius:8px;background:#1e293b;color:#facc15;border:1px solid #334155;display:none;word-break:break-word}
            .msg.ok{color:#4ade80;border-color:#22c55e}
            .msg.err{color:#fca5a5;border-color:#ef4444}
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="card">
              <h1>SeeThere 到處看看 · 區網管理後台</h1>
              <div id="statusText">載入中...</div>
              <div style="margin-top:8px">
                <a href="/api/export.csv"><button class="sec" type="button">下載匯出 CSV 清單</button></a>
                <button id="btnRefresh" type="button" onclick="loadAll()">重新整理清單</button>
              </div>
              <div id="msg" class="msg"></div>
            </div>
            <div class="card">
              <h2>新增單筆即時影像 (支援 .m3u8 / YouTube / tw.live)</h2>
              <input id="camUrl" placeholder="https://www.youtube.com/watch?v=... 或 .m3u8">
              <input id="camTitle" placeholder="鏡頭名稱（YouTube 可留空自動抓取）">
              <input id="camRegion" placeholder="區域" value="區網新增">
              <button id="btnAdd" type="button" onclick="addCam()">資安檢查並新增播放</button>
              <div id="addMsg" class="msg"></div>
            </div>
            <div class="card">
              <h2>批次匯入 CSV</h2>
              <textarea id="csvText" rows="5" placeholder="貼上 CSV 內容..."></textarea>
              <button id="btnImport" type="button" onclick="importCsv()">執行批次匯入</button>
              <div id="importMsg" class="msg"></div>
            </div>
            <div class="card">
              <h2>目前裝置鏡頭清單</h2>
              <div id="camList"></div>
            </div>
          </div>
          <script>
            function esc(s){return String(s||'').replace(/[&<>"']/g,function(m){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m];});}
            function showBox(id,t,kind){
              var el=document.getElementById(id);
              if(!el)return;
              el.style.display='block';
              el.className='msg'+(kind==='ok'?' ok':(kind==='err'?' err':''));
              el.textContent=t;
            }
            function showMsg(t,kind){showBox('msg',t,kind);}
            async function postJsonOrText(url,payload,timeoutMs){
              var ctrl=(typeof AbortController!=='undefined')?new AbortController():null;
              var timer=ctrl?setTimeout(function(){ctrl.abort();},timeoutMs||10000):null;
              try{
                var opts={method:'POST',body:typeof payload==='string'?payload:JSON.stringify(payload)};
                if(typeof payload!=='string'){opts.headers={'Content-Type':'application/json'};}
                if(ctrl){opts.signal=ctrl.signal;}
                var res=await fetch(url,opts);
                var raw=await res.text();
                try{return {status:res.status,okHttp:res.ok,data:JSON.parse(raw)};}
                catch(_e){return {status:res.status,okHttp:res.ok,data:{ok:false,message:raw||('HTTP '+res.status)}};}
              }finally{
                if(timer)clearTimeout(timer);
              }
            }
            async function loadAll(){
              try{
                var r=await fetch('/api/cameras',{cache:'no-store'});
                if(!r.ok)throw new Error('HTTP '+r.status);
                var list=await r.json();
                document.getElementById('statusText').textContent='連線正常 · 共 '+list.length+' 支鏡頭';
                var box=document.getElementById('camList');
                box.innerHTML=list.map(function(c){
                  return '<div class="row"><div><b>'+esc(c.title)+'</b><br><small>'+esc(c.region)+' · '+esc(c.city)+'</small></div>'+
                    '<button type="button" data-cam-id="'+esc(c.id)+'" class="playBtn">在裝置播放</button></div>';
                }).join('');
                var btns=box.querySelectorAll('.playBtn');
                for(var i=0;i<btns.length;i++){
                  btns[i].addEventListener('click',function(){playCam(this.getAttribute('data-cam-id'));});
                }
              }catch(err){
                document.getElementById('statusText').textContent='無法連線至手機服務：'+(err&&err.message?err.message:err);
              }
            }
            async function addCam(){
              var btn=document.getElementById('btnAdd');
              var url=(document.getElementById('camUrl').value||'').trim();
              if(!url){showBox('addMsg','請先輸入串流或 YouTube 網址','err');return;}
              btn.disabled=true;
              btn.textContent='檢查與傳送中...';
              showBox('addMsg','⏳ 正在執行資安檢查並送出至手機...','');
              try{
                var resp=await postJsonOrText('/api/camera/add',{
                  url:url,
                  title:document.getElementById('camTitle').value||'',
                  region:document.getElementById('camRegion').value||'區網新增',
                  favorite:true
                },10000);
                var d=resp.data||{};
                var text=d.message||(d.ok?'已新增並播放':'新增失敗 (HTTP '+resp.status+')');
                showBox('addMsg',text,d.ok?'ok':'err');
                showMsg(text,d.ok?'ok':'err');
                if(d.ok){
                  document.getElementById('camUrl').value='';
                  document.getElementById('camTitle').value='';
                  await loadAll();
                }
              }catch(err){
                var errTxt='❌ 送出失敗，請確認手機 SeeThere 仍在執行且區網服務未停止（'+((err&&err.name==='AbortError')?'連線逾時':(err&&err.message?err.message:err))+'）';
                showBox('addMsg',errTxt,'err');
                showMsg(errTxt,'err');
              }finally{
                btn.disabled=false;
                btn.textContent='資安檢查並新增播放';
              }
            }
            async function importCsv(){
              var btn=document.getElementById('btnImport');
              var csv=document.getElementById('csvText').value||'';
              if(!csv.trim()){showBox('importMsg','請先貼上 CSV 內容','err');return;}
              btn.disabled=true;
              showBox('importMsg','⏳ 正在匯入 CSV...','');
              try{
                var resp=await postJsonOrText('/api/import.csv',csv,12000);
                var d=resp.data||{};
                var text='匯入成功 '+(d.accepted||0)+' 筆，阻擋 '+(d.rejected||0)+' 筆';
                showBox('importMsg',text,d.ok?'ok':'err');
                showMsg(text,d.ok?'ok':'err');
                await loadAll();
              }catch(err){
                var errTxt='❌ 匯入失敗：'+(err&&err.message?err.message:err);
                showBox('importMsg',errTxt,'err');
                showMsg(errTxt,'err');
              }finally{
                btn.disabled=false;
              }
            }
            async function playCam(id){
              try{
                await postJsonOrText('/api/camera/play',{id:id},6000);
                showMsg('已通知裝置切換播放','ok');
              }catch(err){
                showMsg('❌ 切換失敗：'+(err&&err.message?err.message:err),'err');
              }
            }
            loadAll();
          </script>
        </body>
        </html>
    """.trimIndent()
}

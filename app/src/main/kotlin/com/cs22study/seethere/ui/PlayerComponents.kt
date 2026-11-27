package com.cs22study.seethere.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.StreamProtocol
import com.cs22study.seethere.core.StreamRouter
import com.cs22study.seethere.core.StreamSecuritySanitizer
import com.cs22study.seethere.data.WraDisasterClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

@Composable
fun StreamPlayerBox(
    camera: CameraItem?,
    trafficSaverMode: Boolean,
    onSwitchToBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (camera == null) {
        Box(
            modifier = modifier.background(Color(0xFF0B1220)),
            contentAlignment = Alignment.Center,
        ) {
            Text("請從清單選擇一支即時影像", color = Color(0xFF94A3B8))
        }
        return
    }

    val sanitized = remember(camera.id, camera.streamUrl, camera.allowLocalHttp) {
        StreamSecuritySanitizer.sanitizeStreamUrl(camera.streamUrl, camera.allowLocalHttp)
    }

    if (!sanitized.isAllowed) {
        Box(
            modifier = modifier.background(Color(0xFF1F1215)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "資安防護攔截：${sanitized.blockReason ?: "網址不安全"}",
                color = Color(0xFFFCA5A5),
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    var forceLiveInSaver by remember(camera.id) { mutableStateOf(false) }

    if (trafficSaverMode && !forceLiveInSaver && camera.thumbnailUrl.isNotBlank()) {
        SnapshotImageLoop(
            imageUrl = camera.thumbnailUrl,
            refreshMillis = 8_000L,
            overlayLabel = "省流量縮圖模式 (每 8 秒更新)",
            actionLabel = "開啟即時串流",
            onActionClick = { forceLiveInSaver = true },
            modifier = modifier,
        )
        return
    }

    when (sanitized.protocol) {
        StreamProtocol.HLS -> {
            HlsExoPlayerView(
                camera = camera,
                streamUrl = sanitized.normalizedUrl,
                onSwitchToBackup = onSwitchToBackup,
                onLogEvent = onLogEvent,
                modifier = modifier,
            )
        }
        StreamProtocol.YOUTUBE -> {
            IsolatedYouTubeWebView(
                videoId = sanitized.extractedYouTubeId.orEmpty(),
                embedUrl = sanitized.normalizedUrl,
                modifier = modifier,
            )
        }
        StreamProtocol.MJPEG -> {
            MjpegStreamLoop(
                streamUrl = sanitized.normalizedUrl,
                modifier = modifier,
            )
        }
        StreamProtocol.SNAPSHOT -> {
            SnapshotImageLoop(
                imageUrl = sanitized.normalizedUrl,
                refreshMillis = 5_000L,
                overlayLabel = "定時快照模式 (每 5 秒更新)",
                actionLabel = null,
                onActionClick = null,
                modifier = modifier,
            )
        }
        StreamProtocol.WRA_API -> {
            WraDynamicSnapshotPlayer(
                camera = camera,
                apiStreamUrl = sanitized.normalizedUrl,
                onSwitchToBackup = onSwitchToBackup,
                modifier = modifier,
            )
        }
        StreamProtocol.BLOCKED -> {
            Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                Text("無法播放此串流格式", color = Color.White)
            }
        }
    }
}

@Composable
private fun HlsExoPlayerView(
    camera: CameraItem,
    streamUrl: String,
    onSwitchToBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var errorMessage by remember(camera.id) { mutableStateOf<String?>(null) }
    var retryAttempt by remember(camera.id) { mutableIntStateOf(0) }
    var reloadNonce by remember(camera.id) { mutableIntStateOf(0) }
    var fallbackToSnapshot by remember(camera.id) { mutableStateOf(false) }

    if (fallbackToSnapshot && camera.thumbnailUrl.isNotBlank()) {
        SnapshotImageLoop(
            imageUrl = camera.thumbnailUrl,
            refreshMillis = 5_000L,
            overlayLabel = "HLS 暫時維護中，已自動切換即時快照",
            actionLabel = "重試影片",
            onActionClick = {
                fallbackToSnapshot = false
                errorMessage = null
                retryAttempt = 0
                reloadNonce += 1
            },
            modifier = modifier,
        )
        return
    }

    val exoPlayer = remember(camera.id, reloadNonce) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "串流暫時中斷 (${error.errorCodeName})"
                onLogEvent("HLS_ERROR", "${camera.id}: ${error.errorCodeName}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    retryAttempt = 0
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    LaunchedEffect(errorMessage, retryAttempt) {
        if (errorMessage != null) {
            if (retryAttempt >= 1 && camera.thumbnailUrl.isNotBlank()) {
                delay(1_200L)
                fallbackToSnapshot = true
            } else {
                val waitMs = StreamRouter.computeReconnectDelayMs(retryAttempt, Random.nextLong(0, 400))
                if (waitMs != null) {
                    delay(waitMs)
                    retryAttempt += 1
                    reloadNonce += 1
                }
            }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = true
                    player = exoPlayer
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.useController = (errorMessage == null)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD90F172A))
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(errorMessage ?: "", color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        retryAttempt = 0
                        errorMessage = null
                        reloadNonce += 1
                    }) {
                        Text("重試")
                    }
                    if (camera.thumbnailUrl.isNotBlank()) {
                        OutlinedButton(onClick = { fallbackToSnapshot = true }) {
                            Text("即時快照")
                        }
                    }
                    OutlinedButton(onClick = { onSwitchToBackup(camera) }) {
                        Text("換備援")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun IsolatedYouTubeWebView(
    videoId: String,
    embedUrl: String,
    modifier: Modifier = Modifier,
) {
    var useDirectWatchMode by remember(videoId) { mutableStateOf(false) }
    val loadKey = "$videoId:$useDirectWatchMode"

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    CookieManager.getInstance().let { cm ->
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Keep 1:1 viewport in iframe mode so 2x2 Quad tiles don't crop to the top half
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    settings.textZoom = 100
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                    @Suppress("DEPRECATION")
                    settings.savePassword = false
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val target = request?.url?.toString() ?: return true
                            return !StreamSecuritySanitizer.isAllowedInYouTubeSandbox(target)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null && url.startsWith("https://www.youtube.com/watch")) {
                                // Inspired by fork620 WebViewHelper: isolate & fullscreen <video> element cleanly
                                val js = """
                                    (function() {
                                      var tries = 0;
                                      var timer = setInterval(function() {
                                        tries++;
                                        var v = document.querySelector('video');
                                        if (v) {
                                          v.muted = true;
                                          v.play().catch(function(){});
                                          v.style.position = 'fixed';
                                          v.style.top = '0';
                                          v.style.left = '0';
                                          v.style.zIndex = '999999';
                                          v.style.width = '100%';
                                          v.style.height = '100%';
                                          v.style.objectFit = 'contain';
                                          v.style.background = '#000';
                                          document.body.appendChild(v);
                                          clearInterval(timer);
                                        }
                                        if (tries > 12) clearInterval(timer);
                                      }, 500);
                                    })();
                                """.trimIndent()
                                view?.evaluateJavascript(js, null)
                            }
                        }
                    }
                }
            },
            update = { webView ->
                if (webView.tag != loadKey) {
                    webView.tag = loadKey
                    if (useDirectWatchMode && videoId.isNotBlank()) {
                        webView.settings.useWideViewPort = true
                        webView.settings.loadWithOverviewMode = true
                        webView.loadUrl(
                            "https://www.youtube.com/watch?v=$videoId",
                            mapOf("Referer" to "https://www.youtube.com/"),
                        )
                    } else if (videoId.isNotBlank()) {
                        webView.settings.useWideViewPort = false
                        webView.settings.loadWithOverviewMode = false
                        val html = StreamSecuritySanitizer.buildSafeYouTubeIframeHtml(
                            videoId = videoId,
                            originUrl = "https://com.cs22study.seethere",
                        )
                        webView.loadDataWithBaseURL(
                            "https://com.cs22study.seethere/",
                            html,
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    } else {
                        webView.loadUrl(embedUrl, mapOf("Referer" to "https://com.cs22study.seethere/"))
                    }
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (videoId.isNotBlank()) {
            OutlinedButton(
                onClick = { useDirectWatchMode = !useDirectWatchMode },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                Text(
                    if (useDirectWatchMode) "內嵌" else "直連",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun SnapshotImageLoop(
    imageUrl: String,
    refreshMillis: Long,
    overlayLabel: String,
    actionLabel: String?,
    onActionClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(imageUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUrl, refreshMillis) {
        while (isActive) {
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val sep = if (imageUrl.contains('?')) '&' else '?'
                    val conn = (URL("$imageUrl${sep}t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000
                        readTimeout = 6_000
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (fetched != null) bitmap = fetched
            delay(refreshMillis)
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = overlayLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator()
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x990F172A))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(overlayLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
            if (actionLabel != null && onActionClick != null) {
                Button(onClick = onActionClick) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun MjpegStreamLoop(
    streamUrl: String,
    modifier: Modifier = Modifier,
) {
    var frameBitmap by remember(streamUrl) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(streamUrl) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                runCatching {
                    val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000
                        readTimeout = 10_000
                    }
                    conn.inputStream.use { input ->
                        val buffer = ByteArrayOutputStream()
                        var prev = -1
                        var capturing = false
                        val chunk = ByteArray(4096)
                        while (isActive) {
                            val read = input.read(chunk)
                            if (read <= 0) break
                            for (i in 0 until read) {
                                val b = chunk[i].toInt() and 0xFF
                                if (!capturing && prev == 0xFF && b == 0xD8) {
                                    capturing = true
                                    buffer.reset()
                                    buffer.write(0xFF)
                                    buffer.write(0xD8)
                                } else if (capturing) {
                                    buffer.write(b)
                                    if (prev == 0xFF && b == 0xD9) {
                                        val bytes = buffer.toByteArray()
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                                            frameBitmap = bmp
                                        }
                                        capturing = false
                                    }
                                }
                                prev = b
                            }
                        }
                    }
                }
                delay(3_000L)
            }
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val current = frameBitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "MJPEG 即時串流",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun WraDynamicSnapshotPlayer(
    camera: CameraItem,
    apiStreamUrl: String,
    onSwitchToBackup: (CameraItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var resolvedImageUrl by remember(apiStreamUrl) { mutableStateOf<String?>(null) }
    var failed by remember(apiStreamUrl) { mutableStateOf(false) }

    LaunchedEffect(apiStreamUrl) {
        failed = false
        val url = WraDisasterClient.resolveLiveImageUrl(apiStreamUrl)
        if (url != null) {
            resolvedImageUrl = url
        } else {
            failed = true
        }
    }

    when {
        resolvedImageUrl != null -> {
            SnapshotImageLoop(
                imageUrl = resolvedImageUrl!!,
                refreshMillis = 10_000L,
                overlayLabel = "水利署即時防災影像 (每 10 秒更新)",
                actionLabel = null,
                onActionClick = null,
                modifier = modifier,
            )
        }
        failed -> {
            Column(
                modifier = modifier.background(Color(0xFF1F1215)).padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("無法取得今日水利署有效影像連結", color = Color(0xFFFCA5A5))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onSwitchToBackup(camera) }) {
                    Text("切換最近備援")
                }
            }
        }
        else -> {
            Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

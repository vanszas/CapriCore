package com.capri.mobile.remote

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.capri.mobile.AMBER
import com.capri.mobile.BG
import com.capri.mobile.CYAN
import com.capri.mobile.CapriButton
import com.capri.mobile.EDGE
import com.capri.mobile.GREEN
import com.capri.mobile.INK
import com.capri.mobile.Label
import com.capri.mobile.MONO
import com.capri.mobile.MUTED
import com.capri.mobile.ON_NEON
import com.capri.mobile.PANEL
import com.capri.mobile.PANEL_HI
import com.capri.mobile.PINK
import com.capri.mobile.Pill
import com.capri.mobile.SANS
import com.capri.mobile.capriField
import com.capri.mobile.capriSwitch
import com.capri.mobile.chamfer
import com.capri.mobile.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun ScreenRemoteSubTab() {
    val ctx = LocalContext.current
    val io = remember { Executors.newCachedThreadPool() }
    val pointerIo = remember { ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) }
    val pointerId = remember { java.util.UUID.randomUUID().toString().replace("-", "") }
    val latestMove = remember { AtomicReference<RemoteMoveReq?>(null) }
    val moveDraining = remember { AtomicBoolean(false) }
    var udpFastPathPort by remember { mutableIntStateOf(0) }
    var showRdpDialog by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    val prefs = remember { ctx.getSharedPreferences("remote_screen", 0) }
    var resolution by remember { mutableStateOf(prefs.getString("resolution", "1080p") ?: "1080p") }
    var trackpadSensitivity by remember { mutableIntStateOf(prefs.getInt("trackpad_sensitivity", 100).coerceIn(25, 300)) }
    val setTrackpadSensitivity: (Int) -> Unit = { value ->
        trackpadSensitivity = value.coerceIn(25, 300)
        prefs.edit().putInt("trackpad_sensitivity", trackpadSensitivity).apply()
    }
    var targetFps by remember { mutableIntStateOf(prefs.getInt("fps", 30)) }
    var mbps by remember { mutableIntStateOf(prefs.getInt("mbps", 8)) }
    var quality by remember { mutableIntStateOf(when (resolution) { "native" -> 85; "1080p" -> 80; "540p" -> 58; else -> 74 }) }
    var monitor by remember { mutableStateOf("primary") }
    var monitors by remember { mutableStateOf(mapOf("primary" to "Main monitor", "virtual" to "All monitors")) }
    var monitorSizes by remember { mutableStateOf(mapOf("primary" to IntSize(1920, 1080), "virtual" to IntSize(1920, 1080))) }
    var frameBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var fps by remember { mutableIntStateOf(0) }
    var streamErr by remember { mutableStateOf("") }
    var useVideo by remember { mutableStateOf(prefs.getBoolean("use_h264_stream", prefs.getBoolean("use_h264", true))) }
    val screenClient = remember(RemoteState.activeProfile, RemoteState.connected) {
        if (RemoteState.connected) RemoteState.client(ctx) else null
    }
    DisposableEffect(screenClient) {
        onDispose { screenClient?.closePointerSocket() }
    }
    var videoCoolingDown by remember(screenClient, monitor, targetFps, mbps, useVideo, playing) { mutableStateOf(false) }
    LaunchedEffect(videoCoolingDown) {
        if (videoCoolingDown) {
            delay(30_000)
            videoCoolingDown = false
        }
    }
    val playingVideo = useVideo && !videoCoolingDown
    val videoClient = screenClient.takeIf { playingVideo && playing }
    val video = remember(videoClient, monitor, targetFps, mbps) {
        videoClient?.let { RemoteVideoSource(it.baseUrl, it.token, it.deviceId, monitor, targetFps, mbps) }
    }
    var controlledApp by remember { mutableStateOf<String?>(null) }

    var showWindowPicker by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showGamepad by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }
    var showTrackpad by remember { mutableStateOf(false) }

    val cursor = remember { RemoteCursorController() }
    var statusNote by remember { mutableStateOf("") }
    var windowsMouse by remember { mutableStateOf(prefs.getBoolean("windows_mouse", false)) }
    var supportsWindowsMouse by remember { mutableStateOf(false) }
    val onVideoError = {
        fps = 0
        videoCoolingDown = true
        statusNote = "Menyambungkan ulang H.264…"
    }
    var hostAudioEnabled by remember { mutableStateOf(false) }
    var hostAudioStatus by remember { mutableStateOf("") }
    val audioProfileId = RemoteState.activeProfile?.id
    val toggleHostAudio = {
        hostAudioEnabled = !hostAudioEnabled
        hostAudioStatus = if (hostAudioEnabled) "Menyambungkan suara PC…" else "Suara PC dimatikan."
    }
    LaunchedEffect(hostAudioEnabled, RemoteState.connected, audioProfileId) {
        if (!hostAudioEnabled) return@LaunchedEffect
        if (!RemoteState.connected) {
            hostAudioEnabled = false
            hostAudioStatus = "PC host belum tersambung."
            return@LaunchedEffect
        }
        try {
            val client = checkNotNull(RemoteState.client(ctx)) { "PC host belum tersambung" }
            playHostAudio(
                client, RemotePrefs(ctx), checkNotNull(audioProfileId),
                onApproval = { hostAudioStatus = "Menunggu izin suara di Capri host…" },
                onReady = { hostAudioStatus = "Suara PC aktif." },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            hostAudioStatus = error.message ?: "Suara PC host terputus."
            hostAudioEnabled = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pointerIo.shutdownNow()
            io.shutdownNow()
        }
    }

    // Fetch monitor metadata once connected
    LaunchedEffect(RemoteState.connected) {
        supportsWindowsMouse = false
        udpFastPathPort = 0
        if (!RemoteState.connected) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { RemoteState.client(ctx)?.screenInfo() }.getOrNull()?.let { info ->
                udpFastPathPort = info.optInt("udp_fastpath_port", 0)
                val modes = info.optJSONArray("mouse_modes")
                supportsWindowsMouse = modes != null && (0 until modes.length()).any { modes.optString(it) == "windows" }
                if (!supportsWindowsMouse) windowsMouse = false
                val list = linkedMapOf("primary" to "Main monitor")
                val sizes = linkedMapOf("primary" to IntSize(1920, 1080), "virtual" to IntSize(1920, 1080))
                val arr = info.optJSONArray("monitors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i)
                        val id = m?.optString("id") ?: m?.optString("name") ?: arr.optString(i)
                        val w = m?.optInt("width", 0) ?: 0
                        val h = m?.optInt("height", 0) ?: 0
                        if (w > 0 && h > 0) {
                            if (id == "all" || id == "virtual") {
                                sizes["virtual"] = IntSize(w, h)
                            } else if (id.isNotBlank()) {
                                sizes[id] = IntSize(w, h)
                                if (m?.optBoolean("is_primary", false) == true) {
                                    sizes["primary"] = IntSize(w, h)
                                }
                            }
                        }
                        if (id.isNotBlank() && id !in setOf("all", "virtual")) {
                            list[id] = m?.optString("name", id)?.ifBlank { id } ?: id
                        }
                    }
                }
                list["virtual"] = "All monitors"
                monitors = list
                monitorSizes = sizes
                if (monitor !in list) monitor = "primary"
            }
        }
    }

    // Frame streaming loop
    LaunchedEffect(monitor, RemoteState.connected) {
        latestMove.set(null)
        cursor.reset()
        frameBitmap = null
        fps = 0
        streamErr = ""
    }
    val decodeOpts = remember {
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
    LaunchedEffect(playing, resolution, quality, targetFps, mbps, monitor, RemoteState.connected, playingVideo, showTrackpad && windowsMouse) {
        if (!playing || !RemoteState.connected || playingVideo) return@LaunchedEffect
        var frames = 0
        var lastTick = android.os.SystemClock.elapsedRealtime()
        val hideCursor = showTrackpad && windowsMouse
        while (playing && RemoteState.connected) {
            val client = RemoteState.client(ctx) ?: break
            try {
                client.screenFrames(resolution, quality, monitor, targetFps, mbps, hideCursor = hideCursor)
                    .conflate()
                    .collect { bytes ->
                        val bmp = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                        } ?: return@collect
                        val newRatio = bmp.width.toFloat() / bmp.height.toFloat()
                        if (kotlin.math.abs(newRatio - aspectRatio) > 0.005f) aspectRatio = newRatio
                        frameBitmap = bmp.asImageBitmap()
                        if (streamErr.isNotEmpty()) streamErr = ""
                        frames++
                        val now = android.os.SystemClock.elapsedRealtime()
                        val elapsed = now - lastTick
                        if (elapsed >= 1000L) {
                            val newFps = (frames * 1000L / elapsed).toInt()
                            if (newFps != fps) fps = newFps
                            frames = 0
                            lastTick = now
                        }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fps = 0
                frames = 0
                streamErr = error.message ?: "Screen stream disconnected"
                delay(450L)
                lastTick = android.os.SystemClock.elapsedRealtime()
            }
        }
    }

    fun sendInput(
        action: String,
        x: Float = cursor.x,
        y: Float = cursor.y,
        delta: Int = 0,
        text: String = "",
        key: String = "",
        updateCursor: Boolean = true,
    ) {
        val (nx, ny) = if (updateCursor) cursor.setTo(x, y) else {
            x.coerceIn(0f, 0.999999f) to y.coerceIn(0f, 0.999999f)
        }
        val selectedMonitor = monitor
        val mode = if (windowsMouse) "windows" else "virtual"
        val fastPort = udpFastPathPort

        fun flushMoveQueue() {
            if (!moveDraining.compareAndSet(false, true)) return
            pointerIo.execute {
                try {
                    while (true) {
                        val req = latestMove.getAndSet(null) ?: break
                        val client = screenClient ?: continue
                        val sentUdp = req.udpPort > 0 && client.sendPointerUdp(
                            action = "move",
                            x = req.x.toDouble(),
                            y = req.y.toDouble(),
                            monitor = req.monitor,
                            pointer = pointerId,
                            mouseMode = req.mode,
                            udpPort = req.udpPort,
                        )
                        if (sentUdp) {
                            Thread.sleep(7L)
                        } else {
                            val t0 = android.os.SystemClock.elapsedRealtime()
                            runCatching {
                                client.screenInput(
                                    action = "pointer_move",
                                    x = req.x.toDouble(),
                                    y = req.y.toDouble(),
                                    monitor = req.monitor,
                                    pointer = pointerId,
                                    mouseMode = req.mode,
                                )
                            }.onFailure { error ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    statusNote = error.message ?: "Remote pointer unavailable"
                                }
                            }
                            val waitMs = 28L - (android.os.SystemClock.elapsedRealtime() - t0)
                            if (waitMs > 0L) Thread.sleep(waitMs)
                        }
                    }
                } finally {
                    moveDraining.set(false)
                    if (latestMove.get() != null) flushMoveQueue()
                }
            }
        }

        if (action == "move") {
            latestMove.set(RemoteMoveReq(nx, ny, selectedMonitor, mode, fastPort))
            flushMoveQueue()
            return
        }

        latestMove.set(null)
        pointerIo.execute {
            val client = screenClient ?: return@execute
            val udpEligible = fastPort > 0 && action in setOf("click", "double_click", "rclick", "down", "up", "scroll")
            if (udpEligible) {
                val ok = client.sendPointerUdp(
                    action = action,
                    x = nx.toDouble(),
                    y = ny.toDouble(),
                    monitor = selectedMonitor,
                    pointer = pointerId,
                    mouseMode = mode,
                    delta = delta,
                    udpPort = fastPort,
                )
                if (ok) {
                    if (action == "up") {
                        Thread.sleep(8L)
                        client.sendPointerUdp(
                            action = "up",
                            x = nx.toDouble(),
                            y = ny.toDouble(),
                            monitor = selectedMonitor,
                            pointer = pointerId,
                            mouseMode = mode,
                            delta = delta,
                            udpPort = fastPort,
                        )
                    }
                    return@execute
                }
            }
            runCatching {
                client.screenInput(
                    action = "pointer_$action",
                    x = nx.toDouble(),
                    y = ny.toDouble(),
                    delta = delta,
                    key = key,
                    text = text,
                    monitor = selectedMonitor,
                    pointer = pointerId,
                    mouseMode = mode,
                )
            }.onFailure { error ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    statusNote = error.message ?: "Remote pointer unavailable"
                }
            }
        }
    }

    fun moveByTrackpad(dx: Float, dy: Float) {
        val (nx, ny) = cursor.moveBy(dx, dy)
        sendInput("move", nx, ny, updateCursor = false)
    }

    fun sendHotkey(key: String) {
        io.execute {
            runCatching { RemoteState.client(ctx)?.sendHotkey(key) }
        }
        statusNote = "Hotkey: $key"
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (said.isNotBlank()) {
                statusNote = "Voice → PC: $said"
                sendInput("type", text = said)
            }
        }
    }

    if (showWindowPicker) {
        DesktopWindowsDialog(
            onDismiss = { showWindowPicker = false },
            onFocused = { win ->
                controlledApp = win.title
                monitor = "virtual"
                statusNote = "Focused: ${win.title}"
                showWindowPicker = false
            },
        )
    }

    if (showKeyboard) {
        KeyboardModal(
            onDismiss = { showKeyboard = false },
            onTypeText = { text -> sendInput("type", text = text) },
            onKey = { key -> sendInput("key", key = key) },
            onHotkey = { hk -> sendInput("hotkey", key = hk) },
        )
    }

    if (showRdpDialog) {
        WindowsRdpDialog(onDismiss = { showRdpDialog = false })
    }

    if (showShortcuts) {
        CustomShortcutsModal(
            onDismiss = { showShortcuts = false },
            onRun = { key -> sendHotkey(key) },
        )
    }

    if (showFullscreen) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ScreenCanvas(
                    bitmap = frameBitmap,
                    video = video,
                    onVideoSize = { if (kotlin.math.abs(it - aspectRatio) > 0.005f) aspectRatio = it },
                    onVideoFps = { fps = it; if (statusNote == "Menyambungkan ulang H.264…") statusNote = "" },
                    onVideoError = onVideoError,
                    monitor = monitor,
                    detailEnabled = playing && RemoteState.connected && !useVideo && resolution != "native",
                    cursor = cursor,
                    showLocalCursor = showTrackpad || !windowsMouse,
                    remoteMonitorSize = monitorSizes[monitor] ?: IntSize(1920, 1080),
                    aspectRatio = aspectRatio,
                    onTap = { x, y -> sendInput("click", x, y) },
                    onDoubleTap = { x, y -> sendInput("double_click", x, y) },
                    onLongPress = { x, y -> sendInput("rclick", x, y) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showGamepad) {
                    GameControllerOverlay(
                        onKey = { key, state ->
                            io.execute { runCatching { RemoteState.client(ctx)?.controllerKey(key, state) } }
                        },
                        onReleaseAll = {
                            io.execute { runCatching { RemoteState.client(ctx)?.releaseAllControllerKeys() } }
                        },
                    )
                }
                if (showTrackpad) {
                    MovableRemoteTrackpad(
                        sensitivity = trackpadSensitivity,
                        onSensitivity = setTrackpadSensitivity,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        aspectRatio = aspectRatio,
                        onMove = { dx, dy -> moveByTrackpad(dx, dy) },
                        onDragButton = { held -> sendInput(if (held) "down" else "up") },
                        onClick = { sendInput("click") },
                        onRightClick = { sendInput("rclick") },
                        onScroll = { sendInput("scroll", delta = it) },
                        onClose = { showTrackpad = false },
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallChip(tr("Exit"), PINK, selected = true) { showFullscreen = false }
                        SmallChip(if (showGamepad) "Gamepad ON" else "Gamepad", CYAN, selected = showGamepad) {
                            showGamepad = !showGamepad
                        }
                        SmallChip("Keys", AMBER) { showKeyboard = true }
                        SmallChip("Trackpad", CYAN, selected = showTrackpad) { showTrackpad = !showTrackpad }
                        SmallChip("🖥 Windows RDP", CYAN) { showRdpDialog = true }
                    }
                    MonitorChips(monitors, monitor) { monitor = it }
                    HostAudioControls(hostAudioEnabled, hostAudioStatus, toggleHostAudio)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Window Picker + Status bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(PANEL, chamfer(8.dp))
                .border(1.dp, EDGE, chamfer(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    controlledApp ?: tr("Control Capri / PC Screen"),
                    color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (playingVideo) "$monitor · Native H.264 · $fps FPS" else "$monitor · $resolution · $fps FPS") +
                        (if (statusNote.isNotEmpty()) " · $statusNote" else ""),
                    color = MUTED, fontFamily = MONO, fontSize = 10.5.sp, maxLines = 1,
                )
            }
            SmallChip(tr("Capri / Apps"), PINK) {
                RemoteState.refreshWindows(ctx)
                showWindowPicker = true
            }
        }

        HostAudioControls(hostAudioEnabled, hostAudioStatus, toggleHostAudio)
        SmallChip("Native H.264 (Beta)", CYAN, selected = useVideo) {
            useVideo = !useVideo
            prefs.edit().putBoolean("use_h264_stream", useVideo).apply()
            statusNote = ""
        }

        // Stream controls row
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallChip(if (playing) "⏸ Pause" else "▶ Stream", if (playing) GREEN else AMBER, selected = playing) {
                playing = !playing
            }
            SmallChip("⛶ Full", CYAN) { showFullscreen = true }
            SmallChip(if (showGamepad) "🎮 Gamepad ON" else "🎮 Gamepad", PINK, selected = showGamepad) {
                showGamepad = !showGamepad
            }
            SmallChip("⌨ Keys", INK) { showKeyboard = true }
            SmallChip("Trackpad", CYAN, selected = showTrackpad) { showTrackpad = !showTrackpad }
            SmallChip("🖥 Windows RDP", CYAN) { showRdpDialog = true }
            SmallChip(if (windowsMouse) "Mouse Windows" else "Mouse virtual", CYAN, selected = windowsMouse) {
                if (supportsWindowsMouse) {
                    sendInput("up")
                    windowsMouse = !windowsMouse
                    prefs.edit().putBoolean("windows_mouse", windowsMouse).apply()
                    statusNote = if (windowsMouse) "Mouse Windows menggerakkan kursor fisik host." else "Mouse virtual aktif."
                } else {
                    statusNote = "Muat ulang gateway Capri untuk mengaktifkan Mouse Windows."
                }
            }
            SmallChip("⚡ Shortcuts", AMBER) { showShortcuts = true }
            SmallChip("🎤 Voice→PC", GREEN) {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Speak PC command"))
                }
                runCatching { voiceLauncher.launch(i) }
            }
            for ((id, name) in monitors) {
                SmallChip(tr(name), CYAN, selected = monitor == id) { monitor = id }
            }
            ScreenOption(targetFps, listOf(5, 10, 20, 30, 45, 60), { "$it FPS" }) {
                targetFps = it
                prefs.edit().putInt("fps", it).apply()
            }
            ScreenOption(mbps, listOf(1, 2, 4, 8, 16), { "$it Mbps" }) {
                mbps = it
                prefs.edit().putInt("mbps", it).apply()
            }
            ScreenOption(resolution, listOf("540p", "720p", "1080p", "native"), {
                if (it == "native") tr("Native") else it
            }) {
                resolution = it
                prefs.edit().putString("resolution", it).apply()
                quality = when (it) { "native" -> 85; "1080p" -> 80; "540p" -> 58; else -> 74 }
            }
        }

        // Live Screen Viewport + Optional Gamepad Overlay
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(chamfer(10.dp))
                .background(Color.Black)
                .border(1.dp, EDGE, chamfer(10.dp)),
        ) {
            ScreenCanvas(
                bitmap = frameBitmap,
                video = if (showFullscreen) null else video,
                onVideoSize = { if (kotlin.math.abs(it - aspectRatio) > 0.005f) aspectRatio = it },
                onVideoFps = { fps = it; if (statusNote == "Menyambungkan ulang H.264…") statusNote = "" },
                onVideoError = onVideoError,
                monitor = monitor,
                detailEnabled = playing && RemoteState.connected && !useVideo && resolution != "native",
                cursor = cursor,
                showLocalCursor = showTrackpad || !windowsMouse,
                remoteMonitorSize = monitorSizes[monitor] ?: IntSize(1920, 1080),
                aspectRatio = aspectRatio,
                onTap = { x, y -> sendInput("click", x, y) },
                onDoubleTap = { x, y -> sendInput("double_click", x, y) },
                onLongPress = { x, y -> sendInput("rclick", x, y) },
                modifier = Modifier.fillMaxSize(),
            )
            if (frameBitmap == null && !useVideo) {
                Text(
                    if (streamErr.isNotEmpty()) streamErr else tr("Waiting for PC screen frame..."),
                    color = MUTED, fontFamily = MONO, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
            if (showGamepad) {
                GameControllerOverlay(
                    onKey = { key, state ->
                        io.execute { runCatching { RemoteState.client(ctx)?.controllerKey(key, state) } }
                    },
                    onReleaseAll = {
                        io.execute { runCatching { RemoteState.client(ctx)?.releaseAllControllerKeys() } }
                    },
                )
            }
            if (showTrackpad && !showFullscreen) {
                MovableRemoteTrackpad(
                    sensitivity = trackpadSensitivity,
                    onSensitivity = setTrackpadSensitivity,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    aspectRatio = aspectRatio,
                    onMove = { dx, dy -> moveByTrackpad(dx, dy) },
                    onDragButton = { held -> sendInput(if (held) "down" else "up") },
                    onClick = { sendInput("click") },
                    onRightClick = { sendInput("rclick") },
                    onScroll = { sendInput("scroll", delta = it) },
                    onClose = { showTrackpad = false },
                )
            }
        }

        // Quick Hotkeys + Scroll + Spotify Media bar
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallChip("Esc", INK) { sendInput("key", key = "Escape") }
            SmallChip("Enter", INK) { sendInput("key", key = "Enter") }
            SmallChip("Alt+Tab", AMBER) { sendHotkey("alt+tab") }
            SmallChip("Win+D", AMBER) { sendHotkey("win+d") }
            SmallChip("🎵 Play/Pause", GREEN) {
                io.execute { runCatching { RemoteState.client(ctx)?.spotifyControl("play_pause") } }
                statusNote = "Media Play/Pause"
            }
            SmallChip("⏭ Next", GREEN) {
                io.execute { runCatching { RemoteState.client(ctx)?.spotifyControl("next") } }
                statusNote = "Media Next"
            }
            SmallChip("⏮ Prev", GREEN) {
                io.execute { runCatching { RemoteState.client(ctx)?.spotifyControl("prev") } }
                statusNote = "Media Prev"
            }
            for ((label, key) in RemoteState.shortcuts) {
                SmallChip(label, PINK) { sendHotkey(key) }
            }
        }
    }
}

@Composable
private fun HostAudioControls(enabled: Boolean, status: String, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(PANEL, chamfer(8.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmallChip(tr(if (enabled) "Matikan suara PC" else "Suara PC"), GREEN, selected = enabled, onClick = onToggle)
        if (status.isNotEmpty()) Text(status, Modifier.weight(1f), color = INK, fontSize = 12.sp)
    }
}

@Composable
private fun MonitorChips(monitors: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((id, name) in monitors) {
            SmallChip(tr(name), CYAN, selected = selected == id) { onSelect(id) }
        }
    }
}

@Composable
private fun MovableRemoteTrackpad(
    sensitivity: Int,
    onSensitivity: (Int) -> Unit,
    modifier: Modifier,
    aspectRatio: Float,
    onMove: (Float, Float) -> Unit,
    onDragButton: (Boolean) -> Unit,
    onClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val move by rememberUpdatedState(onMove)
    val pointerSensitivity by rememberUpdatedState(sensitivity)
    val dragButton by rememberUpdatedState(onDragButton)
    val ratio by rememberUpdatedState(aspectRatio)
    val click by rememberUpdatedState(onClick)
    var dragLocked by remember { mutableStateOf(false) }
    fun setDragLocked(locked: Boolean) {
        if (dragLocked == locked) return
        dragLocked = locked
        dragButton(locked)
    }
    DisposableEffect(Unit) {
        onDispose { setDragLocked(false) }
    }
    var position by remember { mutableStateOf(Offset.Zero) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val travelX = (constraints.maxWidth - padSize.width).coerceAtLeast(0).toFloat()
        val travelY = (constraints.maxHeight - padSize.height).coerceAtLeast(0).toFloat()
        Column(
            Modifier.align(Alignment.BottomEnd)
                .offset { IntOffset(position.x.coerceIn(-travelX, 0f).roundToInt(), position.y.coerceIn(-travelY, 0f).roundToInt()) }
                .width(248.dp)
                .onSizeChanged { padSize = it }
                .background(Color.Black.copy(alpha = 0.9f)).border(1.dp, CYAN).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("⠿ Trackpad"),
                    Modifier.weight(1f).pointerInput(travelX, travelY) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            position = Offset(
                                (position.x + drag.x).coerceIn(-travelX, 0f),
                                (position.y + drag.y).coerceIn(-travelY, 0f),
                            )
                        }
                    }.padding(vertical = 8.dp),
                    color = INK,
                    fontSize = 12.sp,
                )
                SmallChip(tr(if (dragLocked) "Drop" else "Drag"), CYAN, selected = dragLocked) {
                    setDragLocked(!dragLocked)
                }
                SmallChip(tr("Close"), MUTED) {
                    setDragLocked(false)
                    onClose()
                }
            }
            Box(
                Modifier.fillMaxWidth().height(104.dp).background(CYAN.copy(alpha = 0.15f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val first = awaitFirstDown()
                            first.consume()
                            var previous = first.position
                            var prevUptimeMs = first.uptimeMillis
                            var moving = false
                            val locked = dragLocked
                            var held = false
                            var totalTravel = 0f
                            val moveSlop = 2.dp.toPx()
                            val tapSlop = 6.dp.toPx()
                            val downAt = first.uptimeMillis
                            val holdAt = android.os.SystemClock.uptimeMillis() + viewConfiguration.longPressTimeoutMillis
                            try {
                                while (true) {
                                    val event = if (moving || held || locked) awaitPointerEvent() else {
                                        val remaining = (holdAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                    }
                                    if (event == null) {
                                        held = true
                                        dragButton(true)
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                    if (change.isConsumed || event.changes.any { it.id != first.id && it.pressed }) break
                                    if (!change.pressed) {
                                        change.consume()
                                        if ((!moving || (totalTravel < tapSlop && change.uptimeMillis - downAt < 240L)) && !held && !locked) {
                                            click()
                                        }
                                        break
                                    }
                                    val d = change.position - previous
                                    previous = change.position
                                    val dtMs = (change.uptimeMillis - prevUptimeMs).coerceIn(4L, 64L).toFloat()
                                    prevUptimeMs = change.uptimeMillis
                                    totalTravel += d.getDistance()
                                    if (!moving && !held && !locked && (change.position - first.position).getDistance() < moveSlop) continue
                                    moving = true
                                    change.consume()
                                    if (d != Offset.Zero) {
                                        val speedDpPerMs = (d.getDistance() / density) / dtMs
                                        val accel = (1f + (speedDpPerMs - 0.22f).coerceAtLeast(0f) * 0.9f).coerceIn(1f, 2.5f)
                                        val sens = (pointerSensitivity / 100f) * accel
                                        val dxNorm = d.x * (1.65f * sens) / size.width.coerceAtLeast(1)
                                        val dyNorm = d.y * (1.65f * sens) / size.height.coerceAtLeast(1)
                                        move(dxNorm, dyNorm)
                                    }
                                }
                            } finally {
                                if (held) dragButton(false)
                            }
                        }
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallChip(tr("Left"), CYAN) { if (dragLocked) setDragLocked(false) else onClick() }
                SmallChip(tr("Right"), CYAN) { if (!dragLocked) onRightClick() }
                SmallChip(tr("Scroll ↑"), CYAN) { onScroll(120) }
                SmallChip(tr("Scroll ↓"), CYAN) { onScroll(-120) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Sensitivity"), Modifier.weight(1f), color = INK, fontSize = 12.sp)
                SmallChip("−", CYAN) { onSensitivity(sensitivity - 25) }
                SmallChip("$sensitivity%", CYAN) { onSensitivity(100) }
                SmallChip("+", CYAN) { onSensitivity(sensitivity + 25) }
            }
        }
    }
}

@Composable
private fun ScreenCanvas(
    bitmap: ImageBitmap?,
    video: RemoteVideoSource?,
    onVideoFps: (Int) -> Unit,
    onVideoSize: (Float) -> Unit,
    onVideoError: () -> Unit,
    monitor: String,
    detailEnabled: Boolean,
    cursor: RemoteCursorController,
    showLocalCursor: Boolean,
    remoteMonitorSize: IntSize,
    aspectRatio: Float,
    onTap: (Float, Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var panOnly by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val hasFrame = bitmap != null && video == null
    val region = if (detailEnabled && hasFrame && scale > 1.05f && imageSize.width > 0 && imageSize.height > 0 &&
        viewportSize.width > 0 && viewportSize.height > 0) {
        val w = imageSize.width * scale
        val h = imageSize.height * scale
        androidx.compose.ui.geometry.Rect(
            (0.5f + (-viewportSize.width / 2f - pan.x) / w).coerceIn(0f, 1f),
            (0.5f + (-viewportSize.height / 2f - pan.y) / h).coerceIn(0f, 1f),
            (0.5f + (viewportSize.width / 2f - pan.x) / w).coerceIn(0f, 1f),
            (0.5f + (viewportSize.height / 2f - pan.y) / h).coerceIn(0f, 1f),
        )
    } else null
    var detail by remember(monitor, region) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(monitor, region) {
        val crop = region ?: return@LaunchedEffect
        if (crop.width <= 0f || crop.height <= 0f) return@LaunchedEffect
        delay(220)
        val client = RemoteState.client(ctx) ?: return@LaunchedEffect
        val coordinates = listOf(crop.left, crop.top, crop.right, crop.bottom).joinToString(",")
        while (true) {
            while (android.os.SystemClock.uptimeMillis() - cursor.lastMoveUptimeMs < 500L) {
                delay(250)
            }
            val next = try {
                val bytes = withContext(Dispatchers.IO) { client.screenDetail(monitor, coordinates) }
                    ?: break
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                detail = null
                break
            }
            detail = next
            delay(450)
        }
    }
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val longPress by rememberUpdatedState(onLongPress)
    fun zoomTo(value: Float, offset: Offset = pan) {
        scale = value.coerceIn(0.5f, 6f)
        if (scale <= 1f) panOnly = false
        val extra = (scale - 1f).coerceAtLeast(0f) / 2f
        pan = Offset(
            offset.x.coerceIn(-imageSize.width * extra, imageSize.width * extra),
            offset.y.coerceIn(-imageSize.height * extra, imageSize.height * extra),
        )
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val next = (scale * zoom).coerceIn(0.5f, 6f)
                    val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
                    val anchor = centroid - center
                    zoomTo(next, anchor + (pan - anchor) * (next / scale) + panChange)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val safeRatio = if (aspectRatio > 0.1f && aspectRatio < 10f) aspectRatio else (16f / 9f)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(safeRatio)
                .onSizeChanged { imageSize = it }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = pan.x,
                    translationY = pan.y,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off ->
                            if (panOnly) return@detectTapGestures
                            tap(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                        onDoubleTap = { off ->
                            if (panOnly) return@detectTapGestures
                            doubleTap(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                        onLongPress = { off ->
                            if (panOnly) return@detectTapGestures
                            longPress(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                    )
                },
        ) {
            if (bitmap != null || video != null) {
                if (video != null) {
                    RemoteVideo(video, Modifier.fillMaxSize(), onVideoSize, onVideoError, onVideoFps)
                } else if (bitmap != null) Image(
                    bitmap = bitmap,
                    contentDescription = "PC Screen",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                val sharp = detail
                if (sharp != null && region != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            image = sharp,
                            dstOffset = IntOffset((region.left * size.width).roundToInt(), (region.top * size.height).roundToInt()),
                            dstSize = IntSize((region.width * size.width).roundToInt().coerceAtLeast(1),
                                (region.height * size.height).roundToInt().coerceAtLeast(1)),
                        )
                    }
                }
                if (showLocalCursor) {
                    Canvas(Modifier.fillMaxSize()) {
                        val _tick = cursor.drawTick.intValue
                        val tip = Offset(cursor.x * size.width, cursor.y * size.height)
                        val remoteH = remoteMonitorSize.height.coerceAtLeast(540).toFloat()
                        // Exact 1:1 PC pixel scale so a 19px arrow matches the remote Windows cursor size
                        val u = (size.height / remoteH).coerceAtLeast(0.18f)
                        val arrow = androidx.compose.ui.graphics.Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(tip.x, tip.y + 19f * u)
                            lineTo(tip.x + 5f * u, tip.y + 14f * u)
                            lineTo(tip.x + 8f * u, tip.y + 20f * u)
                            lineTo(tip.x + 11f * u, tip.y + 19f * u)
                            lineTo(tip.x + 8f * u, tip.y + 12f * u)
                            lineTo(tip.x + 15f * u, tip.y + 12f * u)
                            close()
                        }
                        val strokePx = (1.35f * u).coerceAtLeast(1.1f)
                        drawPath(arrow, Color(0xEE081018), style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx))
                        drawPath(arrow, CYAN)
                    }
                }
            }
        }
        if (bitmap != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SmallChip("−", CYAN) { zoomTo(scale / 1.25f) }
                SmallChip("${(scale * 100).roundToInt()}%", CYAN) { zoomTo(1f, Offset.Zero) }
                SmallChip("+", CYAN) { zoomTo(scale * 1.25f) }
                if (scale > 1f) SmallChip(tr("Pan"), CYAN, selected = panOnly) { panOnly = !panOnly }
            }
        }
    }
}

@Composable
private fun GameControllerOverlay(
    onKey: (String, String) -> Unit,
    onReleaseAll: () -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose { onReleaseAll() }
    }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        SmallChip("RELEASE ALL", PINK, selected = true) { onReleaseAll() }
        // Left D-Pad (W / A / S / D)
        Column(
            Modifier.align(Alignment.BottomStart),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ControllerHoldButton("W", CYAN, onKey)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ControllerHoldButton("A", CYAN, onKey)
                ControllerHoldButton("S", CYAN, onKey)
                ControllerHoldButton("D", CYAN, onKey)
            }
        }
        // Right Action Buttons (E / F / SPACE)
        Column(
            Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ControllerHoldButton("E", GREEN, onKey)
                ControllerHoldButton("F", AMBER, onKey)
            }
            ControllerHoldButton("SPACE", PINK, onKey, widthDp = 96)
        }
    }
}

@Composable
private fun ControllerHoldButton(
    keyName: String,
    accent: Color,
    onKey: (String, String) -> Unit,
    widthDp: Int = 46,
) {
    var pressed by remember { mutableStateOf(false) }
    val wire = keyName.lowercase()
    Box(
        Modifier
            .width(widthDp.dp)
            .height(44.dp)
            .clip(chamfer(8.dp))
            .background(if (pressed) accent.copy(alpha = 0.85f) else Color(0x99121014))
            .border(1.5.dp, accent, chamfer(8.dp))
            .pointerInput(wire) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    onKey(wire, "down")
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    pressed = false
                    onKey(wire, "up")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            keyName,
            color = Color.White,
            fontFamily = MONO,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DesktopWindowsDialog(
    onDismiss: () -> Unit,
    onFocused: (DesktopWindowInfo) -> Unit,
) {
    val ctx = LocalContext.current
    var capriOnly by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val windows = RemoteState.desktopWindows.filter { !capriOnly || it.isCapri }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("Capri & PC Apps")) }
                SmallChip(tr("Refresh"), CYAN) { RemoteState.refreshWindows(ctx) }
                Spacer(Modifier.width(6.dp))
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Capri windows only"), color = INK, fontFamily = SANS, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                Switch(capriOnly, { capriOnly = it }, colors = capriSwitch())
            }
            if (err.isNotEmpty()) Text(err, color = PINK, fontFamily = SANS, fontSize = 12.sp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (windows.isEmpty()) {
                    Text(
                        if (capriOnly) tr("No Capri window open on PC. Turn off filter to see all PC windows.")
                        else tr("No active windows found."),
                        color = MUTED, fontFamily = SANS, fontSize = 12.5.sp,
                    )
                }
                for (w in windows) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL_HI, chamfer(8.dp))
                            .border(1.dp, if (w.isCapri) PINK else EDGE, chamfer(8.dp))
                            .clickable(enabled = !busy) {
                                busy = true
                                err = ""
                                Executors.newSingleThreadExecutor().execute {
                                    val res = runCatching { RemoteState.client(ctx)?.focusDesktopWindow(w.hwnd) }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        busy = false
                                        val ok = res.getOrNull()?.optBoolean("ok", false) == true
                                        if (ok) onFocused(w) else err = res.exceptionOrNull()?.message ?: "Focus failed"
                                    }
                                }
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(w.title, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                            Text(w.process, color = MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1)
                        }
                        SmallChip(tr("Focus"), if (w.isCapri) PINK else GREEN) {
                            busy = true
                            Executors.newSingleThreadExecutor().execute {
                                val res = runCatching { RemoteState.client(ctx)?.focusDesktopWindow(w.hwnd) }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    busy = false
                                    if (res.getOrNull()?.optBoolean("ok", false) == true) onFocused(w)
                                    else err = res.exceptionOrNull()?.message ?: "Focus failed"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardModal(
    onDismiss: () -> Unit,
    onTypeText: (String) -> Unit,
    onKey: (String) -> Unit,
    onHotkey: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var imageStatus by remember { mutableStateOf("") }
    var sendingImage by remember { mutableStateOf(false) }
    val sendImage: (Uri) -> Unit = { uri ->
        sendingImage = true
        imageStatus = ""
        Thread {
            val result = runCatching {
                val bytes = checkNotNull(ctx.contentResolver.openInputStream(uri)) { "Gambar tidak bisa dibaca" }
                    .use { input ->
                        val buffer = ByteArray(8192)
                        val output = java.io.ByteArrayOutputStream()
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 8 * 1024 * 1024) { "Ukuran gambar maksimal 8 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                require(bytes.size in 1..(8 * 1024 * 1024)) { "Ukuran gambar maksimal 8 MB" }
                checkNotNull(RemoteState.client(ctx)) { "PC belum tersambung" }.pasteImage(bytes)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                sendingImage = false
                imageStatus = result.exceptionOrNull()?.message ?: "Ctrl+V dikirim ke aplikasi aktif di PC."
            }
        }.start()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendImage(uri)
    }
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("PC Keyboard Input")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(tr("Text to type on PC")) },
                maxLines = 5,
                colors = capriField(),
                shape = chamfer(9.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            CapriButton(tr("Type on PC"), Modifier.fillMaxWidth(), color = GREEN, enabled = text.isNotEmpty()) {
                onTypeText(text)
                text = ""
            }
            CapriButton(if (sendingImage) "Mengirim gambar…" else "Paste gambar ke PC",
                Modifier.fillMaxWidth(), color = CYAN, enabled = !sendingImage) {
                val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val uri = clipboard?.primaryClip?.takeIf { it.description.hasMimeType("image/*") }
                    ?.getItemAt(0)?.uri
                if (uri != null) sendImage(uri) else pickImage.launch("image/*")
            }
            if (imageStatus.isNotEmpty()) Text(imageStatus, color = MUTED, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Enter", INK) { onKey("Enter") }
                SmallChip("Backspace", INK) { onKey("Backspace") }
                SmallChip("Delete", INK) { onKey("Delete") }
                SmallChip("Tab", INK) { onKey("Tab") }
                SmallChip("Ctrl+A", CYAN) { onHotkey("ctrl+a") }
                SmallChip("Ctrl+C", CYAN) { onHotkey("ctrl+c") }
                SmallChip("Ctrl+V", CYAN) { onHotkey("ctrl+v") }
                SmallChip("Ctrl+Z", CYAN) { onHotkey("ctrl+z") }
            }
        }
    }
}

@Composable
private fun CustomShortcutsModal(onDismiss: () -> Unit, onRun: (String) -> Unit) {
    val ctx = LocalContext.current
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("Custom PC Shortcuts")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            for (sc in RemoteState.shortcuts) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PANEL_HI, chamfer(8.dp))
                        .border(1.dp, EDGE, chamfer(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sc.first, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(sc.second, color = MUTED, fontFamily = MONO, fontSize = 11.sp)
                    }
                    SmallChip(tr("Run"), GREEN) { onRun(sc.second); onDismiss() }
                    Spacer(Modifier.width(6.dp))
                    SmallChip("✕", PINK) {
                        val updated = RemoteState.shortcuts.filterNot { it == sc }
                        RemoteState.shortcuts = updated
                        RemotePrefs(ctx).saveCustomShortcuts(updated)
                        Executors.newSingleThreadExecutor().execute {
                            runCatching { RemoteState.client(ctx)?.saveShortcuts(updated) }
                        }
                    }
                }
            }
            OutlinedTextField(label, { label = it }, label = { Text(tr("Label (e.g. Save)")) }, singleLine = true, colors = capriField(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text(tr("Hotkey (e.g. ctrl+s)")) }, singleLine = true, colors = capriField(), modifier = Modifier.fillMaxWidth())
            CapriButton(tr("Add Shortcut"), Modifier.fillMaxWidth(), color = GREEN, enabled = label.isNotBlank() && key.isNotBlank()) {
                val updated = RemoteState.shortcuts + (label.trim() to key.trim().lowercase())
                RemoteState.shortcuts = updated
                RemotePrefs(ctx).saveCustomShortcuts(updated)
                Executors.newSingleThreadExecutor().execute {
                    runCatching { RemoteState.client(ctx)?.saveShortcuts(updated) }
                }
                label = ""
                key = ""
            }
        }
    }
}

private class RemoteCursorController {
    @Volatile var x: Float = 0.5f
    @Volatile var y: Float = 0.5f
    @Volatile var lastMoveUptimeMs: Long = 0L
    val drawTick = mutableIntStateOf(0)

    fun reset() {
        x = 0.5f
        y = 0.5f
        drawTick.intValue++
    }

    fun setTo(nx: Float, ny: Float): Pair<Float, Float> {
        val cx = nx.coerceIn(0f, 0.999999f)
        val cy = ny.coerceIn(0f, 0.999999f)
        x = cx
        y = cy
        drawTick.intValue++
        return cx to cy
    }

    fun moveBy(dx: Float, dy: Float): Pair<Float, Float> {
        val cx = (x + dx).coerceIn(0f, 0.999999f)
        val cy = (y + dy).coerceIn(0f, 0.999999f)
        x = cx
        y = cy
        lastMoveUptimeMs = android.os.SystemClock.uptimeMillis()
        drawTick.intValue++
        return cx to cy
    }
}

private data class RemoteMoveReq(
    val x: Float,
    val y: Float,
    val monitor: String,
    val mode: String,
    val udpPort: Int,
)

@Composable
private fun <T> ScreenOption(current: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SmallChip("${label(current)} ▾", MUTED) { expanded = true }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label(option)) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
private fun WindowsRdpDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var reloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadNonce) {
        loading = true
        val res = withContext(Dispatchers.IO) { runCatching { RemoteState.client(ctx)?.rdpStatus() } }
        res.onSuccess { status = it }.onFailure { message = it.message ?: "Gagal memeriksa status RDP." }
        loading = false
    }

    val info = status
    val host = info?.optString("host").orEmpty().ifBlank { RemoteState.client(ctx)?.hostAddress().orEmpty() }
    val port = info?.optInt("port", 3389) ?: 3389
    val username = info?.optString("username").orEmpty()
    val listening = info?.optBoolean("listening", false) == true

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label("Windows Remote Desktop (RDP)") }
                SmallChip(tr("Refresh"), CYAN) { reloadNonce++ }
                Spacer(Modifier.width(6.dp))
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Text(
                tr("Native Microsoft RDP (0 delay untuk video & kursor). Catatan: layar fisik PC host otomatis ter-lock saat sesi RDP tersambung."),
                color = MUTED,
                fontFamily = SANS,
                fontSize = 12.sp,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(PANEL_HI, chamfer(8.dp))
                    .border(1.dp, EDGE, chamfer(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (host.isBlank()) "Host belum tersambung" else "$host:$port",
                        Modifier.weight(1f),
                        color = INK,
                        fontFamily = MONO,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Pill(
                        if (loading) "Checking…" else if (listening) "Siap Konek" else "Belum Aktif",
                        if (listening) GREEN else AMBER,
                    )
                }
                if (info?.optBoolean("legacy") == true) Text("Aktifkan RDP melalui Pengaturan Windows di PC host.", color = MUTED, fontSize = 12.sp)
                if (username.isNotBlank()) {
                    Text("User Windows: $username", color = MUTED, fontFamily = MONO, fontSize = 11.5.sp)
                }
            }
            CapriButton(
                if (listening) "Matikan RDP di PC" else "Nyalakan RDP di PC",
                Modifier.fillMaxWidth(),
                color = if (listening) AMBER else CYAN,
                enabled = !busy && info?.optBoolean("supported") == true && !info.optBoolean("legacy"),
            ) {
                busy = true
                message = ""
                Executors.newSingleThreadExecutor().execute {
                    val result = runCatching {
                        checkNotNull(RemoteState.client(ctx)) { "Host belum tersambung" }.setRdpEnabled(!listening)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        result.onSuccess {
                            status = it
                            message = if (it.optBoolean("listening")) "RDP aktif dan siap dikoneksikan." else "RDP dimatikan."
                        }.onFailure {
                            message = it.message ?: "Gagal mengubah status RDP."
                        }
                    }
                }
            }
            if (message.isNotEmpty()) {
                Text(message, color = AMBER, fontFamily = SANS, fontSize = 12.sp)
            }
            CapriButton(
                tr("Konek via Microsoft Remote Desktop"),
                Modifier.fillMaxWidth(),
                color = GREEN,
                enabled = !busy && host.isNotBlank(),
            ) {
                busy = true
                message = ""
                Executors.newSingleThreadExecutor().execute {
                    val result = runCatching {
                        val client = checkNotNull(RemoteState.client(ctx)) { "Host belum tersambung" }
                        val bytes = client.buildOrDownloadRdpFile()
                        val dir = File(ctx.cacheDir, "rdp").apply { mkdirs() }
                        val file = File(dir, "Capri-PC.rdp").apply { writeBytes(bytes) }
                        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        val uri = result.getOrNull()
                        if (uri == null) {
                            message = result.exceptionOrNull()?.message ?: "Gagal menyiapkan file .rdp"
                            return@post
                        }
                        val fileIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/x-rdp")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (runCatching { ctx.startActivity(fileIntent) }.isSuccess) {
                            onDismiss()
                            return@post
                        }
                        val encodedUser = Uri.encode(username)
                        val rdpUrl = Uri.parse(
                            "rdp://full%20address=s:$host:$port" +
                                (if (encodedUser.isNotBlank()) "&username=s:$encodedUser" else "") +
                                "&audiomode=i:0",
                        )
                        val schemeIntent = Intent(Intent.ACTION_VIEW, rdpUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (runCatching { ctx.startActivity(schemeIntent) }.isSuccess) {
                            onDismiss()
                            return@post
                        }
                        message = "Install Windows App / Microsoft Remote Desktop di Play Store terlebih dahulu."
                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.microsoft.rdc.androidx"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (runCatching { ctx.startActivity(market) }.isFailure) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.microsoft.rdc.androidx"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }
                }
            }
            CapriButton(
                tr("Buka Pengaturan RDP di PC Host"),
                Modifier.fillMaxWidth(),
                quiet = true,
                color = CYAN,
                enabled = !busy,
            ) {
                busy = true
                Executors.newSingleThreadExecutor().execute {
                    val res = runCatching { RemoteState.client(ctx)?.rdpOpenHostSettings() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        res.onSuccess {
                            message = "Pengaturan Remote Desktop dibuka di PC host. Aktifkan lalu tekan Refresh."
                        }.onFailure {
                            message = it.message ?: "Gagal membuka Pengaturan RDP di PC host."
                        }
                    }
                }
            }
        }
    }
}

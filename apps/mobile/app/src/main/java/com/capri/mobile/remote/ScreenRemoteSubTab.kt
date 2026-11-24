package com.capri.mobile.remote

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
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
    val pendingMove = remember { arrayOfNulls<Runnable>(1) }
    var playing by remember { mutableStateOf(true) }
    var resolution by remember { mutableStateOf("720p") }
    val prefs = remember { ctx.getSharedPreferences("remote_screen", 0) }
    var trackpadSensitivity by remember { mutableIntStateOf(prefs.getInt("trackpad_sensitivity", 100).coerceIn(25, 300)) }
    val setTrackpadSensitivity: (Int) -> Unit = { value ->
        trackpadSensitivity = value.coerceIn(25, 300)
        prefs.edit().putInt("trackpad_sensitivity", trackpadSensitivity).apply()
    }
    var targetFps by remember { mutableIntStateOf(prefs.getInt("fps", 30)) }
    var mbps by remember { mutableIntStateOf(prefs.getInt("mbps", 8)) }
    var quality by remember { mutableIntStateOf(62) }
    var monitor by remember { mutableStateOf("primary") }
    var monitors by remember { mutableStateOf(mapOf("primary" to "Main monitor", "virtual" to "All monitors")) }
    var frameBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var fps by remember { mutableIntStateOf(0) }
    var streamErr by remember { mutableStateOf("") }
    var useVideo by remember { mutableStateOf(false) }
    val videoClient = if (useVideo && playing && RemoteState.connected) RemoteState.client(ctx) else null
    val video = videoClient?.let { RemoteVideoSource(it.baseUrl, it.token, it.deviceId, monitor, targetFps, mbps) }
    var controlledApp by remember { mutableStateOf<String?>(null) }

    var showWindowPicker by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showGamepad by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }
    var showTrackpad by remember { mutableStateOf(false) }

    var cursorX by remember { mutableFloatStateOf(0.5f) }
    var cursorY by remember { mutableFloatStateOf(0.5f) }
    var statusNote by remember { mutableStateOf("") }
    var windowsMouse by remember { mutableStateOf(prefs.getBoolean("windows_mouse", false)) }
    var supportsWindowsMouse by remember { mutableStateOf(false) }
    val onVideoError = {
        useVideo = false
        statusNote = "Video H.264 terputus atau gagal mulai. Kembali ke JPEG."
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
        if (!RemoteState.connected) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { RemoteState.client(ctx)?.screenInfo() }.getOrNull()?.let { info ->
                val modes = info.optJSONArray("mouse_modes")
                supportsWindowsMouse = modes != null && (0 until modes.length()).any { modes.optString(it) == "windows" }
                if (!supportsWindowsMouse) windowsMouse = false
                val list = linkedMapOf("primary" to "Main monitor")
                val arr = info.optJSONArray("monitors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i)
                        val id = m?.optString("id") ?: m?.optString("name") ?: arr.optString(i)
                        if (id.isNotBlank() && id !in setOf("all", "virtual")) {
                            list[id] = m?.optString("name", id)?.ifBlank { id } ?: id
                        }
                    }
                }
                list["virtual"] = "All monitors"
                monitors = list
                if (monitor !in list) monitor = "primary"
            }
        }
    }

    // Frame streaming loop
    LaunchedEffect(monitor, RemoteState.connected) {
        pendingMove[0]?.let(pointerIo::remove)
        pendingMove[0] = null
        cursorX = 0.5f
        cursorY = 0.5f
        frameBitmap = null
        fps = 0
        streamErr = ""
    }
    val decodeOpts = remember {
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    }
    LaunchedEffect(playing, resolution, quality, targetFps, mbps, monitor, RemoteState.connected, useVideo) {
        if (!playing || !RemoteState.connected || useVideo) return@LaunchedEffect
        var frames = 0
        var lastTick = android.os.SystemClock.elapsedRealtime()
        while (playing && RemoteState.connected) {
            val client = RemoteState.client(ctx) ?: break
            try {
                client.screenFrames(resolution, quality, monitor, targetFps, mbps)
                    .conflate()
                    .collect { bytes ->
                        val bmp = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                        } ?: return@collect
                        aspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                        frameBitmap = bmp.asImageBitmap()
                        streamErr = ""
                        frames++
                        val now = android.os.SystemClock.elapsedRealtime()
                        val elapsed = now - lastTick
                        if (elapsed >= 1000L) {
                            fps = (frames * 1000L / elapsed).toInt()
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

    fun sendInput(action: String, x: Float = cursorX, y: Float = cursorY, delta: Int = 0, text: String = "", key: String = "") {
        val nx = x.coerceIn(0f, 0.999999f)
        val ny = y.coerceIn(0f, 0.999999f)
        cursorX = nx
        cursorY = ny
        val selectedMonitor = monitor
        val mode = if (windowsMouse) "windows" else "virtual"
        val pointerInput = Runnable {
            runCatching {
                RemoteState.client(ctx)?.screenInput(
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
        if (action != "up") pendingMove[0]?.let(pointerIo::remove)
        pendingMove[0] = if (action == "move") pointerInput else null
        pointerIo.execute(pointerInput)
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
                    onVideoSize = { aspectRatio = it },
                    onVideoError = onVideoError,
                    monitor = monitor,
                    detailEnabled = playing && RemoteState.connected,
                    localPointer = if (showTrackpad && !windowsMouse) Offset(cursorX, cursorY) else null,
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
                        onMove = { dx, dy -> sendInput("move", cursorX + dx, cursorY + dy) },
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
                    (if (useVideo) "$monitor · Native H.264" else "$monitor · $resolution · $fps FPS") +
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
            for (rate in listOf(5, 10, 20, 30)) {
                SmallChip("$rate FPS", MUTED, selected = targetFps == rate) {
                    targetFps = rate
                    prefs.edit().putInt("fps", rate).apply()
                }
            }
            for (rate in listOf(1, 2, 4, 8, 16)) {
                SmallChip("$rate Mbps", MUTED, selected = mbps == rate) {
                    mbps = rate
                    prefs.edit().putInt("mbps", rate).apply()
                }
            }
            for (r in listOf("540p", "720p", "1080p", "native")) {
                SmallChip(if (r == "native") tr("Native") else r, MUTED, selected = resolution == r) {
                    resolution = r
                    quality = when (r) { "native" -> 80; "1080p" -> 75; "540p" -> 50; else -> 62 }
                }
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
                onVideoSize = { aspectRatio = it },
                onVideoError = onVideoError,
                monitor = monitor,
                detailEnabled = playing && RemoteState.connected,
                localPointer = if (showTrackpad && !windowsMouse) Offset(cursorX, cursorY) else null,
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
                    onMove = { dx, dy -> sendInput("move", cursorX + dx, cursorY + dy) },
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
                            var moving = false
                            val locked = dragLocked
                            var held = false
                            var pending = Offset.Zero
                            var lastSent = first.uptimeMillis
                            fun flush() {
                                if (pending != Offset.Zero) {
                                    val scale = 1.35f * (pointerSensitivity / 100f) / size.width.coerceAtLeast(1)
                                    move(pending.x * scale, pending.y * scale * ratio)
                                    pending = Offset.Zero
                                }
                            }
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
                                        flush()
                                        if (!moving && !held && !locked) click()
                                        break
                                    }
                                    if (!moving && !held && !locked && (change.position - first.position).getDistance() < viewConfiguration.touchSlop) continue
                                    moving = true
                                    pending += change.position - previous
                                    previous = change.position
                                    change.consume()
                                    if (change.uptimeMillis - lastSent >= 24) {
                                        flush()
                                        lastSent = change.uptimeMillis
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
    onVideoSize: (Float) -> Unit,
    onVideoError: () -> Unit,
    monitor: String,
    detailEnabled: Boolean,
    localPointer: Offset?,
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
                    RemoteVideo(video, Modifier.fillMaxSize(), onVideoSize, onVideoError)
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
                if (localPointer != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val tip = Offset(localPointer.x * size.width, localPointer.y * size.height)
                        val arrow = androidx.compose.ui.graphics.Path().apply {
                            moveTo(tip.x, tip.y)
                            lineTo(tip.x, tip.y + 17.dp.toPx())
                            lineTo(tip.x + 5.dp.toPx(), tip.y + 12.dp.toPx())
                            lineTo(tip.x + 12.dp.toPx(), tip.y + 11.dp.toPx())
                            close()
                        }
                        drawPath(arrow, Color.Black, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
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
                colors = capriField(),
                shape = chamfer(9.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            CapriButton(tr("Type on PC"), Modifier.fillMaxWidth(), color = GREEN, enabled = text.isNotEmpty()) {
                onTypeText(text)
                text = ""
            }
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

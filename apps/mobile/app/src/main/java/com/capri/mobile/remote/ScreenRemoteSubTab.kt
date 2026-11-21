package com.capri.mobile.remote

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun ScreenRemoteSubTab() {
    val ctx = LocalContext.current
    val io = remember { Executors.newCachedThreadPool() }
    var playing by remember { mutableStateOf(true) }
    var resolution by remember { mutableStateOf("720p") }
    var quality by remember { mutableIntStateOf(70) }
    var monitor by remember { mutableStateOf("primary") }
    var monitors by remember { mutableStateOf<List<String>>(listOf("primary", "virtual")) }
    var frameBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var fps by remember { mutableIntStateOf(0) }
    var streamErr by remember { mutableStateOf("") }
    var controlledApp by remember { mutableStateOf<String?>(null) }

    var showWindowPicker by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }
    var showGamepad by remember { mutableStateOf(false) }
    var showFullscreen by remember { mutableStateOf(false) }

    var cursorX by remember { mutableFloatStateOf(0.5f) }
    var cursorY by remember { mutableFloatStateOf(0.5f) }
    var showCursor by remember { mutableStateOf(false) }
    var statusNote by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { io.shutdownNow() }
    }

    // Fetch monitor metadata once connected
    LaunchedEffect(RemoteState.connected) {
        if (!RemoteState.connected) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { RemoteState.client(ctx)?.screenInfo() }.getOrNull()?.let { info ->
                val list = mutableListOf<String>()
                val arr = info.optJSONArray("monitors")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i)
                        val id = m?.optString("id") ?: m?.optString("name") ?: arr.optString(i)
                        if (id.isNotBlank()) list.add(id)
                    }
                }
                if ("primary" !in list) list.add(0, "primary")
                if ("virtual" !in list) list.add("virtual")
                monitors = list.distinct()
            }
        }
    }

    // Frame streaming loop
    LaunchedEffect(playing, resolution, quality, monitor, RemoteState.connected) {
        if (!playing || !RemoteState.connected) return@LaunchedEffect
        var frames = 0
        var lastTick = System.currentTimeMillis()
        while (playing && RemoteState.connected) {
            val client = RemoteState.client(ctx) ?: break
            val bytes = withContext(Dispatchers.IO) {
                runCatching { client.screenFrame(resolution, quality, monitor) }.getOrElse { e ->
                    streamErr = e.message ?: "Frame error"
                    null
                }
            }
            if (bytes != null && bytes.isNotEmpty()) {
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    aspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                    frameBitmap = bmp.asImageBitmap()
                    streamErr = ""
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 1000L) {
                        fps = frames
                        frames = 0
                        lastTick = now
                    }
                }
                delay(45L)
            } else {
                delay(450L)
            }
        }
    }

    fun sendInput(action: String, x: Float = cursorX, y: Float = cursorY, delta: Int = 0, text: String = "", key: String = "") {
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        cursorX = nx
        cursorY = ny
        showCursor = true
        io.execute {
            runCatching {
                RemoteState.client(ctx)?.screenInput(
                    action = action,
                    x = nx.toDouble(),
                    y = ny.toDouble(),
                    delta = delta,
                    key = key,
                    text = text,
                    monitor = monitor,
                )
            }
        }
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
                io.execute { runCatching { RemoteState.client(ctx)?.voiceInput(said) } }
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
            onHotkey = { hk -> sendHotkey(hk) },
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
                    aspectRatio = aspectRatio,
                    cursorX = cursorX,
                    cursorY = cursorY,
                    showCursor = showCursor,
                    onTap = { x, y -> sendInput("click", x, y) },
                    onDoubleTap = { x, y -> sendInput("double_click", x, y) },
                    onLongPress = { x, y -> sendInput("right_click", x, y) },
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
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SmallChip(tr("Exit"), PINK, selected = true) { showFullscreen = false }
                    SmallChip(if (showGamepad) "Gamepad ON" else "Gamepad", CYAN, selected = showGamepad) {
                        showGamepad = !showGamepad
                    }
                    SmallChip("Keys", AMBER) { showKeyboard = true }
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
                    "$monitor · $resolution · $fps FPS" + (if (statusNote.isNotEmpty()) " · $statusNote" else ""),
                    color = MUTED, fontFamily = MONO, fontSize = 10.5.sp, maxLines = 1,
                )
            }
            SmallChip(tr("Capri / Apps"), PINK) {
                RemoteState.refreshWindows(ctx)
                showWindowPicker = true
            }
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
            SmallChip("⚡ Shortcuts", AMBER) { showShortcuts = true }
            SmallChip("🎤 Voice→PC", GREEN) {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, tr("Speak PC command"))
                }
                runCatching { voiceLauncher.launch(i) }
            }
            for (m in monitors) {
                SmallChip(m, CYAN, selected = monitor == m) { monitor = m }
            }
            for (r in listOf("540p", "720p", "1080p")) {
                SmallChip(r, MUTED, selected = resolution == r) {
                    resolution = r
                    quality = when (r) { "1080p" -> 80; "540p" -> 55; else -> 70 }
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
                aspectRatio = aspectRatio,
                cursorX = cursorX,
                cursorY = cursorY,
                showCursor = showCursor,
                onTap = { x, y -> sendInput("click", x, y) },
                onDoubleTap = { x, y -> sendInput("double_click", x, y) },
                onLongPress = { x, y -> sendInput("right_click", x, y) },
                modifier = Modifier.fillMaxSize(),
            )
            if (frameBitmap == null) {
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
        }

        // Quick Hotkeys + Scroll + Spotify Media bar
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallChip("Scroll ↑", CYAN) { sendInput("scroll", delta = 120) }
            SmallChip("Scroll ↓", CYAN) { sendInput("scroll", delta = -120) }
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
private fun ScreenCanvas(
    bitmap: ImageBitmap?,
    aspectRatio: Float,
    cursorX: Float,
    cursorY: Float,
    showCursor: Boolean,
    onTap: (Float, Float) -> Unit,
    onDoubleTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    pan = if (nextScale <= 1.01f) Offset.Zero else pan + panChange
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val safeRatio = if (aspectRatio > 0.1f && aspectRatio < 10f) aspectRatio else (16f / 9f)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(safeRatio)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = pan.x,
                    translationY = pan.y,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off ->
                            onTap(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                        onDoubleTap = { off ->
                            onDoubleTap(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                        onLongPress = { off ->
                            onLongPress(
                                (off.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (off.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                        },
                    )
                },
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "PC Screen",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showCursor) {
                val px = (cursorX * constraints.maxWidth).roundToInt()
                val py = (cursorY * constraints.maxHeight).roundToInt()
                Box(
                    Modifier
                        .offset { IntOffset(px - 7, py - 7) }
                        .size(14.dp)
                        .background(Color(0xCCFF9100), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape),
                )
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

package com.capri.mobile.remote

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.capri.mobile.AMBER
import com.capri.mobile.BG
import com.capri.mobile.CYAN
import com.capri.mobile.CapriButton
import com.capri.mobile.Card
import com.capri.mobile.EDGE
import com.capri.mobile.GREEN
import com.capri.mobile.INK
import com.capri.mobile.Label
import com.capri.mobile.MONO
import com.capri.mobile.MUTED
import com.capri.mobile.ON_GREEN
import com.capri.mobile.ON_NEON
import com.capri.mobile.PANEL
import com.capri.mobile.PANEL_HI
import com.capri.mobile.PINK
import com.capri.mobile.Pill
import com.capri.mobile.SANS
import com.capri.mobile.Segments
import com.capri.mobile.capriField
import com.capri.mobile.chamfer
import com.capri.mobile.tr
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

private enum class RemoteSubTab(val label: String) {
    AGENTS("Agents"),
    SCREEN("Screen"),
    FILES("Files"),
    TASKS("Tasks"),
    USAGE("Usage"),
}

@Composable
fun RemoteHubScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { RemoteState.ensureInit(ctx) }
    var subTab by remember { mutableStateOf(RemoteSubTab.AGENTS) }
    LaunchedEffect(RemoteState.requestedSubTab) {
        val req = RemoteState.requestedSubTab
        if (req != null) {
            RemoteSubTab.entries.firstOrNull { it.name.equals(req, ignoreCase = true) }?.let { subTab = it }
            RemoteState.requestedSubTab = null
        }
    }
    var showPair by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    if (showWorkspacePicker) FolderPickerDialog(onDismiss = { showWorkspacePicker = false })

    val profile = RemoteState.activeProfile
    if (showPair) {
        PairPcDialog(
            onDismiss = { showPair = false },
            onPaired = { showPair = false },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // Top PC Connection Status Strip
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(PANEL, chamfer(9.dp))
                .border(1.dp, if (RemoteState.connected) GREEN else EDGE, chamfer(9.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when {
                            RemoteState.connected -> GREEN
                            RemoteState.connecting -> AMBER
                            else -> PINK
                        },
                        CircleShape,
                    ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.name ?: tr("No PC paired yet"),
                    color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        RemoteState.connecting -> tr("Connecting...")
                        RemoteState.connected -> {
                            val ws = RemoteState.activeWorkspace.substringAfterLast('\\').substringAfterLast('/')
                            val ts = if (profile?.preferTailscale == true) "Tailscale" else "LAN"
                            "$ts · ${ws.ifBlank { profile?.activeEndpoint().orEmpty() }}"
                        }
                        RemoteState.statusError.isNotEmpty() -> RemoteState.statusError.take(55)
                        else -> tr("Pair your Windows Capri / Agent Remote host")
                    },
                    color = MUTED, fontFamily = MONO, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (RemoteState.connected) {
                SmallChip(tr("Workspace"), CYAN) { showWorkspacePicker = true }
            }
            if (profile != null && !RemoteState.connected && !RemoteState.connecting) {
                SmallChip(tr("Retry"), AMBER) { RemoteState.connectActive(ctx) }
            }
            SmallChip(if (profile == null) tr("Pair PC") else tr("+ Pair"), PINK) { showPair = true }
        }

        if (profile == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    tr("Connect Capri Mobile to your PC"),
                    tr("Control Capri on desktop, run Codex/Claude/Gemini/OpenCode/Hermes agents, stream your live PC screen, browse files & Git, monitor background tasks, and track 9Router quotas right from Capri Mobile."),
                ) {
                    CapriButton(tr("Pair PC"), Modifier.fillMaxWidth(), color = GREEN) { showPair = true }
                }
            }
            return
        }

        // Sub-tab bar (Agents | Screen | Files | Tasks | Usage)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .background(PANEL, chamfer(9.dp))
                .border(1.dp, EDGE, chamfer(9.dp))
                .padding(3.dp),
        ) {
            for (t in RemoteSubTab.entries) {
                val sel = t == subTab
                Box(
                    Modifier
                        .weight(1f)
                        .clip(chamfer(7.dp))
                        .background(if (sel) PINK else Color.Transparent)
                        .clickable { subTab = t }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tr(t.label),
                        color = if (sel) ON_NEON else MUTED,
                        fontFamily = SANS,
                        fontWeight = if (sel) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (subTab) {
                RemoteSubTab.AGENTS -> AgentsSubTab()
                RemoteSubTab.SCREEN -> ScreenRemoteSubTab()
                RemoteSubTab.FILES -> FilesGitSubTab()
                RemoteSubTab.TASKS -> TasksSubTab()
                RemoteSubTab.USAGE -> UsageSubTab()
            }
        }
    }
}

@Composable
internal fun SmallChip(
    label: String,
    accent: Color,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (selected) accent else PANEL_HI
    val fg = when {
        selected && accent == GREEN -> ON_GREEN
        selected -> ON_NEON
        else -> accent
    }
    Box(
        Modifier
            .clip(chamfer(6.dp))
            .background(bg)
            .border(1.dp, if (selected) accent else EDGE, chamfer(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = fg, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1)
    }
}

@Composable
private fun AgentsSubTab() {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showDebate by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val listState = rememberLazyListState()
    val msgs = RemoteState.messages
    LaunchedEffect(msgs.size, RemoteState.streamingText.length) {
        if (msgs.isNotEmpty() || RemoteState.streamingText.isNotEmpty()) {
            val idx = (msgs.size + (if (RemoteState.streaming) 1 else 0) - 1).coerceAtLeast(0)
            runCatching { listState.animateScrollToItem(idx) }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFromUri(ctx, uri) { feedback = it }
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attachFromUri(ctx, uri) { feedback = it }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) attachFromUri(ctx, uri) { feedback = it }
    }

    if (showFolderPicker) FolderPickerDialog(onDismiss = { showFolderPicker = false })
    if (showSessions) SessionsDialog(onDismiss = { showSessions = false })
    if (showDebate) DebateDialog(onDismiss = { showDebate = false }) { feedback = it }
    if (showConfig) AgentConfigDialog(onDismiss = { showConfig = false }) { feedback = it }

    Column(Modifier.fillMaxSize()) {
        // Controls strip: Workspace, Agents, Mode, Debate, Sessions
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val wsLabel = RemoteState.activeWorkspace.substringAfterLast('\\').substringAfterLast('/').ifBlank { "Folder" }
            SmallChip("📁 $wsLabel", CYAN) { showFolderPicker = true }
            SmallChip(
                "${RemoteState.executionMode.label} · ${RemoteState.selectedAgentIds.size}",
                PINK,
            ) { showConfig = true }
            ModelDropdownPicker(
                label = "9Router Model",
                selectedModel = RemoteState.selectedModel,
                models = RemoteState.models,
                loading = RemoteState.fetchingModels,
                onRefresh = { RemoteState.refreshModels(ctx) },
                onSelect = { RemoteState.selectedModel = it },
                compact = true,
            )
            SmallChip(
                RemoteState.activeSession?.title?.take(14) ?: tr("Sessions"),
                INK,
            ) { showSessions = true }
            SmallChip(
                if (RemoteState.debateRunning) "/debate (ON)" else "/debate",
                if (RemoteState.debateRunning) GREEN else AMBER,
                selected = RemoteState.debateRunning,
            ) { showDebate = true }
            SmallChip("/fable-mind", GREEN) {
                if (!draft.contains("/fable-mind")) {
                    draft = if (draft.isBlank()) "/fable-mind " else "/fable-mind $draft"
                }
            }
        }

        // Agent quick chips
        if (RemoteState.connected) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallChip(tr("Rescan agents"), INK) { RemoteState.refreshAgents(ctx) }
                for (ag in RemoteState.agents.filter { it.detected }) {
                    val sel = ag.id in RemoteState.selectedAgentIds
                    SmallChip(
                        label = if (ag.installed) ag.name else "${ag.name} · ${tr("Unsupported")}",
                        accent = if (ag.installed) GREEN else MUTED,
                        selected = sel,
                    ) {
                        if (!ag.installed) return@SmallChip
                        RemoteState.selectedAgentIds = if (RemoteState.executionMode == RemoteExecutionMode.SINGLE) {
                            setOf(ag.id)
                        } else {
                            val next = RemoteState.selectedAgentIds.toMutableSet()
                            if (ag.id in next && next.size > 1) next.remove(ag.id) else next.add(ag.id)
                            next
                        }
                    }
                }
            }
        }

        // Messages + live stream + tool/approval/clarification cards
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msgs.isEmpty() && !RemoteState.streaming) {
                item {
                    Card(
                        tr("Multi-CLI Agent Studio"),
                        tr("Send coding tasks to Codex, Claude, Gemini, OpenCode, or Hermes on your PC. Use /debate or /fable-mind for multi-seat review & grounded answers."),
                    )
                }
            }
            items(msgs, key = { it.id }) { m ->
                val isUser = m.role == "user"
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 310.dp)
                            .background(if (isUser) PANEL_HI else PANEL, chamfer(10.dp))
                            .border(1.dp, if (isUser) PINK else EDGE, chamfer(10.dp))
                            .padding(11.dp),
                    ) {
                        Text(
                            if (isUser) tr("You") else (m.agentId.ifBlank { "Agent" }),
                            color = if (isUser) PINK else GREEN,
                            fontFamily = MONO, fontWeight = FontWeight.Bold, fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(m.text, color = INK, fontFamily = SANS, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                }
            }
            if (RemoteState.toolActivities.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL, chamfer(9.dp))
                            .border(1.dp, EDGE, chamfer(9.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Label(tr("Tool Activity"), CYAN)
                        for (t in RemoteState.toolActivities.takeLast(6)) {
                            Text(
                                "[${t.status}] ${t.name}: ${t.text.take(90)}",
                                color = if (t.status == "running") AMBER else MUTED,
                                fontFamily = MONO, fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            for (ap in RemoteState.approvals) {
                item(key = "app_${ap.requestId}") {
                    Card(ap.title, ap.detail) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CapriButton(tr("Approve"), Modifier.weight(1f), color = GREEN) {
                                RemoteState.resolveApproval(ctx, ap.requestId, approve = true) { err ->
                                    if (err != null) feedback = err
                                }
                            }
                            CapriButton(tr("Deny"), Modifier.weight(1f), quiet = true) {
                                RemoteState.resolveApproval(ctx, ap.requestId, approve = false) { err ->
                                    if (err != null) feedback = err
                                }
                            }
                        }
                    }
                }
            }
            for (cl in RemoteState.clarifications) {
                item(key = "clar_${cl.requestId}") {
                    Card(tr("Clarification"), cl.question) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (c in cl.choices) {
                                SmallChip(c, CYAN) {
                                    RemoteState.resolveClarification(ctx, cl.requestId, c) { err ->
                                        if (err != null) feedback = err
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (RemoteState.streaming) {
                item {
                    val liveStatus = RemoteState.streamingStatus.ifBlank { tr("Streaming...") }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL, chamfer(10.dp))
                            .border(1.dp, GREEN, chamfer(10.dp))
                            .padding(11.dp),
                    ) {
                        Text(
                            liveStatus,
                            color = GREEN, fontFamily = MONO, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            RemoteState.streamingText.ifBlank { liveStatus },
                            color = if (RemoteState.streamingText.isBlank()) MUTED else INK,
                            fontFamily = SANS, fontSize = 13.5.sp, lineHeight = 19.sp,
                        )
                    }
                }
            }
        }

        if (feedback.isNotEmpty()) {
            Text(
                feedback,
                color = PINK, fontFamily = SANS, fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // Attachment drafts strip
        if (RemoteState.attachments.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (att in RemoteState.attachments) {
                    SmallChip("✕ ${att.name} (${att.bytes.size / 1024}KB)", AMBER) {
                        RemoteState.attachments = RemoteState.attachments.filterNot { it.id == att.id }
                    }
                }
            }
        }

        // Attachment action buttons + Composer
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallChip("+ File", MUTED) { filePicker.launch(arrayOf("*/*")) }
            SmallChip("+ Gallery", MUTED) { galleryPicker.launch("image/*") }
            SmallChip("+ Camera", MUTED) {
                val dir = File(ctx.cacheDir, "remote_uploads").apply { mkdirs() }
                val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                cameraUri = uri
                cameraLauncher.launch(uri)
            }
            SmallChip("+ Clipboard", MUTED) { attachFromClipboard(ctx) { feedback = it } }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(tr("Message PC agents (supports /debate, /fable-mind)"), fontSize = 13.sp) },
                maxLines = 4,
                colors = capriField(),
                shape = chamfer(9.dp),
                modifier = Modifier.weight(1f),
            )
            if (RemoteState.streaming) {
                CapriButton(tr("Stop"), color = PINK) {
                    RemoteState.stopActiveSession(ctx)
                }
            } else {
                CapriButton(tr("Send"), color = GREEN, enabled = draft.isNotBlank()) {
                    val text = draft
                    draft = ""
                    feedback = ""
                    RemoteState.sendPrompt(ctx, text) { feedback = it }
                }
            }
        }
    }
}

@Composable
private fun AgentConfigDialog(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    var gPers by remember { mutableStateOf(RemoteState.globalPersonalization) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("Agent Execution Settings")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Text(tr("Execution Mode"), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
            Segments(
                RemoteExecutionMode.entries.map { it.label },
                RemoteExecutionMode.entries.indexOf(RemoteState.executionMode),
            ) { idx -> RemoteState.executionMode = RemoteExecutionMode.entries[idx] }

            Text(tr("PC Permission Mode"), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
            Segments(
                RemotePermissionMode.entries.map { it.label },
                RemotePermissionMode.entries.indexOf(RemoteState.permissionMode),
            ) { idx ->
                val mode = RemotePermissionMode.entries[idx]
                RemoteState.permissionMode = mode
                Executors.newSingleThreadExecutor().execute {
                    runCatching { RemoteState.client(ctx)?.setPermission(mode) }
                }
            }

            if (RemoteState.executionMode != RemoteExecutionMode.SINGLE) {
                Text(
                    tr("Concurrency: {0} (max {1})", RemoteState.concurrency, RemoteState.maxConcurrentAgents),
                    color = INK, fontFamily = SANS, fontSize = 12.5.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 1..RemoteState.maxConcurrentAgents) {
                        SmallChip("$n", CYAN, selected = RemoteState.concurrency == n) {
                            RemoteState.concurrency = n
                        }
                    }
                }
            }

            ModelDropdownPicker(
                label = tr("Model"),
                selectedModel = RemoteState.selectedModel,
                models = RemoteState.models,
                loading = RemoteState.fetchingModels,
                onRefresh = { RemoteState.refreshModels(ctx) },
                onSelect = { RemoteState.selectedModel = it },
            )

            OutlinedTextField(
                value = gPers,
                onValueChange = { gPers = it },
                label = { Text(tr("Global PC Agent Personalization")) },
                minLines = 2,
                maxLines = 4,
                colors = capriField(),
                shape = chamfer(9.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            CapriButton(tr("Save Personalization"), Modifier.fillMaxWidth(), color = GREEN) {
                RemoteState.globalPersonalization = gPers
                Executors.newSingleThreadExecutor().execute {
                    runCatching { RemoteState.client(ctx)?.setGlobalPersonalization(gPers) }
                }
                onMessage("Personalization saved")
                onDismiss()
            }
        }
    }
}

@Composable
private fun FolderPickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var listing by remember { mutableStateOf<WorkspaceFolderListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    fun load(path: String) {
        loading = true
        error = ""
        Executors.newSingleThreadExecutor().execute {
            val res = runCatching { RemoteState.client(ctx)?.browseFolders(path) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                loading = false
                res.onSuccess { listing = it }.onFailure { error = it.message ?: "Error" }
            }
        }
    }

    LaunchedEffect(Unit) { load("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("PC Workspace Folder")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Text(
                listing?.path?.ifBlank { tr("PC Drives") } ?: tr("Loading..."),
                color = CYAN, fontFamily = MONO, fontSize = 11.5.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val parent = listing?.parent
                if (parent != null) {
                    CapriButton(tr("Up"), Modifier.weight(1f), quiet = true) { load(parent) }
                }
                val curPath = listing?.path.orEmpty()
                if (curPath.isNotBlank()) {
                    CapriButton(tr("Use This Folder"), Modifier.weight(1f), color = GREEN) {
                        RemoteState.switchWorkspace(ctx, curPath) { onDismiss() }
                    }
                }
                CapriButton(tr("PC Dialog"), Modifier.weight(1f), quiet = true, color = AMBER) {
                    Executors.newSingleThreadExecutor().execute {
                        val picked = runCatching { RemoteState.client(ctx)?.pickDesktopFolder() }.getOrNull().orEmpty()
                        if (picked.isNotBlank()) {
                            RemoteState.switchWorkspace(ctx, picked) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post { onDismiss() }
                            }
                        }
                    }
                }
            }
            if (error.isNotEmpty()) Text(error, color = PINK, fontFamily = SANS, fontSize = 12.sp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loading) Text(tr("Loading folders..."), color = MUTED, fontFamily = SANS, fontSize = 12.5.sp)
                for (f in listing?.folders.orEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL_HI, chamfer(8.dp))
                            .border(1.dp, EDGE, chamfer(8.dp))
                            .clickable { load(f.path) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("📁 ${f.name}", color = INK, fontFamily = SANS, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        SmallChip(tr("Select"), GREEN) {
                            RemoteState.switchWorkspace(ctx, f.path) { onDismiss() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var renameId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("PC Agent Sessions")) }
                CapriButton(tr("+ New"), color = GREEN) {
                    RemoteState.createSession(ctx)
                    onDismiss()
                }
            }
            if (renameId.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(tr("Rename session")) },
                        singleLine = true,
                        colors = capriField(),
                        modifier = Modifier.weight(1f),
                    )
                    CapriButton(tr("Save"), color = GREEN) {
                        RemoteState.renameSession(ctx, renameId, renameText)
                        renameId = ""
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(290.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (s in RemoteState.sessions) {
                    val active = s.id == RemoteState.activeSession?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL_HI, chamfer(8.dp))
                            .border(1.dp, if (active) GREEN else EDGE, chamfer(8.dp))
                            .clickable {
                                RemoteState.selectSession(ctx, s.id)
                                onDismiss()
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                            Text("${s.workspaceName} · ${s.modelName}", color = MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1)
                        }
                        SmallChip(tr("Rename"), CYAN) {
                            renameId = s.id
                            renameText = s.title
                        }
                        Spacer(Modifier.width(4.dp))
                        SmallChip("✕", PINK) { RemoteState.deleteSession(ctx, s.id) }
                    }
                }
            }
            CapriButton(tr("Close"), Modifier.fillMaxWidth(), quiet = true, color = MUTED) { onDismiss() }
        }
    }
}

@Composable
private fun DebateDialog(onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    var taskText by remember { mutableStateOf("") }
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
                Box(Modifier.weight(1f)) { Label(tr("Multi-AI /debate Orchestrator")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Pill(
                if (RemoteState.debateRunning) tr("Debate Active (Zero-Avatar Ready)") else tr("Idle"),
                if (RemoteState.debateRunning) GREEN else MUTED,
            )
            OutlinedTextField(
                value = taskText,
                onValueChange = { taskText = it },
                label = { Text(tr("Topic / Code review task for multi-AI debate")) },
                minLines = 3,
                colors = capriField(),
                shape = chamfer(9.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapriButton(
                    tr("Start /debate"),
                    Modifier.weight(1f),
                    color = GREEN,
                    enabled = taskText.isNotBlank(),
                ) {
                    RemoteState.toggleDebate(ctx, task = taskText, stop = false) {
                        onStatus(it)
                        onDismiss()
                    }
                }
                if (RemoteState.debateRunning) {
                    CapriButton(tr("Stop Debate"), Modifier.weight(1f), color = PINK) {
                        RemoteState.toggleDebate(ctx, stop = true) {
                            onStatus(it)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

private fun attachFromUri(ctx: Context, uri: Uri, onFeedback: (String) -> Unit) {
    runCatching {
        var name = "upload_${System.currentTimeMillis()}"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx) ?: name
            }
        }
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Empty file")
        if (bytes.size > 15 * 1024 * 1024) error("File exceeds 15 MB")
        RemoteState.attachments = RemoteState.attachments + RemoteAttachmentDraft(
            id = "${System.nanoTime()}",
            name = name,
            bytes = bytes,
        )
    }.onFailure { onFeedback(it.message ?: "Attachment failed") }
}

private fun attachFromClipboard(ctx: Context, onFeedback: (String) -> Unit) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
    val uri = item?.uri
    if (uri != null) {
        attachFromUri(ctx, uri, onFeedback)
        return
    }
    val txt = item?.coerceToText(ctx)?.toString().orEmpty()
    if (txt.isNotBlank()) {
        val bytes = txt.toByteArray(Charsets.UTF_8)
        RemoteState.attachments = RemoteState.attachments + RemoteAttachmentDraft(
            id = "${System.nanoTime()}",
            name = "clipboard.txt",
            bytes = bytes,
        )
    } else {
        onFeedback(tr("Clipboard has no image or text."))
    }
}

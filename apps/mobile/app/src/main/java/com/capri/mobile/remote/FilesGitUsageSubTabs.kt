package com.capri.mobile.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.capri.mobile.PANEL
import com.capri.mobile.PANEL_HI
import com.capri.mobile.PINK
import com.capri.mobile.Pill
import com.capri.mobile.SANS
import com.capri.mobile.Segments
import com.capri.mobile.Toggle
import com.capri.mobile.capriField
import com.capri.mobile.chamfer
import com.capri.mobile.tr
import java.util.concurrent.Executors

@Composable
fun FilesGitSubTab() {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = Files, 1 = Git
    var gitFilter by remember { mutableStateOf("all") }
    var fileErr by remember { mutableStateOf("") }

    LaunchedEffect(RemoteState.connected) {
        if (RemoteState.connected) RemoteState.refreshFilesAndGit(ctx)
    }

    val openDoc = RemoteState.openFile
    if (openDoc != null) {
        WorkspaceFileDialog(
            doc = openDoc,
            onDismiss = { RemoteState.openFile = null },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Segments(listOf(tr("Workspace Files"), tr("Git Status")), mode) { mode = it }

        if (mode == 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "/${RemoteState.treePath}".replace("//", "/"),
                    color = CYAN, fontFamily = MONO, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (RemoteState.treePath.isNotBlank()) {
                    SmallChip("↑ Up", AMBER) {
                        val parent = RemoteState.treePath.substringBeforeLast('/', "")
                        val cleanParent = if (parent == RemoteState.treePath) "" else parent
                        RemoteState.refreshFilesAndGit(ctx, path = cleanParent)
                    }
                }
                SmallChip(tr("Refresh"), GREEN) {
                    RemoteState.refreshFilesAndGit(ctx, path = RemoteState.treePath)
                }
            }
            if (fileErr.isNotEmpty()) Text(fileErr, color = PINK, fontFamily = SANS, fontSize = 12.sp)
            for (e in RemoteState.treeEntries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(PANEL, chamfer(8.dp))
                        .border(1.dp, EDGE, chamfer(8.dp))
                        .clickable {
                            fileErr = ""
                            if (e.isDirectory) RemoteState.refreshFilesAndGit(ctx, path = e.path)
                            else RemoteState.openWorkspaceFile(ctx, e.path) { fileErr = it }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (e.isDirectory) "📁 ${e.name}" else "📄 ${e.name}",
                        color = INK, fontFamily = if (e.isDirectory) SANS else MONO, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (e.isDirectory) "→" else "View", color = MUTED, fontFamily = MONO, fontSize = 11.sp)
                }
            }
            if (RemoteState.treeEntries.isEmpty()) {
                Card(tr("Empty folder"), tr("No files in this directory."))
            }
        } else {
            val git = RemoteState.gitStatus
            if (git == null) {
                Card(tr("Git Status"), tr("Loading repository info..."))
                CapriButton(tr("Refresh Git"), Modifier.fillMaxWidth(), color = GREEN) {
                    RemoteState.refreshFilesAndGit(ctx, fetchGit = true)
                }
            } else if (!git.isGitRepo) {
                Card(tr("Not a Git repository"), tr("Selected PC folder has no .git directory."))
                if (git.nestedRepos.isNotEmpty()) {
                    Card(tr("Nested Git Repositories")) {
                        for (nr in git.nestedRepos) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(PANEL_HI, chamfer(8.dp))
                                    .border(1.dp, EDGE, chamfer(8.dp))
                                    .clickable { RemoteState.switchWorkspace(ctx, nr.path) }
                                    .padding(10.dp),
                            ) {
                                Text(nr.name, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                SmallChip(tr("Open"), GREEN) { RemoteState.switchWorkspace(ctx, nr.path) }
                            }
                        }
                    }
                }
            } else {
                Card(tr("Repository: ${git.branch.ifBlank { "HEAD" }}"), git.remoteUrl) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("↑ ${git.ahead}", CYAN)
                        Pill("↓ ${git.behind}", AMBER)
                        Pill("+${git.additions} / -${git.deletions}", GREEN)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CapriButton(tr("Refresh"), Modifier.weight(1f), quiet = true) {
                            RemoteState.refreshFilesAndGit(ctx, fetchGit = false)
                        }
                        CapriButton(tr("Git Fetch"), Modifier.weight(1f), color = GREEN) {
                            RemoteState.refreshFilesAndGit(ctx, fetchGit = true)
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in listOf("all", "staged", "modified", "untracked")) {
                        SmallChip(f.uppercase(), PINK, selected = gitFilter == f) { gitFilter = f }
                    }
                }
                val filtered = git.files.filter {
                    when (gitFilter) {
                        "staged" -> it.staged
                        "modified" -> it.status == "modified"
                        "untracked" -> it.status == "untracked"
                        else -> true
                    }
                }
                for (ch in filtered) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL, chamfer(8.dp))
                            .border(1.dp, EDGE, chamfer(8.dp))
                            .clickable { RemoteState.openWorkspaceFile(ctx, ch.path) { fileErr = it } }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ch.path, color = INK, fontFamily = MONO, fontSize = 12.sp)
                            Text(
                                "${ch.status}${if (ch.staged) " · staged" else ""}",
                                color = if (ch.staged) GREEN else AMBER, fontFamily = MONO, fontSize = 10.5.sp,
                            )
                        }
                        SmallChip("Diff", CYAN) { RemoteState.openWorkspaceFile(ctx, ch.path) { fileErr = it } }
                    }
                }
                if (git.outgoing.isNotEmpty()) {
                    Card(tr("Outgoing Commits (${git.outgoing.size})")) {
                        for (c in git.outgoing.take(8)) {
                            Text("${c.hash} · ${c.subject} (${c.author})", color = INK, fontFamily = MONO, fontSize = 11.5.sp)
                        }
                    }
                }
                if (git.incoming.isNotEmpty()) {
                    Card(tr("Incoming Commits (${git.incoming.size})")) {
                        for (c in git.incoming.take(8)) {
                            Text("${c.hash} · ${c.subject} (${c.author})", color = CYAN, fontFamily = MONO, fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun WorkspaceFileDialog(doc: WorkspaceFileDoc, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var viewTab by remember { mutableIntStateOf(if (doc.diff.isNotBlank()) 1 else 0) }
    var editing by remember { mutableStateOf(false) }
    var editedText by remember(doc.hash) { mutableStateOf(doc.content) }
    var status by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.95f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(doc.name, color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text("${doc.path} · ${doc.lineCount} lines", color = MUTED, fontFamily = MONO, fontSize = 10.5.sp, maxLines = 1)
                }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(tr("Code"), PINK, selected = viewTab == 0) { viewTab = 0; editing = false }
                if (doc.diff.isNotBlank()) {
                    SmallChip(tr("Git Diff"), CYAN, selected = viewTab == 1) { viewTab = 1; editing = false }
                }
                if (doc.editable) {
                    SmallChip(if (editing) tr("Cancel Edit") else tr("Edit"), GREEN, selected = editing) {
                        viewTab = 0
                        editing = !editing
                        editedText = doc.content
                    }
                }
            }
            if (status.isNotEmpty()) Text(status, color = AMBER, fontFamily = SANS, fontSize = 12.sp)

            if (editing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    colors = capriField(),
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                )
                CapriButton(tr("Save to PC"), Modifier.fillMaxWidth(), color = GREEN) {
                    RemoteState.saveWorkspaceFile(ctx, doc.path, editedText, doc.hash) { err ->
                        if (err != null) status = err
                        else {
                            status = tr("Saved!")
                            editing = false
                        }
                    }
                }
            } else {
                val text = if (viewTab == 1 && doc.diff.isNotBlank()) doc.diff else doc.content
                val lines = remember(text) { text.lines().take(500) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(BG, chamfer(8.dp))
                        .border(1.dp, EDGE, chamfer(8.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEachIndexed { i, line ->
                        val color = when {
                            viewTab == 1 && line.startsWith("+") -> GREEN
                            viewTab == 1 && line.startsWith("-") -> PINK
                            viewTab == 1 && line.startsWith("@@") -> CYAN
                            else -> INK
                        }
                        Text("${"%4d".format(i + 1)}  $line", color = color, fontFamily = MONO, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TasksSubTab() {
    val ctx = LocalContext.current
    val prefs = remember { RemotePrefs(ctx) }
    var monitorOn by remember { mutableStateOf(prefs.backgroundMonitor) }
    var filter by remember { mutableStateOf("all") }

    LaunchedEffect(RemoteState.connected) {
        if (RemoteState.connected) RemoteState.refreshTasks(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(tr("Android Task Monitor")) {
            Toggle(
                tr("Background task monitor"),
                tr("Keep an Android foreground notification showing active PC tasks with Open & Stop buttons."),
                monitorOn,
            ) { enabled ->
                monitorOn = enabled
                prefs.backgroundMonitor = enabled
                RemoteMonitorService.sync(ctx)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in listOf("all", "running", "completed", "failed")) {
                    SmallChip(f.uppercase(), PINK, selected = filter == f) { filter = f }
                }
            }
            SmallChip(tr("Refresh"), GREEN) { RemoteState.refreshTasks(ctx) }
        }

        val items = RemoteState.tasks.filter { filter == "all" || it.status == filter }
        if (items.isEmpty()) {
            Card(tr("No PC tasks"), tr("Tasks launched from Capri Mobile or Codex CLI appear here."))
        }
        for (t in items) {
            val accent = when (t.status) {
                "running", "queued" -> AMBER
                "completed" -> GREEN
                "failed" -> PINK
                else -> MUTED
            }
            Card(t.title) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(t.status.uppercase(), accent)
                    if (t.phase.isNotBlank()) Pill(t.phase, CYAN)
                    Spacer(Modifier.weight(1f))
                    if (t.status == "running" && t.sessionId.isNotBlank()) {
                        SmallChip(tr("Stop"), PINK) {
                            Executors.newSingleThreadExecutor().execute {
                                runCatching { RemoteState.client(ctx)?.stopSession(t.sessionId) }
                                RemoteState.refreshTasks(ctx)
                            }
                        }
                    }
                }
                if (t.detail.isNotBlank()) Text(t.detail, color = MUTED, fontFamily = SANS, fontSize = 12.5.sp)
                for (st in t.agentStates) {
                    Text(
                        "• ${st.name} [${st.status}/${st.phase}] ${st.elapsedSeconds}s ${st.detail.take(70)}",
                        color = INK, fontFamily = MONO, fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun UsageSubTab() {
    val ctx = LocalContext.current
    var threshold by remember(RemoteState.routerConfig.complexityThreshold) {
        mutableFloatStateOf(RemoteState.routerConfig.complexityThreshold.toFloat())
    }
    var simpleModel by remember(RemoteState.routerConfig.simpleModel) {
        mutableStateOf(RemoteState.routerConfig.simpleModel)
    }
    var complexModel by remember(RemoteState.routerConfig.complexModel) {
        mutableStateOf(RemoteState.routerConfig.complexModel)
    }
    var routerMode by remember(RemoteState.routerConfig.mode) {
        mutableStateOf(RemoteState.routerConfig.mode)
    }
    var providerFilter by remember { mutableStateOf("ALL") }
    var saveNote by remember { mutableStateOf("") }

    LaunchedEffect(RemoteState.connected) {
        if (RemoteState.connected) RemoteState.refreshUsageAndRouter(ctx)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in listOf("24h", "7d", "30d")) {
                SmallChip(r, PINK, selected = RemoteState.usageRange == r) {
                    RemoteState.refreshUsageAndRouter(ctx, r)
                }
            }
            Spacer(Modifier.weight(1f))
            SmallChip(tr("Refresh"), GREEN) { RemoteState.refreshUsageAndRouter(ctx) }
        }

        val u = RemoteState.usageData
        if (u == null || !u.available) {
            Card(
                tr("Provider Usage (9Router)"),
                u?.reason?.ifBlank { tr("9Router usage data unavailable on PC.") } ?: tr("Loading usage..."),
            )
        } else {
            Card(tr("Usage Summary (${u.range})")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Requests", "${u.requests}", Modifier.weight(1f))
                    UsageMetric("Cost", "$${"%.2f".format(u.estimatedCost)}", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UsageMetric("Input", compactTokens(u.inputTokens), Modifier.weight(1f))
                    UsageMetric("Output", compactTokens(u.outputTokens), Modifier.weight(1f))
                    UsageMetric("Cached", compactTokens(u.cachedTokens), Modifier.weight(1f))
                }
            }

            if (u.quotaAccounts.isNotEmpty()) {
                val accountProviders = remember(u.quotaAccounts) {
                    listOf("ALL") + u.quotaAccounts.map { it.provider.lowercase() }.filter { it.isNotBlank() }.distinct().sorted()
                }
                val visibleAccounts = remember(u.quotaAccounts, providerFilter) {
                    if (providerFilter == "ALL") u.quotaAccounts
                    else u.quotaAccounts.filter { it.provider.equals(providerFilter, ignoreCase = true) }
                }
                Card(tr("Multi-Account Quotas (${visibleAccounts.size}/${u.quotaAccounts.size})")) {
                    if (accountProviders.size > 2) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (p in accountProviders) {
                                SmallChip(
                                    if (p == "ALL") "ALL (${u.quotaAccounts.size})" else p.uppercase(),
                                    CYAN,
                                    selected = providerFilter == p,
                                ) { providerFilter = p }
                            }
                        }
                    }
                    for (acc in visibleAccounts) {
                        val badgeColor = when (acc.provider.lowercase()) {
                            "antigravity" -> CYAN
                            "codex" -> Color(0xFFA855F7)
                            "claude" -> Color(0xFFF97316)
                            else -> GREEN
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(PANEL_HI, chamfer(9.dp))
                                .border(1.dp, if (acc.limitReached) PINK else if (acc.active) GREEN else EDGE, chamfer(9.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Pill(acc.provider.uppercase(), badgeColor)
                                Text(acc.name.ifBlank { acc.id }, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                if (acc.limitReached) Pill("LIMIT", PINK)
                                else if (acc.active) Pill("ACTIVE", GREEN)
                                else if (acc.status.isNotBlank()) Pill(acc.status.uppercase(), AMBER)
                            }
                            if (acc.plan.isNotBlank() || acc.model.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (acc.plan.isNotBlank()) Pill(acc.plan.uppercase(), CYAN)
                                    if (acc.model.isNotBlank()) {
                                        Text(acc.model, color = MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                            val windows = acc.quotas.ifEmpty {
                                listOf(QuotaWindow("default", "Quota", if (acc.limitReached) 100.0 else 0.0, if (acc.limitReached) 0.0 else 100.0, ""))
                            }
                            for (q in windows) {
                                val usedPct = q.effectiveUsedPercent(acc.limitReached)
                                val remPct = q.effectiveRemainingPercent(acc.limitReached)
                                val barColor = when {
                                    remPct <= 20.0 -> PINK
                                    remPct <= 50.0 -> AMBER
                                    else -> GREEN
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(q.label, color = MUTED, fontFamily = SANS, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        "Sisa ${"%.0f".format(remPct)}% · Used ${"%.0f".format(usedPct)}%",
                                        color = barColor,
                                        fontFamily = MONO,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (remPct / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = barColor,
                                    trackColor = EDGE,
                                )
                                if (q.resetAt.isNotBlank()) {
                                    Text("Reset: ${q.resetAt}", color = MUTED, fontFamily = MONO, fontSize = 10.sp)
                                }
                            }
                            if (acc.error.isNotBlank()) {
                                Text(acc.error, color = PINK, fontFamily = SANS, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (u.recent.isNotEmpty()) {
                Card(tr("Recent Token Log (${u.recent.size})")) {
                    for (entry in u.recent.take(15)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(PANEL_HI, chamfer(7.dp))
                                .border(1.dp, EDGE, chamfer(7.dp))
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    entry.model.ifBlank { entry.provider },
                                    color = INK,
                                    fontFamily = MONO,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    listOfNotNull(
                                        entry.provider.takeIf { it.isNotBlank() },
                                        entry.account.takeIf { it.isNotBlank() },
                                        entry.timestamp.takeIf { it.isNotBlank() }?.take(19),
                                    ).joinToString(" · "),
                                    color = MUTED,
                                    fontFamily = SANS,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${compactTokens(entry.totalTokens)} tok", color = CYAN, fontFamily = MONO, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                                if (entry.cost > 0.0) {
                                    Text("$${"%.4f".format(entry.cost)}", color = GREEN, fontFamily = MONO, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Smart Router Card
        val pickerModels = remember(RemoteState.routerModels, RemoteState.models) {
            (RemoteState.routerModels + RemoteState.models).distinct()
        }
        Card(
            tr("Smart Router (:20130)"),
            if (RemoteState.routerRunning) tr("Running on PC · Port ${RemoteState.routerConfig.listenPort}")
            else tr("Smart Router not detected on PC port 20130."),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf("auto", "simple", "complex")) {
                    SmallChip(m.uppercase(), CYAN, selected = routerMode == m) { routerMode = m }
                }
            }
            ModelDropdownPicker(
                label = tr("Simple Model"),
                selectedModel = simpleModel,
                models = pickerModels,
                loading = RemoteState.fetchingModels,
                onRefresh = { RemoteState.refreshModels(ctx) },
                onSelect = { simpleModel = it },
            )
            ModelDropdownPicker(
                label = tr("Complex Model"),
                selectedModel = complexModel,
                models = pickerModels,
                loading = RemoteState.fetchingModels,
                onRefresh = { RemoteState.refreshModels(ctx) },
                onSelect = { complexModel = it },
            )
            Text(tr("Complexity threshold: ${threshold.toInt()}"), color = INK, fontFamily = SANS, fontSize = 12.5.sp)
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                valueRange = 10f..90f,
                colors = SliderDefaults.colors(thumbColor = PINK, activeTrackColor = PINK, inactiveTrackColor = EDGE),
            )
            if (saveNote.isNotEmpty()) Text(saveNote, color = GREEN, fontFamily = SANS, fontSize = 12.sp)
            CapriButton(tr("Save Smart Router"), Modifier.fillMaxWidth(), color = GREEN) {
                val cfg = RemoteState.routerConfig.copy(
                    mode = routerMode,
                    simpleModel = simpleModel.trim(),
                    complexModel = complexModel.trim(),
                    complexityThreshold = threshold.toInt(),
                )
                RemoteState.routerConfig = cfg
                Executors.newSingleThreadExecutor().execute {
                    val res = runCatching { RemoteState.client(ctx)?.saveRouterConfig(cfg) }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        saveNote = if (res.isSuccess) tr("Router config saved on PC") else (res.exceptionOrNull()?.message ?: "Save failed")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun UsageMetric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .background(PANEL_HI, chamfer(8.dp))
            .border(1.dp, EDGE, chamfer(8.dp))
            .padding(10.dp),
    ) {
        Text(label, color = MUTED, fontFamily = SANS, fontSize = 11.sp)
        Text(value, color = INK, fontFamily = MONO, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000_000L -> "%.2fB".format(n / 1e9)
    n >= 1_000_000L -> "%.1fM".format(n / 1e6)
    n >= 1_000L -> "%.1fK".format(n / 1e3)
    else -> "$n"
}

package com.capri.mobile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.capri.mobile.remote.ModelDropdownPicker
import com.capri.mobile.remote.RemoteState
import java.io.File
import java.net.URI
import kotlin.concurrent.thread

/** Her brain: one on the phone (pick, download, use), or LM Studio on your PC. Then what she remembers. */
@Composable
fun BrainTab(go: (Tab) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var page by remember { mutableIntStateOf(if (prefs.brain == ModelStore.LM.id) 1 else if (prefs.brain == ModelStore.NV.id) 2 else 0) }
    var status by remember { mutableStateOf(CapriService.brainStatus) }
    LaunchedEffect(Unit) {
        while (true) {
            status = CapriService.brainStatus
            delay(500)
        }
    }
    Segments(listOf(tr("This phone"), "LM Studio / 9Router", "NVIDIA"), page) { page = it }
    if (CapriService.running) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
        Box(Modifier.size(7.dp).background(if (CapriService.brainOnline) GREEN else PINK, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(status, color = MUTED, fontFamily = SANS, fontSize = 12.5.sp)
    }
    when (page) {
        0 -> {
            KeepSafe()
            PhoneBrains(prefs)
            EarsCard()
        }
        1 -> LmStudioCard(prefs, go)
        else -> NvidiaCard(prefs)
    }
    MemoryCard(prefs)
}

/** Brains in a folder that survives deleting the app; a new install finds them there. */
@Composable
private fun KeepSafe() {
    val ctx = LocalContext.current
    var safe by remember { mutableStateOf(ModelStore.canShare(ctx)) }
    var moving by remember { mutableStateOf("") }
    val old = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { safe = ModelStore.canShare(ctx) }
    LaunchedEffect(Unit) {
        var was = safe
        while (true) {
            delay(700)
            safe = ModelStore.canShare(ctx)
            if (safe && !was) thread(name = "capri-move") {  // just allowed: bring her brains over, pick up any left from before
                ModelStore.moveToShared(ctx) { moving = it }
                CapriService.brainChanged(ctx)
            }
            was = safe
        }
    }
    if (safe) Card(tr("Brains are safe")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(tr("Kept"), GREEN)
            Spacer(Modifier.width(10.dp))
            Text(tr("In {0} on your phone. They stay if you delete the app, and a new install finds them.", ModelStore.sharedPath),
                color = MUTED, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        if (moving.isNotEmpty()) Text(moving, color = INK, fontFamily = SANS, fontSize = 12.sp)
    } else Card(tr("Keep brains safe"), tr("Deleting the app deletes her brains. Keep them in a Capri folder on your phone: they stay when you delete the app, and a new install picks them up with no downloading. Had Capri before? Tap this and she finds them.")) {
        CapriButton(tr("Keep my brains safe"), Modifier.fillMaxWidth(), color = GREEN) {
            if (Build.VERSION.SDK_INT >= 30) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            } else old.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

@Composable
private fun PhoneBrains(prefs: Prefs) {
    val ctx = LocalContext.current
    val dl by DownloadService.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }  // re-reads files every second
    var importing by remember { mutableStateOf("") }
    val ram = remember { ModelStore.ramGb(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) thread(name = "capri-import") { importGguf(ctx, uri) { importing = it } }
    }
    val best = remember { ModelStore.recommended(ram) }
    Card(tr("Pick a brain"), if (best != null)
        tr("She downloads it and runs it offline, on your phone. This phone has about {0} GB of RAM, so {1} is the best fit.", ram, best.label)
        else tr("She downloads it and runs it offline, on your phone. This phone has about {0} GB of RAM: too little for a brain on the phone. Use NVIDIA's free cloud brain instead (NVIDIA tab).", ram)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val active = prefs.brain
        for (m in ModelStore.all(ctx)) {
            val have = m.url.isEmpty() || ModelStore.installed(ctx, m)
            val partial = ModelStore.part(ctx, m).let { if (it.exists()) it.length() else 0L }
            val busy = dl.id == m.id && dl.error.isEmpty()
            val inUse = have && active == m.id
            Column(Modifier.fillMaxWidth().background(PANEL_HI, chamfer(10.dp)).border(1.dp, if (inUse) GREEN else EDGE, chamfer(10.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, color = INK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("%.1f GB".format(m.bytes / 1e9), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
                }
                if (best?.id == m.id) Pill(tr("Recommended for this phone"), GREEN)
                Text(tr(m.note), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
                if (m.minRamGb > 0) Text(tr("RAM: needs {0} GB, best with {1} GB or more", m.minRamGb, m.goodRamGb) +
                    (if (m.minRamGb <= ram && ram < m.goodRamGb) "  ·  " + tr("runs here, but may be slow") else ""),
                    color = if (m.minRamGb > ram) PINK else if (ram < m.goodRamGb) AMBER else MUTED,
                    fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                if (busy || (dl.id == m.id && dl.total > 0)) {
                    LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f },
                        modifier = Modifier.fillMaxWidth(), color = PINK, trackColor = EDGE)
                    Text(if (busy) "%.2f / %.2f GB  ·  %.1f MB/s".format(dl.done / 1e9, dl.total / 1e9, dl.speed / 1e6)
                        else tr("stopped: {0}", dl.error), color = MUTED, fontFamily = SANS, fontSize = 11.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        inUse && m.minRamGb <= ram -> Pill(tr("In use"), GREEN)
                        m.minRamGb > ram -> Pill(tr("Too big for this phone"), PINK)
                        have -> CapriButton(tr("Use"), color = GREEN) { prefs.brain = m.id; CapriService.brainChanged(ctx); tick++ }
                        busy -> CapriButton(tr("Pause"), quiet = true) { DownloadService.cancel(ctx) }
                        else -> CapriButton(if (partial > 0) tr("Resume") else tr("Download")) { DownloadService.start(ctx, m) }
                    }
                    if (have || partial > 0) CapriButton(tr("Delete"), quiet = true, color = MUTED) {
                        if (busy) DownloadService.cancel(ctx)
                        ModelStore.file(ctx, m).delete(); ModelStore.part(ctx, m).delete()
                        if (prefs.brain == m.id) { prefs.brain = ""; CapriService.brainChanged(ctx) }
                        tick++
                    }
                }
                if (m.hasEyes) {  // her eyes for this brain: optional, lets her look at your screen
                    val eyesOn = ModelStore.eyesInstalled(ctx, m)
                    val eyesPart = File(ModelStore.eyes(ctx, m).path + ".part").let { if (it.exists()) it.length() else 0L }
                    val eyesBusy = dl.id == m.id + ":eyes" && dl.error.isEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, EDGE, chamfer(8.dp)).padding(9.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("+ Eyes  ·  {0} MB", "%.0f".format(m.eyesBytes / 1e6)), color = INK, fontFamily = SANS, fontSize = 13.sp)
                            Text(if (eyesOn) tr("she can look at your screen") else if (eyesBusy) "%.0f / %.0f MB".format(
                                dl.done / 1e6, dl.total / 1e6) else tr("lets her look at your screen (Android 11+)"),
                                color = MUTED, fontFamily = SANS, fontSize = 11.sp)
                        }
                        when {
                            eyesOn -> CapriButton(tr("Remove"), quiet = true, color = MUTED) {
                                ModelStore.eyes(ctx, m).delete(); CapriService.brainChanged(ctx); tick++
                            }
                            eyesBusy -> CapriButton(tr("Pause"), quiet = true) { DownloadService.cancel(ctx) }
                            else -> CapriButton(if (eyesPart > 0) tr("Resume") else tr("Get"), color = GREEN) {
                                DownloadService.start(ctx, m, eyes = true)
                            }
                        }
                    }
                }
            }
        }
        CapriButton(tr("Add your own .gguf"), Modifier.fillMaxWidth(), quiet = true) { pick.launch(arrayOf("application/octet-stream", "*/*")) }
        if (importing.isNotEmpty()) Text(importing, color = MUTED, fontFamily = SANS, fontSize = 12.sp)
    }
}

/** LM Studio or 9Router on your PC: over Wi-Fi or Tailscale. */
@Composable
private fun LmStudioCard(prefs: Prefs, go: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val phoneTailscaleIp = remember { runCatching { LmStudio.detectTailscaleVpnIp() }.getOrNull().orEmpty() }
    val knownUrls = remember(RemoteState.activeProfile, RemoteState.profiles, RemoteState.tailscaleIp) {
        buildList {
            if (RemoteState.tailscaleIp.isNotBlank()) add("http://${RemoteState.tailscaleIp}:20128")
            RemoteState.activeProfile?.let { prof ->
                if (prof.tailscaleEndpoint.isNotBlank()) add(prof.tailscaleEndpoint)
                if (prof.localEndpoint.isNotBlank()) add(prof.localEndpoint)
            }
            for (p in RemoteState.profiles) {
                if (p.tailscaleEndpoint.isNotBlank()) add(p.tailscaleEndpoint)
                if (p.localEndpoint.isNotBlank()) add(p.localEndpoint)
            }
        }.distinct()
    }
    val initialAutoUrl = remember(prefs.lmUrl, knownUrls, phoneTailscaleIp) {
        if (prefs.lmUrl.isNotBlank()) prefs.lmUrl
        else {
            val tsHost = RemoteState.tailscaleIp.takeIf { it.isNotBlank() }
                ?: RemoteState.activeProfile?.tailscaleEndpoint?.let { runCatching { URI(it).host }.getOrNull() }
                ?: RemoteState.profiles.firstNotNullOfOrNull { p ->
                    p.tailscaleEndpoint.takeIf { it.isNotBlank() }?.let { runCatching { URI(it).host }.getOrNull() }
                }
            if (!tsHost.isNullOrBlank()) "http://$tsHost:20128" else ""
        }
    }

    var url by remember { mutableStateOf(initialAutoUrl) }
    var apiKey by remember { mutableStateOf(prefs.lmKey) }
    var models by remember { mutableStateOf<List<LmStudio.Info>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var needsKeyPrompt by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(prefs.lmModel) }
    var using by remember { mutableStateOf(prefs.brain == ModelStore.LM.id) }

    fun applyProbeResult(res: LmStudio.ProbeResult) {
        working = false
        if (res.models.isNotEmpty()) {
            url = res.url
            prefs.lmUrl = res.url
            models = res.models
            needsKeyPrompt = false
            note = tr("Connected to {0} ({1} models)", res.url, res.models.size)
            if (model.isBlank()) {
                model = res.models.first().id
                prefs.lmModel = model
            }
        } else if (res.needsKey) {
            url = res.url
            prefs.lmUrl = res.url
            models = emptyList()
            needsKeyPrompt = true
            note = tr("9Router active at {0} — enter your API Key below to auto-fetch models.", res.url)
        } else {
            models = emptyList()
            needsKeyPrompt = false
            note = tr("Nothing answered at {0}. Check Tailscale/Wi-Fi or API Key (9Router :20128).", res.url)
        }
    }

    fun connect(address: String, keyOverride: String = apiKey) {
        val cleanKey = keyOverride.trim()
        prefs.lmKey = cleanKey
        working = true
        note = tr("Connecting...")
        thread(name = "capri-lm") {
            val res = runCatching { LmStudio(address, cleanKey).probe() }
                .getOrElse { LmStudio.ProbeResult(LmStudio.normalize(address), emptyList(), false, it.message ?: "") }
            applyProbeResult(res)
        }
    }

    LaunchedEffect(Unit) {
        RemoteState.ensureInit(ctx)
        if (url.isNotBlank()) {
            connect(url)
        } else {
            working = true
            note = if (phoneTailscaleIp.isNotBlank()) tr("Tailscale VPN active ({0}) — discovering 9Router / LM Studio...", phoneTailscaleIp)
            else tr("Auto-discovering 9Router / LM Studio...")
            thread(name = "capri-lm-auto") {
                val res = runCatching { LmStudio.discoverServer(apiKey.trim(), knownUrls) }.getOrNull()
                if (res != null) {
                    applyProbeResult(res)
                } else {
                    working = false
                    note = if (phoneTailscaleIp.isNotBlank())
                        tr("Tailscale VPN active ({0}). Pair PC in Remote tab once or enter PC Tailscale IP:20128 + API Key.", phoneTailscaleIp)
                    else ""
                }
            }
        }
    }

    // Auto-connect as soon as user pastes/enters a valid 9Router API key when address is already auto-filled
    LaunchedEffect(apiKey, url) {
        val k = apiKey.trim()
        if (url.isNotBlank() && k.length >= 12 && (needsKeyPrompt || models.isEmpty()) && !working) {
            delay(450)
            connect(url, k)
        }
    }

    Card(tr("LM Studio / 9Router (Tailscale & Wi-Fi)"), tr("Connect to LM Studio (:1234), 9Router (:20128), or Smart Router (:20130) over Tailscale or local Wi-Fi.")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (phoneTailscaleIp.isNotBlank()) {
                Pill("Tailscale VPN: $phoneTailscaleIp", GREEN)
            } else {
                Pill("Tailscale VPN: OFF", MUTED)
            }
        }
        OutlinedTextField(
            url,
            { url = it },
            label = { Text(tr("PC Address (auto-filled when Tailscale PC is known)")) },
            singleLine = true,
            colors = capriField(),
            shape = chamfer(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            apiKey,
            { apiKey = it; prefs.lmKey = it.trim() },
            label = { Text(tr("9Router / Bearer API Key (just paste key to connect)")) },
            singleLine = true,
            colors = capriField(),
            shape = chamfer(9.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        val pairedProfile = RemoteState.activeProfile
        if (pairedProfile != null) {
            val host = runCatching { URI(pairedProfile.activeEndpoint()).host }.getOrNull().orEmpty()
            if (host.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapriButton(tr("9Router (:20128)"), Modifier.weight(1f), color = CYAN, enabled = !working) {
                        val routerUrl = "http://$host:20128"
                        url = routerUrl
                        connect(routerUrl)
                    }
                    CapriButton(tr("Smart Router (:20130)"), Modifier.weight(1f), color = PINK, enabled = !working) {
                        val routerUrl = "http://$host:20130"
                        url = routerUrl
                        connect(routerUrl)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton(tr("Auto Connect"), Modifier.weight(1f), quiet = true, enabled = !working) {
                working = true
                note = tr("Searching Tailscale & Wi-Fi...")
                thread(name = "capri-lm-find") {
                    val res = runCatching { LmStudio.discoverServer(apiKey.trim(), knownUrls) }.getOrNull()
                    if (res == null) {
                        working = false
                        note = tr("Didn't find LM Studio or 9Router. Type IP:port (e.g. 100.x.x.x:20128) + API key if needed.")
                    } else applyProbeResult(res)
                }
            }
            CapriButton(tr("Connect"), Modifier.weight(1f), enabled = !working && url.isNotBlank()) { connect(url) }
        }
        if (note.isNotEmpty()) Text(note, color = if (models.isEmpty()) MUTED else GREEN, fontFamily = SANS, fontSize = 12.sp, lineHeight = 17.sp)

        ModelDropdownPicker(
            label = tr("Model (Auto-fetched)"),
            selectedModel = model,
            models = models.map { it.id },
            loading = working,
            onRefresh = {
                if (url.isNotBlank()) connect(url)
                else {
                    working = true
                    thread(name = "capri-lm-ref") {
                        val hit = runCatching { LmStudio.find(apiKey.trim(), knownUrls) }.getOrNull()
                        if (hit != null) connect(hit) else { working = false }
                    }
                }
            },
            onSelect = { pickedId ->
                model = pickedId
                prefs.lmModel = pickedId
                if (using) CapriService.brainChanged(ctx)
            },
        )

        if (models.isNotEmpty()) {
            if (using) Pill(tr("In use"), GREEN)
            else CapriButton(tr("Use LM Studio / 9Router"), Modifier.fillMaxWidth(), color = GREEN) {
                prefs.brain = ModelStore.LM.id
                using = true
                CapriService.brainChanged(ctx)
            }
        }

        val u = RemoteState.usageData
        if (RemoteState.connected && u != null && u.available) {
            Text(
                tr("9Router Monitor: {0} accounts · {1} req · ${"%.2f".format(u.estimatedCost)}", u.quotaAccounts.size, u.requests),
                color = CYAN,
                fontFamily = SANS,
                fontSize = 12.sp,
            )
        } else if (!RemoteState.connected) {
            Text(
                tr("Untuk monitor sisa token & kuota semua akun 9Router, pairing PC (:9120) di tab Remote → Usage."),
                color = MUTED,
                fontFamily = SANS,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            )
        }
        CapriButton(tr("Monitor Token & Kuota Akun 9Router"), Modifier.fillMaxWidth(), color = CYAN, quiet = !RemoteState.connected) {
            RemoteState.requestedSubTab = "usage"
            go(Tab.REMOTE)
        }
    }
}

/** NVIDIA's cloud: a huge model for free, with a key you make yourself on their site. As few steps as possible. */
@Composable
private fun NvidiaCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var key by remember { mutableStateOf(prefs.nvKey) }
    var note by remember { mutableStateOf("") }
    var good by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf(prefs.brain == ModelStore.NV.id) }

    fun tidy(k: String) = k.trim().removePrefix("Bearer ").trim().trim('"', '\'', '`').replace(Regex("\\s"), "")

    fun check(k: String) {
        val clean = tidy(k)
        key = clean
        prefs.nvKey = clean
        good = false
        if (clean.isEmpty()) { note = ""; return }
        if (!clean.startsWith("nvapi-")) { note = tr("That isn't an NVIDIA key. A key starts with nvapi- (copy it with the Copy button on NVIDIA's page)."); return }
        working = true
        note = tr("Checking your key...")
        thread(name = "capri-nv") {
            val (ok, msg) = LmStudio.checkNvidia(clean)
            note = msg
            good = ok
            working = false
            if (ok) {  // it works: switch her over right away
                prefs.brain = ModelStore.NV.id
                using = true
                CapriService.brainChanged(ctx)
            }
        }
    }

    Card(tr("NVIDIA cloud brain"), tr("A huge model in NVIDIA's cloud, free with your own key. Needs internet. What you say to her goes to NVIDIA, and so does your screen when you ask her to look. She sees through NVIDIA's vision model on the same key.")) {
        val steps = listOf(
            tr("Tap Open NVIDIA. Sign in, or make a free account (NVIDIA may ask you to verify your email or phone number)."),
            tr("On that page tap Get API Key, then Generate Key."),
            tr("Tap Copy. The key starts with nvapi-"),
            tr("Come back here and tap Paste key. She checks it and switches to NVIDIA by herself."),
        )
        steps.forEachIndexed { i, s ->
            Row {
                Text("${i + 1}", color = PINK, fontFamily = SANS, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, modifier = Modifier.width(18.dp))
                Text(s, color = MUTED, fontFamily = SANS, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton(tr("Open NVIDIA"), Modifier.weight(1f), quiet = true) { uri.openUri(ModelStore.NV_KEY_PAGE) }
            CapriButton(tr("Paste key"), Modifier.weight(1f), color = GREEN, enabled = !working) {
                val clip = ctx.getSystemService(android.content.ClipboardManager::class.java)
                val text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                if (text.isBlank()) note = tr("Nothing copied yet. On NVIDIA's page tap Copy next to your key, then come back.")
                else check(text)
            }
        }
        OutlinedTextField(key, { key = it; prefs.nvKey = tidy(it); note = ""; good = false }, label = { Text(tr("or type it here: nvapi-...")) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors = capriField(), shape = chamfer(9.dp), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CapriButton(if (working) tr("Checking...") else tr("Check key"), Modifier.weight(1f), quiet = true, enabled = !working && key.isNotBlank()) { check(key) }
            if (using) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Pill(tr("In use"), GREEN) }
            else CapriButton(tr("Use it"), Modifier.weight(1f), color = GREEN, enabled = key.isNotBlank() && !working) {
                prefs.brain = ModelStore.NV.id
                using = true
                CapriService.brainChanged(ctx)
            }
        }
        if (note.isNotEmpty()) Text(note, color = if (good) INK else PINK, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 17.sp)
        Text(tr("Still not working? Copy the whole key again, check you're online, and make sure your NVIDIA account is verified. If NVIDIA's biggest model is busy she switches to a smaller NVIDIA model by herself."),
            color = MUTED, fontFamily = SANS, fontSize = 12.sp, lineHeight = 16.sp)
        var glance by remember { mutableStateOf(prefs.cloudGlance) }
        Toggle(tr("Let her look on her own"), tr("with this on, React on her own works in every app here too: your screen, chats and DMs included, goes to NVIDIA every minute or two. Off: only public feeds like TikTok, and only their text."), glance) {
            glance = it
            prefs.cloudGlance = it
        }
    }
}

/** Her own ears, the same as the desktop's. Without them she uses the phone's speech recognizer. */
@Composable
private fun EarsCard() {
    val ctx = LocalContext.current
    val dl by DownloadService.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val m = ModelStore.ASR
    @Suppress("UNUSED_EXPRESSION") tick
    val ready = ModelStore.earsReady(ctx)
    val total = m.bytes + m.eyesBytes
    val busy = (dl.id == m.id || dl.id == m.id + ":eyes") && dl.error.isEmpty()
    Card(tr("Her ears"), tr("Qwen3-ASR: hears names, slang and accents in 52 languages, right on your phone. Without it she uses your phone's speech recognizer.")) {
        if (busy) {
            LinearProgressIndicator(progress = { if (dl.total > 0) dl.done.toFloat() / dl.total else 0f }, modifier = Modifier.fillMaxWidth(), color = PINK, trackColor = EDGE)
            Text("%.0f / %.0f MB".format(dl.done / 1e6, dl.total / 1e6), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                ready -> Pill(tr("Ready"), GREEN)
                busy -> CapriButton(tr("Pause"), quiet = true) { DownloadService.cancel(ctx) }
                else -> CapriButton(tr("Download  ·  {0} GB", "%.1f".format(total / 1e9))) {
                    DownloadService.start(ctx, m, eyes = ModelStore.installed(ctx, m))
                }
            }
            if (ready) CapriButton(tr("Delete"), quiet = true, color = MUTED) {
                ModelStore.file(ctx, m).delete(); ModelStore.eyes(ctx, m).delete(); tick++
            }
        }
    }
}

/** Her long-term memory: everything she wrote down, yours to read and delete. */
@Composable
private fun MemoryCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val memory = remember { Memory.get(ctx) }
    var remembers by remember { mutableStateOf(prefs.remember) }
    var sure by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val facts = remember(tick) { memory.all() }
    Card(tr("Her memory"), tr("What she remembers about you and the people in your life. Memories: {0}, only on this phone.", facts.size)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Remember things"), color = INK, fontFamily = SANS, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(remembers, { remembers = it; prefs.remember = it }, colors = capriSwitch())
        }
        if (facts.isEmpty()) Text(tr("Nothing yet. Tell her about your day, or hold her and say \"look at this\"."),
            color = MUTED, fontFamily = SANS, fontSize = 12.sp)
        for (f in facts.take(60)) Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(PANEL_HI, chamfer(7.dp)).padding(start = 10.dp)) {
            Text(f.text, color = INK, fontFamily = SANS, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            Text("X", color = PINK, fontFamily = MONO, fontSize = 15.sp, modifier = Modifier.clickable { memory.forget(f.text); tick++ }.padding(12.dp))
        }
        if (facts.isNotEmpty()) CapriButton(if (sure) tr("Tap again: wipe it all") else tr("Forget everything"), quiet = !sure) {
            if (sure) { memory.forgetAll(); sure = false; tick++ } else sure = true
        }
    }
}

/** Copies a picked .gguf into her models folder (the file has to be on the phone for llama.cpp to map it). */
private fun importGguf(ctx: android.content.Context, uri: Uri, progress: (String) -> Unit) {
    val cr = ctx.contentResolver
    var name = "model.gguf"
    var size = -1L
    cr.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
        }
    }
    if (!name.endsWith(".gguf", true)) { progress(tr("That's not a .gguf file.")); return }
    if ("mmproj" in name.lowercase()) { progress(tr("That's a vision add-on (mmproj), not a brain.")); return }
    val out = File(ModelStore.dir(ctx), name)
    val tmp = File(out.path + ".part")
    try {
        cr.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (done - last > 50_000_000) { last = done; progress(tr("Copying {0}: {1} GB", name, "%.2f".format(done / 1e9) +
                        if (size > 0) " / %.2f".format(size / 1e9) else "")) }
                }
            }
        }
        tmp.renameTo(out)
        progress(tr("Added {0}. Tap Use on it.", name))
    } catch (e: Exception) {
        tmp.delete()
        progress(tr("Couldn't copy it: {0}", e.message))
    }
}

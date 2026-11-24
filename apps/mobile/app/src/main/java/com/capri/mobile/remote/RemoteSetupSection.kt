package com.capri.mobile.remote

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.capri.mobile.AMBER
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
import com.capri.mobile.chamfer
import com.capri.mobile.tr
import java.util.concurrent.Executors

@Composable
fun RemoteSetupSection() {
    val ctx = LocalContext.current
    val prefs = remember { RemotePrefs(ctx) }
    var showPair by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showAudit by remember { mutableStateOf(false) }
    var bgMonitor by remember { mutableStateOf(prefs.backgroundMonitor) }
    var zeroAvatar by remember { mutableStateOf(prefs.zeroAvatarReasoning) }
    var soundUri by remember { mutableStateOf(prefs.notificationSoundUri) }
    var soundLabel by remember { mutableStateOf(prefs.notificationSoundLabel) }

    LaunchedEffect(Unit) { RemoteState.ensureInit(ctx) }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val picked: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                res.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            val uriStr = picked?.toString().orEmpty()
            val label = if (picked != null) {
                runCatching { RingtoneManager.getRingtone(ctx, picked)?.getTitle(ctx) }.getOrNull().orEmpty()
            } else ""
            soundUri = uriStr
            soundLabel = label
            prefs.notificationSoundUri = uriStr
            prefs.notificationSoundLabel = label
        }
    }

    val audioFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: "custom-audio"
            val dest = File(ctx.filesDir, "custom_notification_sound")
            val copied = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrDefault(false)
            val storedUri = if (copied) Uri.fromFile(dest).toString() else uri.toString()
            soundUri = storedUri
            soundLabel = name
            prefs.notificationSoundUri = storedUri
            prefs.notificationSoundLabel = name
        }
    }

    if (showPair) PairPcDialog(onDismiss = { showPair = false }, onPaired = { showPair = false })
    if (showDevices) DevicesDialog(onDismiss = { showDevices = false })
    if (showAudit) SecurityAuditDialog(onDismiss = { showAudit = false })

    Card(
        tr("PC Remote & Devices"),
        tr("Pair Capri Mobile with your Windows host over Wi-Fi or Tailscale for multi-CLI agents, live screen control, files/Git, and quotas."),
    ) {
        for (p in RemoteState.profiles) {
            val active = p.id == RemoteState.activeProfile?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(PANEL_HI, chamfer(8.dp))
                    .border(1.dp, if (active && RemoteState.connected) GREEN else if (active) PINK else EDGE, chamfer(8.dp))
                    .clickable { RemoteState.selectProfile(ctx, p.id) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text(p.activeEndpoint(), color = MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (active) Pill(if (RemoteState.connected) tr("Online") else tr("Active"), if (RemoteState.connected) GREEN else AMBER)
                Spacer(Modifier.width(6.dp))
                SmallChip("✕", PINK) { RemoteState.removeProfile(ctx, p.id) }
            }
        }

        val activeProfile = RemoteState.activeProfile
        if (activeProfile != null && activeProfile.tailscaleEndpoint.isNotBlank()) {
            Text(tr("Local / Tailscale"), color = MUTED, fontFamily = SANS, fontSize = 12.sp)
            Segments(listOf("Local Wi-Fi", "Tailscale"), if (activeProfile.preferTailscale) 1 else 0) { idx ->
                RemoteState.toggleTailscaleMode(ctx, idx == 1)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CapriButton(tr("Pair PC"), Modifier.weight(1f), color = GREEN) { showPair = true }
            if (activeProfile != null) {
                CapriButton(tr("Connected Devices"), Modifier.weight(1f), quiet = true) {
                    RemoteState.refreshDevicesAndAudit(ctx)
                    showDevices = true
                }
            }
        }
        if (activeProfile != null) {
            CapriButton(tr("Security Audit"), Modifier.fillMaxWidth(), quiet = true, color = CYAN) {
                RemoteState.refreshDevicesAndAudit(ctx)
                showAudit = true
            }
        }

        Toggle(
            tr("Background task monitor"),
            tr("Keep an Android foreground notification while PC agent tasks are active."),
            bgMonitor,
        ) { enabled ->
            bgMonitor = enabled
            prefs.backgroundMonitor = enabled
            RemoteMonitorService.sync(ctx)
        }

        Toggle(
            tr("Zero-avatar on /debate & /fable-mind"),
            tr("Temporarily hide the floating Capri avatar during multi-AI debate or deep reasoning runs."),
            zeroAvatar,
        ) { enabled ->
            zeroAvatar = enabled
            prefs.zeroAvatarReasoning = enabled
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(tr("Notification sound"), color = INK, fontFamily = SANS, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    soundLabel.ifBlank { if (soundUri.isBlank()) tr("Silent / default") else soundUri.substringAfterLast('/') },
                    color = MUTED, fontFamily = MONO, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SmallChip(tr("Audio / MP3"), GREEN) {
                audioFilePicker.launch(arrayOf("audio/*"))
            }
            SmallChip(tr("System"), CYAN) {
                val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    if (soundUri.isNotBlank() && !soundUri.startsWith("file://")) {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(soundUri))
                    }
                }
                soundPicker.launch(i)
            }
            if (soundUri.isNotBlank()) {
                SmallChip(tr("Test"), CYAN) {
                    RemoteMonitorService.playTone(ctx, soundUri)
                }
                SmallChip(tr("Clear"), MUTED) {
                    soundUri = ""
                    soundLabel = ""
                    prefs.notificationSoundUri = ""
                    prefs.notificationSoundLabel = ""
                    runCatching { File(ctx.filesDir, "custom_notification_sound").delete() }
                }
            }
        }
    }
}

@Composable
private fun DevicesDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
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
                Box(Modifier.weight(1f)) { Label(tr("Connected Devices")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(290.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Trusted (${RemoteState.trustedDevices.size})", color = GREEN, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                for (d in RemoteState.trustedDevices) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL_HI, chamfer(8.dp))
                            .border(1.dp, EDGE, chamfer(8.dp))
                            .padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.deviceName, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${d.ip} · ${d.lastSeen}", color = MUTED, fontFamily = MONO, fontSize = 10.5.sp)
                        }
                        SmallChip(tr("Revoke"), AMBER) {
                            Executors.newSingleThreadExecutor().execute {
                                runCatching { RemoteState.client(ctx)?.revokeDevice(d.deviceId) }
                                RemoteState.refreshDevicesAndAudit(ctx)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        SmallChip(tr("Ban"), PINK) {
                            Executors.newSingleThreadExecutor().execute {
                                runCatching { RemoteState.client(ctx)?.banDevice(d.deviceId, d.deviceName) }
                                RemoteState.refreshDevicesAndAudit(ctx)
                            }
                        }
                    }
                }
                if (RemoteState.bannedDevices.isNotEmpty()) {
                    Text("Banned (${RemoteState.bannedDevices.size})", color = PINK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    for (b in RemoteState.bannedDevices) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(PANEL_HI, chamfer(8.dp))
                                .border(1.dp, PINK, chamfer(8.dp))
                                .padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.deviceName, color = INK, fontFamily = SANS, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${b.ip} · ${b.reason}", color = MUTED, fontFamily = MONO, fontSize = 10.5.sp)
                            }
                            SmallChip(tr("Unban"), GREEN) {
                                Executors.newSingleThreadExecutor().execute {
                                    runCatching { RemoteState.client(ctx)?.unbanDevice(b.deviceId) }
                                    RemoteState.refreshDevicesAndAudit(ctx)
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
private fun SecurityAuditDialog(onDismiss: () -> Unit) {
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
                Box(Modifier.weight(1f)) { Label(tr("Security Audit")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (RemoteState.auditItems.isEmpty()) {
                    Text(tr("No security audit entries."), color = MUTED, fontFamily = SANS, fontSize = 12.5.sp)
                }
                for (a in RemoteState.auditItems) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL_HI, chamfer(8.dp))
                            .border(1.dp, EDGE, chamfer(8.dp))
                            .padding(8.dp),
                    ) {
                        Text("${a.event} · ${a.ipAddress}", color = INK, fontFamily = MONO, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        Text("${a.method} ${a.path} · ${a.timestamp}", color = MUTED, fontFamily = MONO, fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}

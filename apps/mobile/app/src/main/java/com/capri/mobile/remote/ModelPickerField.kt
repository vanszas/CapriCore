package com.capri.mobile.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.capri.mobile.CYAN
import com.capri.mobile.EDGE
import com.capri.mobile.GREEN
import com.capri.mobile.INK
import com.capri.mobile.Label
import com.capri.mobile.MUTED
import com.capri.mobile.PANEL
import com.capri.mobile.PANEL_HI
import com.capri.mobile.PINK
import com.capri.mobile.capriField
import com.capri.mobile.chamfer

private fun modelProviderPrefix(model: String): String {
    val idx = model.indexOf('/')
    return if (idx > 0) model.substring(0, idx).lowercase() else "other"
}

private fun modelProviderBadge(prefix: String): Pair<String, Color> = when (prefix) {
    "ag" -> "AG" to PINK
    "cx" -> "CODEX" to CYAN
    "claude", "cc" -> "CLAUDE" to Color(0xFFFFB74D)
    "if" -> "IFLOW" to GREEN
    "gh" -> "GITHUB" to Color(0xFF90CAF9)
    "free" -> "FREE" to GREEN
    "kr" -> "KIRO" to Color(0xFFCE93D8)
    else -> prefix.uppercase().take(8) to MUTED
}

@Composable
fun ModelDropdownPicker(
    label: String,
    selectedModel: String,
    models: List<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }

    if (compact) {
        Row(
            modifier = modifier
                .clip(chamfer(6.dp))
                .background(PANEL_HI)
                .border(1.dp, if (selectedModel.isNotBlank()) PINK.copy(alpha = 0.65f) else EDGE, chamfer(6.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = if (selectedModel.isNotBlank()) selectedModel else "Model (${models.size})",
                color = if (selectedModel.isNotBlank()) INK else MUTED,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Text(
                text = if (loading) "…" else "▾",
                color = PINK,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            if (label.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(label)
                    Text(
                        text = if (loading) "Fetching…" else "${models.size} models",
                        color = if (models.isNotEmpty()) GREEN else MUTED,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(chamfer(8.dp))
                        .background(PANEL_HI)
                        .border(1.dp, if (selectedModel.isNotBlank()) PINK.copy(alpha = 0.6f) else EDGE, chamfer(8.dp))
                        .clickable { open = true }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val prefix = if (selectedModel.isNotBlank()) modelProviderPrefix(selectedModel) else ""
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (prefix.isNotBlank() && selectedModel.contains('/')) {
                            val (badge, badgeColor) = modelProviderBadge(prefix)
                            Text(
                                text = badge,
                                color = badgeColor,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(badgeColor.copy(alpha = 0.14f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        Text(
                            text = selectedModel.ifBlank { "Select 9Router model (${models.size} available)" },
                            color = if (selectedModel.isNotBlank()) INK else MUTED,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("▾", color = PINK, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Box(
                    modifier = Modifier
                        .clip(chamfer(8.dp))
                        .background(PANEL_HI)
                        .border(1.dp, CYAN.copy(alpha = 0.5f), chamfer(8.dp))
                        .clickable(enabled = !loading) { onRefresh() }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (loading) "…" else "↻",
                        color = CYAN,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (open) {
        ModelPickerDialog(
            title = label.ifBlank { "9Router Model" },
            selectedModel = selectedModel,
            models = models,
            loading = loading,
            onRefresh = onRefresh,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    title: String,
    selectedModel: String,
    models: List<String>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("ALL") }

    val providers = remember(models) {
        listOf("ALL") + models.map { modelProviderPrefix(it) }.distinct().sorted()
    }

    val filtered = remember(models, query, selectedProvider) {
        val q = query.trim().lowercase()
        models.filter { m ->
            val providerOk = selectedProvider == "ALL" || modelProviderPrefix(m) == selectedProvider
            val queryOk = q.isBlank() || m.lowercase().contains(q)
            providerOk && queryOk
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .clip(chamfer(12.dp))
                .background(PANEL)
                .border(1.dp, EDGE, chamfer(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = INK, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        text = if (loading) "Auto-fetching from 9Router…" else "${filtered.size} / ${models.size} models",
                        color = if (models.isNotEmpty()) GREEN else MUTED,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(if (loading) "Fetching…" else "↻ Refresh", color = CYAN, fontSize = 12.sp)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search model (e.g. claude, gpt, gemini, ag/)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = capriField(),
            )

            if (providers.size > 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (prov in providers) {
                        val active = selectedProvider == prov
                        val color = if (prov == "ALL") PINK else modelProviderBadge(prov).second
                        Box(
                            modifier = Modifier
                                .clip(chamfer(5.dp))
                                .background(if (active) color.copy(alpha = 0.22f) else PANEL_HI)
                                .border(1.dp, if (active) color else EDGE, chamfer(5.dp))
                                .clickable { selectedProvider = prov }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = if (prov == "ALL") "ALL (${models.size})" else prov.uppercase(),
                                color = if (active) color else MUTED,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            val trimmed = query.trim()
            if (trimmed.isNotBlank() && filtered.none { it.equals(trimmed, ignoreCase = true) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(chamfer(7.dp))
                        .background(CYAN.copy(alpha = 0.12f))
                        .border(1.dp, CYAN.copy(alpha = 0.5f), chamfer(7.dp))
                        .clickable { onSelect(trimmed) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Use \"$trimmed\"", color = CYAN, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("CUSTOM", color = CYAN, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (models.isEmpty()) "No models fetched yet. Tap ↻ Refresh or check 9Router." else "No matching models",
                        color = MUTED,
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(filtered, key = { it }) { model ->
                        val isSelected = model == selectedModel
                        val prefix = modelProviderPrefix(model)
                        val (badge, badgeColor) = modelProviderBadge(prefix)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(chamfer(7.dp))
                                .background(if (isSelected) PINK.copy(alpha = 0.18f) else PANEL_HI)
                                .border(1.dp, if (isSelected) PINK else EDGE, chamfer(7.dp))
                                .clickable { onSelect(model) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (model.contains('/')) {
                                Text(
                                    text = badge,
                                    color = badgeColor,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(badgeColor.copy(alpha = 0.14f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = model,
                                color = if (isSelected) PINK else INK,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isSelected) {
                                Text("✓", color = GREEN, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedModel.isNotBlank()) {
                    TextButton(onClick = { onSelect("") }) {
                        Text("Clear (Server Default)", color = MUTED, fontSize = 11.sp)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = PINK)
                }
            }
        }
    }
}

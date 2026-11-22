package com.capri.mobile.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairingPayload(
    val endpoint: String,
    val token: String,
    val name: String,
) {
    companion object {
        fun parse(raw: String): PairingPayload? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            // 1. agentremote://pair?data=<base64url>
            if (trimmed.startsWith("agentremote://", ignoreCase = true)) {
                return runCatching {
                    val uri = URI(trimmed)
                    if (uri.host?.lowercase() != "pair") return null
                    val query = uri.rawQuery ?: return null
                    val encoded = query.split('&')
                        .mapNotNull { pair ->
                            val parts = pair.split('=', limit = 2)
                            if (parts.size == 2 && parts[0] == "data") {
                                URLDecoder.decode(parts[1], "UTF-8")
                            } else null
                        }.firstOrNull() ?: return null
                    val decoded = String(decodeBase64Url(encoded), StandardCharsets.UTF_8)
                    fromJsonObject(JSONObject(decoded))
                }.getOrNull()
            }

            // 2. Raw JSON {"endpoint": "...", "token": "...", "name": "..."}
            if (trimmed.startsWith("{")) {
                return runCatching { fromJsonObject(JSONObject(trimmed)) }.getOrNull()
            }

            return null
        }

        private fun decodeBase64Url(input: String): ByteArray {
            val clean = input.replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() }
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            val table = IntArray(256) { -1 }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            for (i in alphabet.indices) table[alphabet[i].code] = i
            val out = ByteArrayOutputStream()
            var i = 0
            while (i + 3 < padded.length) {
                val c0 = table[padded[i].code]
                val c1 = table[padded[i + 1].code]
                if (c0 < 0 || c1 < 0) throw IllegalArgumentException("Invalid base64")
                val c2 = if (padded[i + 2] == '=') -1 else table[padded[i + 2].code]
                val c3 = if (padded[i + 3] == '=') -1 else table[padded[i + 3].code]
                out.write((c0 shl 2) or (c1 shr 4))
                if (c2 >= 0) {
                    out.write(((c1 and 0x0F) shl 4) or (c2 shr 2))
                    if (c3 >= 0) out.write(((c2 and 0x03) shl 6) or c3)
                }
                i += 4
            }
            return out.toByteArray()
        }

        private fun fromJsonObject(obj: JSONObject): PairingPayload? {
            val endpoint = obj.optString("endpoint", "").trim().trimEnd('/')
            val token = obj.optString("token", "").trim()
            val name = obj.optString("name", "Capri PC").trim().ifEmpty { "Capri PC" }
            if (!isValidHttpUrl(endpoint) || token.length < 8) return null
            return PairingPayload(endpoint = endpoint, token = token, name = name)
        }

        fun isValidHttpUrl(url: String): Boolean {
            val low = url.lowercase()
            return (low.startsWith("http://") || low.startsWith("https://")) && url.length > 10
        }

        fun normalizeEndpointInput(raw: String, defaultPort: Int = 9120): String {
            var clean = raw.trim().trimEnd('/')
            if (clean.isEmpty()) return ""
            if (!clean.contains("://")) clean = "http://$clean"
            return runCatching {
                val u = URI(clean)
                val host = u.host ?: return clean
                if (u.port == -1 && u.scheme.equals("http", ignoreCase = true)) {
                    "http://$host:$defaultPort${u.rawPath ?: ""}".trimEnd('/')
                } else {
                    clean
                }
            }.getOrDefault(clean)
        }

        fun isTailscaleHost(endpointOrHost: String): Boolean {
            val host = runCatching {
                val u = if (endpointOrHost.contains("://")) URI(endpointOrHost) else URI("http://$endpointOrHost")
                u.host.orEmpty()
            }.getOrDefault(endpointOrHost).lowercase()
            if (host.endsWith(".ts.net")) return true
            val parts = host.split('.')
            if (parts.size == 4) {
                val a = parts[0].toIntOrNull() ?: return false
                val b = parts[1].toIntOrNull() ?: return false
                return a == 100 && b in 64..127
            }
            return false
        }
    }
}

data class PcProfile(
    val id: String,
    val name: String,
    val localEndpoint: String,
    val tailscaleEndpoint: String = "",
    val preferTailscale: Boolean = false,
    val lastWorkspace: String = "",
    val lastSessionId: String = "",
) {
    fun activeEndpoint(): String =
        if (preferTailscale && tailscaleEndpoint.isNotBlank()) tailscaleEndpoint
        else localEndpoint.ifBlank { tailscaleEndpoint }

    fun candidateEndpoints(): List<Pair<Boolean, String>> {
        val list = mutableListOf<Pair<Boolean, String>>()
        val ts = tailscaleEndpoint.trim().trimEnd('/')
        val loc = localEndpoint.trim().trimEnd('/')
        if (preferTailscale) {
            if (ts.isNotBlank()) list.add(true to ts)
            if (loc.isNotBlank() && loc != ts) list.add(false to loc)
        } else {
            if (loc.isNotBlank()) list.add(false to loc)
            if (ts.isNotBlank() && ts != loc) list.add(true to ts)
        }
        return list
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("localEndpoint", localEndpoint)
        .put("tailscaleEndpoint", tailscaleEndpoint)
        .put("preferTailscale", preferTailscale)
        .put("lastWorkspace", lastWorkspace)
        .put("lastSessionId", lastSessionId)

    companion object {
        fun fromJson(obj: JSONObject): PcProfile = PcProfile(
            id = obj.optString("id", ""),
            name = obj.optString("name", "PC"),
            localEndpoint = obj.optString("localEndpoint", ""),
            tailscaleEndpoint = obj.optString("tailscaleEndpoint", ""),
            preferTailscale = obj.optBoolean("preferTailscale", false),
            lastWorkspace = obj.optString("lastWorkspace", ""),
            lastSessionId = obj.optString("lastSessionId", ""),
        )
    }
}

enum class RemoteExecutionMode(val wire: String, val label: String) {
    SINGLE("single", "Single"),
    PARALLEL("parallel", "Parallel"),
    COORDINATOR("coordinator", "Coordinator"),
}

enum class RemotePermissionMode(val wire: String, val label: String) {
    ASK("ask", "Ask"),
    WORKSPACE("workspace", "Workspace"),
    FULL("full", "Full PC"),
}

data class RemoteAgentInfo(
    val id: String,
    val name: String,
    val description: String,
    val supportsStreaming: Boolean,
    val installed: Boolean,
    val command: String,
    val detected: Boolean = installed,
)

data class RemoteAttachmentDraft(
    val id: String,
    val name: String,
    val bytes: ByteArray,
    val previewSummary: String = "",
)

data class ToolActivityItem(
    val id: String,
    val name: String,
    val agentId: String,
    val text: String,
    val status: String, // running | completed | failed
)

data class ApprovalItem(
    val requestId: String,
    val title: String,
    val detail: String,
    val status: String = "pending",
)

data class ClarificationItem(
    val requestId: String,
    val question: String,
    val choices: List<String>,
    val answered: String = "",
)

data class RemoteMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestamp: String = "",
    val agentId: String = "",
)

data class RemoteSession(
    val id: String,
    val title: String,
    val workspace: String,
    val workspaceName: String,
    val modelName: String,
    val updatedAt: String,
    val personalizationOverride: String? = null,
    val messages: List<RemoteMessage> = emptyList(),
)

sealed interface RemoteStreamEvent {
    data class Started(val agents: List<String>, val mode: String) : RemoteStreamEvent
    data class Delta(val agentId: String, val text: String) : RemoteStreamEvent
    data class Reasoning(val agentId: String, val status: String, val text: String) : RemoteStreamEvent
    data class ToolStarted(val toolId: String, val agentId: String, val name: String, val text: String) : RemoteStreamEvent
    data class ToolCompleted(val toolId: String, val agentId: String, val name: String, val text: String) : RemoteStreamEvent
    data class ApprovalRequested(val requestId: String, val title: String, val detail: String) : RemoteStreamEvent
    data class ClarificationRequested(val requestId: String, val question: String, val choices: List<String>) : RemoteStreamEvent
    data class AgentFailed(val agentId: String, val error: String) : RemoteStreamEvent
    data object Stopped : RemoteStreamEvent
    data class Completed(val ok: Boolean, val error: String = "") : RemoteStreamEvent
}

fun parseStreamLine(line: String): RemoteStreamEvent? {
    val clean = line.trim()
    if (clean.isEmpty()) return null
    val obj = runCatching { JSONObject(clean) }.getOrNull() ?: return null
    val type = obj.optString("type", "")
    val agentId = obj.optString("agent_id", "")
    return when (type) {
        "started" -> {
            val arr = obj.optJSONArray("agents") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            RemoteStreamEvent.Started(list, obj.optString("mode", "single"))
        }
        "delta" -> RemoteStreamEvent.Delta(agentId, obj.optString("text", ""))
        "reasoning" -> RemoteStreamEvent.Reasoning(
            agentId = agentId,
            status = obj.optString("status", "running"),
            text = obj.optString("text", "Thinking..."),
        )
        "tool_started" -> RemoteStreamEvent.ToolStarted(
            toolId = obj.optString("tool_id", "${System.nanoTime()}"),
            agentId = agentId,
            name = obj.optString("name", "tool"),
            text = obj.optString("text", ""),
        )
        "tool_completed" -> RemoteStreamEvent.ToolCompleted(
            toolId = obj.optString("tool_id", ""),
            agentId = agentId,
            name = obj.optString("name", "tool"),
            text = obj.optString("text", ""),
        )
        "approval_request" -> RemoteStreamEvent.ApprovalRequested(
            requestId = obj.optString("request_id", ""),
            title = obj.optString("title", "Approval requested"),
            detail = obj.optString("text", obj.optString("detail", "")),
        )
        "clarification_request" -> {
            val arr = obj.optJSONArray("choices") ?: JSONArray()
            val choices = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            RemoteStreamEvent.ClarificationRequested(
                requestId = obj.optString("request_id", ""),
                question = obj.optString("text", obj.optString("question", "")),
                choices = choices,
            )
        }
        "agent_failed" -> RemoteStreamEvent.AgentFailed(agentId, obj.optString("error", "Failed"))
        "stopped" -> RemoteStreamEvent.Stopped
        "completed" -> RemoteStreamEvent.Completed(
            ok = obj.optBoolean("ok", true),
            error = obj.optString("error", ""),
        )
        else -> null
    }
}

data class RemoteTaskAgentState(
    val id: String,
    val name: String,
    val status: String,
    val phase: String,
    val detail: String,
    val elapsedSeconds: Int,
)

data class RemoteTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val status: String,
    val phase: String,
    val detail: String,
    val agents: List<String>,
    val agentStates: List<RemoteTaskAgentState>,
    val updatedAt: String,
)

data class DesktopWindowInfo(
    val hwnd: Long,
    val title: String,
    val process: String,
) {
    val isCapri: Boolean
        get() = "$title $process".lowercase().contains("capri")
}

data class PcWorkspace(
    val id: String,
    val name: String,
    val path: String,
    val isActive: Boolean,
)

data class WorkspaceFolderListing(
    val path: String,
    val parent: String?,
    val folders: List<PcWorkspace>,
)

data class WorkspaceTreeEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
)

data class WorkspaceFileDoc(
    val path: String,
    val name: String,
    val content: String,
    val diff: String,
    val hash: String,
    val size: Int,
    val lineCount: Int,
    val editable: Boolean,
    val gitStatus: String,
)

data class GitFileChange(
    val path: String,
    val status: String,
    val staged: Boolean,
)

data class GitCommitItem(
    val hash: String,
    val subject: String,
    val author: String,
)

data class GitNestedRepo(
    val name: String,
    val path: String,
)

data class GitStatusSnapshot(
    val isGitRepo: Boolean,
    val branch: String,
    val remoteUrl: String,
    val upstream: String,
    val ahead: Int,
    val behind: Int,
    val additions: Int,
    val deletions: Int,
    val files: List<GitFileChange>,
    val incoming: List<GitCommitItem>,
    val outgoing: List<GitCommitItem>,
    val nestedRepos: List<GitNestedRepo>,
)

data class QuotaWindow(
    val id: String,
    val label: String,
    val usedPercent: Double?,
    val remainingPercent: Double?,
    val resetAt: String,
) {
    /**
     * Truthful quota calculation: 0/null without limit_reached means 0% used,
     * never falling back to synthetic 45% or 100%.
     */
    fun effectiveUsedPercent(limitReached: Boolean): Double {
        if (usedPercent != null) return usedPercent.coerceIn(0.0, 100.0)
        if (remainingPercent != null) return (100.0 - remainingPercent).coerceIn(0.0, 100.0)
        return if (limitReached) 100.0 else 0.0
    }

    fun effectiveRemainingPercent(limitReached: Boolean): Double {
        if (remainingPercent != null) return remainingPercent.coerceIn(0.0, 100.0)
        return (100.0 - effectiveUsedPercent(limitReached)).coerceIn(0.0, 100.0)
    }
}

data class QuotaAccount(
    val id: String,
    val provider: String,
    val name: String,
    val active: Boolean,
    val status: String,
    val plan: String,
    val model: String,
    val limitReached: Boolean,
    val error: String,
    val quotas: List<QuotaWindow>,
)

data class ProviderUsageEntry(
    val timestamp: String,
    val provider: String,
    val model: String,
    val totalTokens: Long,
    val cost: Double,
    val account: String,
)

data class ProviderUsageData(
    val available: Boolean,
    val source: String,
    val range: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val estimatedCost: Double,
    val providers: List<String>,
    val models: List<String>,
    val quotaAccounts: List<QuotaAccount>,
    val recent: List<ProviderUsageEntry>,
    val reason: String,
)

data class DeviceEntry(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val pairedAt: String,
    val lastSeen: String,
    val reason: String,
)

data class SecurityAuditItem(
    val timestamp: String,
    val event: String,
    val ipAddress: String,
    val method: String,
    val path: String,
)

data class SmartRouterConfig(
    val enabled: Boolean = true,
    val mode: String = "auto",
    val simpleModel: String = "ag/gemini-3.8-flash-high",
    val complexModel: String = "ag/claude-opus-4-6-thinking",
    val complexityThreshold: Int = 35,
    val listenPort: Int = 20130,
    val upstreamUrl: String = "http://localhost:20128/v1",
)

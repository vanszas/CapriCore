package com.capri.mobile.remote

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.capri.mobile.CapriService
import com.capri.mobile.LmStudio
import com.capri.mobile.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object RemoteState {
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    var initialized by mutableStateOf(false)
        private set

    var profiles by mutableStateOf<List<PcProfile>>(emptyList())
    var activeProfile by mutableStateOf<PcProfile?>(null)
    var connected by mutableStateOf(false)
    var connecting by mutableStateOf(false)
    var statusError by mutableStateOf("")
    var tailscaleIp by mutableStateOf("")
    var networkMode by mutableStateOf("")
    var activeWorkspace by mutableStateOf("")

    // Agents & Sessions
    var agents by mutableStateOf<List<RemoteAgentInfo>>(emptyList())
    var selectedAgentIds by mutableStateOf<Set<String>>(emptySet())
    var executionMode by mutableStateOf(RemoteExecutionMode.SINGLE)
    var permissionMode by mutableStateOf(RemotePermissionMode.WORKSPACE)
    var concurrency by mutableIntStateOf(2)
    var maxConcurrentAgents by mutableIntStateOf(2)
    var coordinatorId by mutableStateOf("")
    var models by mutableStateOf<List<String>>(emptyList())
    var selectedModel by mutableStateOf("")
    var fetchingModels by mutableStateOf(false)
    var skills by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var workspaces by mutableStateOf<List<PcWorkspace>>(emptyList())
    var sessions by mutableStateOf<List<RemoteSession>>(emptyList())
    var activeSession by mutableStateOf<RemoteSession?>(null)
    var messages by mutableStateOf<List<RemoteMessage>>(emptyList())
    var streaming by mutableStateOf(false)
    var streamingText by mutableStateOf("")
    var toolActivities by mutableStateOf<List<ToolActivityItem>>(emptyList())
    var approvals by mutableStateOf<List<ApprovalItem>>(emptyList())
    var clarifications by mutableStateOf<List<ClarificationItem>>(emptyList())
    var attachments by mutableStateOf<List<RemoteAttachmentDraft>>(emptyList())
    var globalPersonalization by mutableStateOf("")

    // /debate
    var debateRunning by mutableStateOf(false)
    var debateModels by mutableStateOf<List<String>>(emptyList())

    // Tasks
    var tasks by mutableStateOf<List<RemoteTask>>(emptyList())

    // Devices & Security
    var trustedDevices by mutableStateOf<List<DeviceEntry>>(emptyList())
    var bannedDevices by mutableStateOf<List<DeviceEntry>>(emptyList())
    var auditItems by mutableStateOf<List<SecurityAuditItem>>(emptyList())

    // Files & Git
    var treePath by mutableStateOf("")
    var treeEntries by mutableStateOf<List<WorkspaceTreeEntry>>(emptyList())
    var openFile by mutableStateOf<WorkspaceFileDoc?>(null)
    var gitStatus by mutableStateOf<GitStatusSnapshot?>(null)

    // Usage & Router
    var usageRange by mutableStateOf("24h")
    var usageData by mutableStateOf<ProviderUsageData?>(null)
    var routerConfig by mutableStateOf(SmartRouterConfig())
    var routerModels by mutableStateOf<List<String>>(emptyList())
    var routerRunning by mutableStateOf(false)
    var requestedSubTab by mutableStateOf<String?>(null)
    var connectionLogs by mutableStateOf<List<String>>(emptyList())

    private fun addLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        post {
            connectionLogs = (connectionLogs + "[$ts] $line").takeLast(25)
        }
    }

    // Screen & Windows
    var desktopWindows by mutableStateOf<List<DesktopWindowInfo>>(emptyList())
    var shortcuts by mutableStateOf<List<Pair<String, String>>>(emptyList())

    fun ensureInit(ctx: Context) {
        if (initialized) return
        val app = ctx.applicationContext
        val prefs = RemotePrefs(app)
        profiles = prefs.profiles()
        activeProfile = prefs.activeProfile()
        shortcuts = prefs.customShortcuts()
        initialized = true
        if (activeProfile != null && prefs.activeToken().isNotBlank()) {
            connectActive(app)
        }
        RemoteMonitorService.sync(app)
    }

    fun client(ctx: Context): RemoteClient? {
        val prefs = RemotePrefs(ctx)
        val p = prefs.activeProfile() ?: return null
        val t = prefs.tokenFor(p.id)
        if (p.activeEndpoint().isBlank() || t.isBlank()) return null
        return RemoteClient(p.activeEndpoint(), t, prefs.deviceId)
    }

    private fun post(block: () -> Unit) = main.post(block)

    fun pairAndSave(
        ctx: Context,
        name: String,
        endpoint: String,
        token: String,
        preferTailscale: Boolean = false,
        onDone: (String?) -> Unit = {},
    ) {
        val app = ctx.applicationContext
        val cleanUrl = PairingPayload.normalizeEndpointInput(endpoint)
        if (!PairingPayload.isValidHttpUrl(cleanUrl) || token.trim().length < 6) {
            addLog("PAIR invalid endpoint/token: $endpoint")
            onDone("Invalid endpoint URL or pairing token.")
            return
        }
        if (cleanUrl.contains("127.0.0.1") || cleanUrl.contains("localhost")) {
            val msg = "QR memakai 127.0.0.1 (Desktop-only). Ubah PC ke mode Tailscale lalu scan ulang."
            addLog("PAIR rejected loopback QR: $cleanUrl")
            onDone(msg)
            return
        }
        val scannedTailscale = PairingPayload.isTailscaleHost(cleanUrl)
        val phoneTs = runCatching { LmStudio.detectTailscaleVpnIp() }.getOrNull() ?: "OFF"
        addLog("PAIR start -> $cleanUrl (Phone Tailscale=$phoneTs)")
        connecting = true
        statusError = ""
        val t0 = System.currentTimeMillis()
        io.execute {
            val prefs = RemotePrefs(app)
            val tmpClient = RemoteClient(cleanUrl, token.trim(), prefs.deviceId)
            try {
                val st = tmpClient.status()
                val dt = System.currentTimeMillis() - t0
                addLog("PAIR OK (${dt}ms) -> ${cleanUrl}")
                val tsIp = st.optString("tailscale_ip", "")
                val tsUrl = when {
                    PairingPayload.isTailscaleHost(tsIp) -> "http://$tsIp:9120"
                    scannedTailscale -> cleanUrl
                    else -> ""
                }
                val localUrl = if (scannedTailscale) "" else cleanUrl
                val pairRes = tmpClient.pair("${Build.MANUFACTURER} ${Build.MODEL}".trim())
                val finalToken = pairRes.optString("token")
                check(pairRes.optString("status") == "approved" && finalToken.isNotBlank()) {
                    "PC pairing was not approved"
                }
                val id = "pc_${(localUrl.ifBlank { tsUrl }).hashCode().toUInt().toString(16)}"
                val profile = PcProfile(
                    id = id,
                    name = name.ifBlank { "Capri PC" },
                    localEndpoint = localUrl,
                    tailscaleEndpoint = tsUrl,
                    preferTailscale = scannedTailscale || (preferTailscale && tsUrl.isNotBlank()),
                    lastWorkspace = st.optJSONObject("workspace")?.optString("path", "").orEmpty(),
                )
                prefs.saveProfile(profile, finalToken)
                post {
                    profiles = prefs.profiles()
                    activeProfile = profile
                    connectActive(app)
                    RemoteMonitorService.sync(app)
                    onDone(null)
                }
            } catch (e: Exception) {
                val dt = System.currentTimeMillis() - t0
                val msg = e.message ?: "Connection failed"
                addLog("PAIR FAIL (${dt}ms) $cleanUrl -> ${e.javaClass.simpleName}: $msg")
                post {
                    connecting = false
                    connected = false
                    statusError = msg
                    onDone(msg)
                }
            }
        }
    }

    fun selectProfile(ctx: Context, profileId: String) {
        val prefs = RemotePrefs(ctx)
        prefs.activeProfileId = profileId
        profiles = prefs.profiles()
        activeProfile = prefs.activeProfile()
        connectActive(ctx)
    }

    fun toggleTailscaleMode(ctx: Context, useTailscale: Boolean) {
        val prefs = RemotePrefs(ctx)
        val p = prefs.activeProfile() ?: return
        val updated = p.copy(preferTailscale = useTailscale)
        prefs.updateProfile(updated)
        profiles = prefs.profiles()
        activeProfile = updated
        connectActive(ctx)
    }

    fun removeProfile(ctx: Context, profileId: String) {
        val prefs = RemotePrefs(ctx)
        prefs.deleteProfile(profileId)
        profiles = prefs.profiles()
        activeProfile = prefs.activeProfile()
        connected = false
        if (activeProfile != null) connectActive(ctx)
        RemoteMonitorService.sync(ctx)
    }

    fun connectActive(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = RemotePrefs(app)
        val profile = prefs.activeProfile()
        val token = profile?.let { prefs.tokenFor(it.id) }.orEmpty()
        val candidates = profile?.candidateEndpoints().orEmpty()
        if (profile == null || token.isBlank() || candidates.isEmpty()) {
            connected = false
            connecting = false
            return
        }
        connecting = true
        statusError = ""
        io.execute {
            var chosenClient: RemoteClient? = null
            var chosenIsTailscale = profile.preferTailscale
            var stJson: org.json.JSONObject? = null
            var lastErr: Exception? = null

            for ((isTs, url) in candidates) {
                try {
                    val probe = RemoteClient(url, token, prefs.deviceId)
                    val status = probe.status()
                    val authorizedClient = try {
                        probe.workspaces()
                        probe
                    } catch (error: RemoteHttpException) {
                        if (error.code != 401) throw error
                        val approval = probe.pair("${Build.MANUFACTURER} ${Build.MODEL}".trim())
                        val approvedToken = approval.optString("token")
                        check(approval.optString("status") == "approved" && approvedToken.isNotBlank()) {
                            "Approve this phone on the PC to view its screen."
                        }
                        RemoteClient(url, approvedToken, prefs.deviceId).also {
                            it.workspaces()
                            prefs.saveProfile(profile, approvedToken)
                        }
                    }
                    stJson = status
                    chosenClient = authorizedClient
                    chosenIsTailscale = isTs
                    break
                } catch (e: Exception) {
                    lastErr = e
                }
            }

            if (chosenClient == null || stJson == null) {
                val msg = lastErr?.message ?: "Unreachable"
                post {
                    connected = false
                    connecting = false
                    statusError = msg
                }
                return@execute
            }

            val c = chosenClient
            val st = stJson
            try {
                val wsPath = st.optJSONObject("workspace")?.optString("path", "").orEmpty()
                val perm = st.optString("permission", "workspace")
                val maxC = st.optInt("max_concurrent_agents", 2).coerceIn(1, 8)
                val ts = st.optString("tailscale_ip", "")
                val net = st.optString("network_mode", "")
                val rRun = st.optJSONObject("router")?.optBoolean("running", false) == true

                val ag = runCatching { c.agents() }.getOrDefault(emptyList())
                val rMods = runCatching { c.routerModels() }.getOrDefault(emptyList())
                val aMods = runCatching { c.models() }.getOrDefault(emptyList())
                val mods = (rMods + aMods).distinct()
                val sk = runCatching { c.skills() }.getOrDefault(emptyList())
                val wss = runCatching { c.workspaces() }.getOrDefault(emptyList())
                val sess = runCatching { c.sessions() }.getOrDefault(emptyList())
                val tks = runCatching { c.tasks() }.getOrDefault(emptyList())
                val deb = runCatching { c.debateStatus().optBoolean("running", false) }.getOrDefault(false)
                val sc = runCatching { c.shortcuts() }.getOrDefault(emptyList())
                val wins = runCatching { c.desktopWindows() }.getOrDefault(emptyList())
                val gPers = runCatching { c.globalPersonalization() }.getOrDefault("")

                // Persist discovered Tailscale endpoint and auto-failover transport preference
                prefs.activeProfile()?.let { cur ->
                    val tsUrl = if (PairingPayload.isTailscaleHost(ts)) "http://$ts:9120" else cur.tailscaleEndpoint
                    if (tsUrl != cur.tailscaleEndpoint || wsPath != cur.lastWorkspace || chosenIsTailscale != cur.preferTailscale) {
                        prefs.updateProfile(
                            cur.copy(
                                tailscaleEndpoint = tsUrl,
                                lastWorkspace = wsPath,
                                preferTailscale = chosenIsTailscale,
                            ),
                        )
                    }
                }
                if (sc.isNotEmpty()) prefs.saveCustomShortcuts(sc)

                // Restore last session or create if needed
                val savedSid = prefs.activeProfile()?.lastSessionId.orEmpty()
                val pickedSess = sess.firstOrNull { it.id == savedSid } ?: sess.firstOrNull()
                val loadedSess = pickedSess?.let { runCatching { c.loadSession(it.id) }.getOrDefault(it) }

                post {
                    profiles = prefs.profiles()
                    activeProfile = prefs.activeProfile()
                    connected = true
                    connecting = false
                    statusError = ""
                    tailscaleIp = ts
                    networkMode = net
                    activeWorkspace = wsPath
                    routerRunning = rRun
                    maxConcurrentAgents = maxC
                    concurrency = concurrency.coerceIn(1, maxC)
                    permissionMode = RemotePermissionMode.entries.firstOrNull { it.wire == perm }
                        ?: RemotePermissionMode.WORKSPACE
                    agents = ag
                    selectedAgentIds = selectedAgentIds.intersect(ag.filter { it.installed }.map { it.id }.toSet())
                    if (coordinatorId.isBlank()) coordinatorId = selectedAgentIds.firstOrNull().orEmpty()
                    models = mods
                    routerModels = rMods
                    if (selectedModel.isBlank() && mods.isNotEmpty()) selectedModel = mods.first()
                    skills = sk
                    workspaces = wss
                    sessions = sess
                    activeSession = loadedSess
                    messages = loadedSess?.messages ?: emptyList()
                    tasks = tks
                    debateRunning = deb
                    if (sc.isNotEmpty()) shortcuts = sc
                    desktopWindows = wins
                    globalPersonalization = gPers
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unreachable"
                post {
                    connected = false
                    connecting = false
                    statusError = msg
                }
            }
        }
    }

    fun selectSession(ctx: Context, sessionId: String) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.loadSession(sessionId) }.onSuccess { s ->
                val prefs = RemotePrefs(ctx)
                prefs.activeProfile()?.let { prefs.updateProfile(it.copy(lastSessionId = s.id)) }
                post {
                    activeProfile = prefs.activeProfile()
                    activeSession = s
                    messages = s.messages
                    toolActivities = emptyList()
                    approvals = emptyList()
                    clarifications = emptyList()
                }
            }
        }
    }

    fun createSession(ctx: Context) {
        val c = client(ctx) ?: return
        val wsName = activeWorkspace.substringAfterLast('\\').substringAfterLast('/').ifBlank { "Workspace" }
        io.execute {
            runCatching { c.createSession(wsName, selectedModel.ifBlank { "PC Agent" }) }.onSuccess { s ->
                val prefs = RemotePrefs(ctx)
                prefs.activeProfile()?.let { prefs.updateProfile(it.copy(lastSessionId = s.id)) }
                val list = runCatching { c.sessions() }.getOrDefault(listOf(s) + sessions)
                post {
                    activeProfile = prefs.activeProfile()
                    sessions = list
                    activeSession = s
                    messages = s.messages
                    toolActivities = emptyList()
                }
            }
        }
    }

    fun renameSession(ctx: Context, id: String, title: String) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.renameSession(id, title) }.onSuccess { updated ->
                val list = runCatching { c.sessions() }.getOrDefault(sessions)
                post {
                    sessions = list
                    if (activeSession?.id == id) activeSession = updated
                }
            }
        }
    }

    fun deleteSession(ctx: Context, id: String) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.deleteSession(id) }.onSuccess {
                val list = runCatching { c.sessions() }.getOrDefault(emptyList())
                val next = list.firstOrNull()?.let { runCatching { c.loadSession(it.id) }.getOrDefault(it) }
                post {
                    sessions = list
                    activeSession = next
                    messages = next?.messages ?: emptyList()
                }
            }
        }
    }

    fun switchWorkspace(ctx: Context, path: String, onDone: (String?) -> Unit = {}) {
        val c = client(ctx) ?: return
        io.execute {
            try {
                val ws = c.selectWorkspace(path)
                val wss = runCatching { c.workspaces() }.getOrDefault(workspaces)
                val sess = runCatching { c.sessions() }.getOrDefault(emptyList())
                post {
                    if (activeWorkspace != ws.path) {
                        activeSession = null
                        messages = emptyList()
                    }
                    activeWorkspace = ws.path
                    workspaces = wss
                    sessions = sess
                    treePath = ""
                    openFile = null
                    onDone(null)
                }
                refreshFilesAndGit(ctx)
            } catch (e: Exception) {
                post { onDone(e.message ?: "Failed") }
            }
        }
    }

    fun sendPrompt(ctx: Context, rawText: String, onError: (String) -> Unit = {}) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val app = ctx.applicationContext
        val c = client(app) ?: run { onError("PC not paired"); return }
        val prefs = RemotePrefs(app)
        val mode = executionMode
        val useAgents = if (mode == RemoteExecutionMode.SINGLE) selectedAgentIds.take(1) else selectedAgentIds.toList()
        val minimumAgents = if (mode == RemoteExecutionMode.SINGLE) 1 else 2
        if (useAgents.size < minimumAgents) {
            onError(tr(if (minimumAgents == 1) "Select one PC agent" else "Select at least two PC agents"))
            return
        }
        val reasoningFocus = prefs.zeroAvatarReasoning &&
            (text.contains("/debate", ignoreCase = true) || text.contains("/fable-mind", ignoreCase = true))
        val draftAtts = attachments.toList()

        streaming = true
        streamingText = ""
        toolActivities = emptyList()
        approvals = emptyList()
        clarifications = emptyList()
        attachments = emptyList()
        if (reasoningFocus) CapriService.setReasoningFocus(app, true)

        io.execute {
            try {
                val workspace = activeWorkspace
                val previous = activeSession
                fun newSession() = c.createSession(
                    activeWorkspace.substringAfterLast('\\').ifBlank { "Workspace" },
                    selectedModel.ifBlank { "PC Agent" },
                ).also { created ->
                    check(workspace.isBlank() || created.workspace.equals(workspace, ignoreCase = true)) {
                        "Workspace PC berubah. Sambungkan ulang."
                    }
                    post { activeSession = created }
                }.id
                var sid = previous?.id ?: newSession()

                val optimisticUser = RemoteMessage("local_${System.currentTimeMillis()}", "user", text)
                post { messages = messages + optimisticUser }

                val buf = StringBuilder()
                val announced = HashSet<String>()
                fun send() = c.streamPrompt(
                    sessionId = sid,
                    text = text,
                    attachments = draftAtts,
                    model = selectedModel,
                    agents = useAgents,
                    mode = mode,
                    concurrency = concurrency,
                    coordinator = coordinatorId.ifBlank { useAgents.first() },
                    permission = permissionMode,
                ) { ev ->
                    when (ev) {
                        is RemoteStreamEvent.Started -> Unit
                        is RemoteStreamEvent.Delta -> {
                            if (useAgents.size > 1 && ev.agentId.isNotBlank() && announced.add(ev.agentId)) {
                                val label = agents.firstOrNull { it.id == ev.agentId }?.name ?: ev.agentId
                                buf.append(if (buf.isEmpty()) "**$label**\n\n" else "\n\n**$label**\n\n")
                            }
                            buf.append(ev.text)
                            val snap = buf.toString()
                            post { streamingText = snap }
                        }
                        is RemoteStreamEvent.Reasoning -> {
                            val item = ToolActivityItem(
                                id = "reason_${ev.agentId}",
                                name = "reasoning",
                                agentId = ev.agentId,
                                text = ev.text,
                                status = if (ev.status == "completed") "completed" else "running",
                            )
                            post {
                                toolActivities = toolActivities.filterNot { it.id == item.id } + item
                            }
                        }
                        is RemoteStreamEvent.ToolStarted -> {
                            val item = ToolActivityItem(ev.toolId, ev.name, ev.agentId, ev.text, "running")
                            post { toolActivities = toolActivities.filterNot { it.id == item.id } + item }
                        }
                        is RemoteStreamEvent.ToolCompleted -> {
                            val item = ToolActivityItem(ev.toolId, ev.name, ev.agentId, ev.text, "completed")
                            post { toolActivities = toolActivities.filterNot { it.id == item.id } + item }
                        }
                        is RemoteStreamEvent.ApprovalRequested -> {
                            post { approvals = approvals + ApprovalItem(ev.requestId, ev.title, ev.detail) }
                        }
                        is RemoteStreamEvent.ClarificationRequested -> {
                            post { clarifications = clarifications + ClarificationItem(ev.requestId, ev.question, ev.choices) }
                        }
                        is RemoteStreamEvent.AgentFailed -> {
                            buf.append("\n\n**${ev.agentId} failed:** ${ev.error}")
                            val snap = buf.toString()
                            post { streamingText = snap }
                        }
                        RemoteStreamEvent.Stopped -> Unit
                        is RemoteStreamEvent.Completed -> {
                            if (!ev.ok && ev.error.isNotBlank()) post { onError(tr(ev.error)) }
                        }
                    }
                }
                try {
                    send()
                } catch (e: RemoteHttpException) {
                    if (previous == null || e.code != 404 || e.message != "session not found") throw e
                    sid = newSession()
                    post { messages = listOf(optimisticUser) }
                    send()
                }
                val refreshed = runCatching { c.loadSession(sid) }.getOrNull()
                val refreshedTasks = runCatching { c.tasks() }.getOrDefault(tasks)
                post {
                    if (refreshed != null) {
                        activeSession = refreshed
                        messages = refreshed.messages
                    } else if (buf.isNotEmpty()) {
                        messages = messages + RemoteMessage("asst_${System.currentTimeMillis()}", "assistant", buf.toString())
                    }
                    streaming = false
                    streamingText = ""
                    tasks = refreshedTasks
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Stream error"
                post {
                    streaming = false
                    streamingText = ""
                    onError(msg)
                }
            } finally {
                if (reasoningFocus) CapriService.setReasoningFocus(app, false)
            }
        }
    }

    fun stopActiveSession(ctx: Context) {
        val c = client(ctx) ?: return
        val sid = activeSession?.id ?: return
        io.execute {
            runCatching { c.stopSession(sid) }
            post { streaming = false }
            if (RemotePrefs(ctx).zeroAvatarReasoning) CapriService.setReasoningFocus(ctx.applicationContext, false)
        }
    }

    fun resolveApproval(ctx: Context, requestId: String, approve: Boolean, onDone: (String?) -> Unit = {}) {
        val c = client(ctx) ?: return
        val sid = activeSession?.id.orEmpty()
        val decision = if (approve) "approve" else "deny"
        io.execute {
            val res = runCatching { c.respondToApproval(requestId, decision, sid) }
            post {
                res.onSuccess {
                    approvals = approvals.filterNot { it.requestId == requestId }
                    if (!approve) streaming = false
                    onDone(null)
                }.onFailure { onDone(it.message ?: "Approval failed") }
            }
        }
    }

    fun resolveClarification(ctx: Context, requestId: String, answer: String, onDone: (String?) -> Unit = {}) {
        val c = client(ctx) ?: return
        io.execute {
            val res = runCatching { c.respondToClarification(requestId, answer) }
            post {
                res.onSuccess {
                    clarifications = clarifications.filterNot { it.requestId == requestId }
                    onDone(null)
                }.onFailure { onDone(it.message ?: "Clarification failed") }
            }
        }
        sendPrompt(ctx, answer, onDone)
    }

    fun toggleDebate(ctx: Context, task: String = "", stop: Boolean = false, onDone: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        val c = client(app) ?: return
        val prefs = RemotePrefs(app)
        io.execute {
            try {
                if (stop) {
                    c.stopDebate()
                    if (prefs.zeroAvatarReasoning) CapriService.setReasoningFocus(app, false)
                    post {
                        debateRunning = false
                        onDone("Debate stopped")
                    }
                } else {
                    if (prefs.zeroAvatarReasoning) CapriService.setReasoningFocus(app, true)
                    c.startDebate(task)
                    post {
                        debateRunning = true
                        onDone("Debate started")
                    }
                }
            } catch (e: Exception) {
                if (!stop && prefs.zeroAvatarReasoning) CapriService.setReasoningFocus(app, false)
                post { onDone(e.message ?: "Debate error") }
            }
        }
    }

    fun refreshAgents(ctx: Context) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.agents() }.onSuccess { found ->
                post {
                    agents = found
                    selectedAgentIds = selectedAgentIds.intersect(found.filter { it.installed }.map { it.id }.toSet())
                }
            }
        }
    }

    fun refreshTasks(ctx: Context) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.tasks() }.onSuccess { list -> post { tasks = list } }
        }
    }

    fun refreshFilesAndGit(ctx: Context, fetchGit: Boolean = false, path: String = treePath) {
        val c = client(ctx) ?: return
        io.execute {
            val entries = runCatching { c.tree(path) }.getOrNull()
            val git = runCatching { c.gitStatus(fetchGit) }.getOrNull()
            post {
                if (entries != null) {
                    treePath = path
                    treeEntries = entries
                }
                if (git != null) gitStatus = git
            }
        }
    }

    fun openWorkspaceFile(ctx: Context, path: String, onError: (String) -> Unit = {}) {
        val c = client(ctx) ?: return
        io.execute {
            try {
                val doc = c.readFile(path)
                post { openFile = doc }
            } catch (e: Exception) {
                post { onError(e.message ?: "Cannot open file") }
            }
        }
    }

    fun saveWorkspaceFile(ctx: Context, path: String, content: String, baseHash: String, onDone: (String?) -> Unit) {
        val c = client(ctx) ?: return
        io.execute {
            try {
                val doc = c.saveFile(path, content, baseHash)
                post {
                    openFile = doc
                    onDone(null)
                }
            } catch (e: Exception) {
                post { onDone(e.message ?: "Save failed") }
            }
        }
    }

    fun refreshUsageAndRouter(ctx: Context, range: String = usageRange) {
        val c = client(ctx) ?: return
        io.execute {
            val u = runCatching { c.providerUsage(range) }.getOrNull()
            val rc = runCatching { c.routerConfig() }.getOrNull()
            val rm = runCatching { c.routerModels() }.getOrNull()
            val rs = runCatching { c.routerStatus().optBoolean("running", false) }.getOrNull()
            post {
                usageRange = range
                if (u != null) usageData = u
                if (rc != null) routerConfig = rc
                if (rm != null) routerModels = rm
                if (rs != null) routerRunning = rs
            }
        }
    }

    fun refreshDevicesAndAudit(ctx: Context) {
        val c = client(ctx) ?: return
        io.execute {
            val devs = runCatching { c.devices() }.getOrNull()
            val aud = runCatching { c.securityAudit() }.getOrNull()
            post {
                if (devs != null) {
                    trustedDevices = devs.first
                    bannedDevices = devs.second
                }
                if (aud != null) auditItems = aud
            }
        }
    }

    fun refreshModels(ctx: Context, onDone: (Int) -> Unit = {}) {
        val c = client(ctx) ?: return
        fetchingModels = true
        io.execute {
            val rMods = runCatching { c.routerModels() }.getOrDefault(emptyList())
            val aMods = runCatching { c.models() }.getOrDefault(emptyList())
            val merged = (rMods + aMods).distinct()
            post {
                fetchingModels = false
                if (merged.isNotEmpty()) {
                    models = merged
                    if (selectedModel.isBlank()) selectedModel = merged.first()
                }
                routerModels = rMods
                onDone(rMods.size)
            }
        }
    }

    fun refreshWindows(ctx: Context) {
        val c = client(ctx) ?: return
        io.execute {
            runCatching { c.desktopWindows() }.onSuccess { list -> post { desktopWindows = list } }
        }
    }
}

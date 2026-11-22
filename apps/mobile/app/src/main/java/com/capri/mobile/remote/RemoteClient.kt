package com.capri.mobile.remote

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RemoteHttpException(val code: Int, override val message: String) : RuntimeException(message)

class RemoteClient(
    baseEndpoint: String,
    val token: String,
    val deviceId: String,
) {
    val baseUrl: String = baseEndpoint.trim().trimEnd('/')

    private fun buildUrl(path: String, query: Map<String, String> = emptyMap()): URL {
        val sb = StringBuilder(baseUrl).append(path)
        if (query.isNotEmpty()) {
            sb.append('?')
            query.entries.forEachIndexed { idx, (k, v) ->
                if (idx > 0) sb.append('&')
                sb.append(URLEncoder.encode(k, "UTF-8"))
                sb.append('=')
                sb.append(URLEncoder.encode(v, "UTF-8"))
            }
        }
        return URL(sb.toString())
    }

    private fun open(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 15000,
    ): HttpURLConnection {
        val conn = buildUrl(path, query).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "CapriMobile/0.5.0")
        conn.setRequestProperty("X-Device-Id", deviceId)
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    private fun requestJson(
        method: String,
        path: String,
        payload: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 18000,
    ): JSONObject {
        val conn = open(method, path, query, readTimeoutMs = readTimeoutMs)
        try {
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            } else if (method == "POST" || method == "PUT" || method == "PATCH") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                    .ifBlank { body.take(180).ifBlank { "HTTP $code" } }
                throw RemoteHttpException(code, msg)
            }
            if (body.isBlank()) return JSONObject()
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun getBytes(path: String, query: Map<String, String> = emptyMap()): ByteArray {
        val conn = open("GET", path, query, connectTimeoutMs = 4000, readTimeoutMs = 8000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw RemoteHttpException(code, err.ifBlank { "HTTP $code" })
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    // -- Status, Auth & Devices -----------------------------------------------------------------------------------

    fun status(): JSONObject = requestJson("GET", "/api/status")

    fun pair(deviceName: String): JSONObject = requestJson(
        "POST",
        "/api/auth/pair",
        JSONObject().put("device_id", deviceId).put("device_name", deviceName),
        readTimeoutMs = 40000,
    )

    fun devices(): Pair<List<DeviceEntry>, List<DeviceEntry>> {
        val obj = try {
            requestJson("GET", "/api/devices")
        } catch (e: RemoteHttpException) {
            if (e.code == 404 || e.code == 405) requestJson("POST", "/api/devices", JSONObject())
            else throw e
        }
        fun parseList(arr: JSONArray?): List<DeviceEntry> = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val d = arr?.optJSONObject(i) ?: return@mapNotNull null
            DeviceEntry(
                deviceId = d.optString("device_id", ""),
                deviceName = d.optString("device_name", d.optString("device_id", "Device")),
                ip = d.optString("ip", ""),
                pairedAt = d.optString("paired_at", ""),
                lastSeen = d.optString("last_seen", ""),
                reason = d.optString("reason", ""),
            )
        }
        return parseList(obj.optJSONArray("trusted")) to parseList(obj.optJSONArray("banned"))
    }

    fun revokeDevice(targetDeviceId: String) {
        requestJson("POST", "/api/devices/revoke", JSONObject().put("device_id", targetDeviceId))
    }

    fun banDevice(targetDeviceId: String, name: String, reason: String = "Manual ban") {
        requestJson(
            "POST",
            "/api/devices/ban",
            JSONObject().put("device_id", targetDeviceId).put("device_name", name).put("reason", reason),
        )
    }

    fun unbanDevice(targetDeviceId: String) {
        requestJson("POST", "/api/devices/unban", JSONObject().put("device_id", targetDeviceId))
    }

    fun securityAudit(limit: Int = 40): List<SecurityAuditItem> {
        val arr = requestJson("GET", "/api/security/audit", query = mapOf("limit" to "$limit"))
            .optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            SecurityAuditItem(
                timestamp = e.optString("timestamp", ""),
                event = e.optString("event", ""),
                ipAddress = e.optString("ip_address", ""),
                method = e.optString("method", ""),
                path = e.optString("path", ""),
            )
        }
    }

    // -- Agents, Workspaces, Sessions & Streaming ----------------------------------------------------------------

    fun agents(): List<RemoteAgentInfo> {
        val arr = requestJson("GET", "/api/agents").optJSONArray("agents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            RemoteAgentInfo(
                id = a.optString("id", ""),
                name = a.optString("name", a.optString("id", "")),
                description = a.optString("description", ""),
                supportsStreaming = a.optBoolean("supports_streaming", true),
                installed = a.optBoolean("installed", false),
                command = a.optString("command", ""),
                detected = a.optBoolean("detected", a.optBoolean("installed", false)),
            )
        }.filter { it.id.isNotBlank() }
    }

    fun models(): List<String> {
        val arr = requestJson("GET", "/api/models").optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun skills(): List<Pair<String, String>> {
        val arr = requestJson("GET", "/api/skills").optJSONArray("skills") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val cmd = s.optString("command", "")
            val desc = s.optString("description", s.optString("name", ""))
            if (cmd.isNotBlank()) cmd to desc else null
        }
    }

    fun setPermission(mode: RemotePermissionMode) {
        requestJson("POST", "/api/permission", JSONObject().put("permission", mode.wire))
    }

    fun globalPersonalization(): String =
        requestJson("GET", "/api/personalization").optString("global", "")

    fun setGlobalPersonalization(value: String) {
        requestJson("POST", "/api/personalization", JSONObject().put("global", value))
    }

    fun workspaces(): List<PcWorkspace> {
        val arr = requestJson("GET", "/api/workspaces").optJSONArray("workspaces") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val w = arr.optJSONObject(i) ?: return@mapNotNull null
            PcWorkspace(
                id = w.optString("id", w.optString("path", "")),
                name = w.optString("name", ""),
                path = w.optString("path", ""),
                isActive = w.optBoolean("is_active", false),
            )
        }
    }

    fun selectWorkspace(path: String): PcWorkspace {
        val obj = requestJson("POST", "/api/workspace", JSONObject().put("path", path))
        val w = obj.optJSONObject("workspace") ?: obj
        return PcWorkspace(
            id = w.optString("id", path),
            name = w.optString("name", path.substringAfterLast('\\').substringAfterLast('/')),
            path = w.optString("path", path),
            isActive = true,
        )
    }

    fun browseFolders(path: String = ""): WorkspaceFolderListing {
        val query = if (path.isNotBlank()) mapOf("path" to path) else emptyMap()
        val obj = requestJson("GET", "/api/folders", query = query)
        val arr = obj.optJSONArray("folders") ?: JSONArray()
        val folders = (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val fp = f.optString("path", "")
            if (fp.isBlank()) null
            else PcWorkspace(fp, f.optString("name", fp), fp, false)
        }
        return WorkspaceFolderListing(
            path = obj.optString("path", ""),
            parent = obj.optString("parent", "").takeIf { it.isNotBlank() },
            folders = folders,
        )
    }

    fun pickDesktopFolder(): String =
        requestJson("POST", "/api/fs/pick_desktop", JSONObject(), readTimeoutMs = 60000)
            .optString("path", "")

    fun sessions(limit: Int = 50): List<RemoteSession> {
        val arr = requestJson("GET", "/api/sessions", query = mapOf("limit" to "$limit"))
            .optJSONArray("sessions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let(::decodeSession)
        }
    }

    fun createSession(workspaceName: String, modelName: String): RemoteSession {
        val obj = requestJson(
            "POST",
            "/api/sessions",
            JSONObject().put("workspace_name", workspaceName).put("model_name", modelName),
        )
        return decodeSession(obj.optJSONObject("session") ?: obj)
    }

    fun capriChat(message: String, history: JSONArray): String =
        requestJson(
            "POST",
            "/api/capri/chat",
            JSONObject().put("message", message).put("history", history),
            readTimeoutMs = 35000,
        ).getString("reply")

    fun loadSession(id: String): RemoteSession {
        val obj = requestJson("GET", "/api/sessions/$id")
        return decodeSession(obj.optJSONObject("session") ?: obj)
    }

    fun renameSession(id: String, title: String): RemoteSession {
        val obj = requestJson("PATCH", "/api/sessions/$id", JSONObject().put("title", title))
        return decodeSession(obj.optJSONObject("session") ?: obj)
    }

    fun setSessionPersonalization(id: String, overrideText: String?): RemoteSession {
        val body = JSONObject()
        if (overrideText == null) body.put("personalization_override", JSONObject.NULL)
        else body.put("personalization_override", overrideText)
        val obj = requestJson("PATCH", "/api/sessions/$id", body)
        return decodeSession(obj.optJSONObject("session") ?: obj)
    }

    fun deleteSession(id: String) {
        requestJson("DELETE", "/api/sessions/$id")
    }

    fun stopSession(id: String) {
        requestJson("POST", "/api/sessions/$id/stop", JSONObject())
    }

    fun respondToApproval(requestId: String, decision: String, sessionId: String = ""): JSONObject =
        requestJson(
            "POST",
            "/api/approvals/respond",
            JSONObject()
                .put("request_id", requestId)
                .put("decision", decision)
                .put("session_id", sessionId),
        )

    fun respondToClarification(requestId: String, answer: String): JSONObject =
        requestJson(
            "POST",
            "/api/clarifications/respond",
            JSONObject()
                .put("request_id", requestId)
                .put("answer", answer),
        )

    fun streamPrompt(
        sessionId: String,
        text: String,
        attachments: List<RemoteAttachmentDraft>,
        model: String,
        agents: List<String>,
        mode: RemoteExecutionMode,
        concurrency: Int,
        coordinator: String,
        permission: RemotePermissionMode,
        onEvent: (RemoteStreamEvent) -> Unit,
    ) {
        val attArr = JSONArray()
        for (a in attachments) {
            attArr.put(
                JSONObject()
                    .put("name", a.name)
                    .put("data", Base64.encodeToString(a.bytes, Base64.NO_WRAP)),
            )
        }
        val agentArr = JSONArray()
        agents.forEach { agentArr.put(it) }
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("text", text)
            .put("attachments", attArr)
            .put("model", model)
            .put("agents", agentArr)
            .put("mode", mode.wire)
            .put("concurrency", if (mode == RemoteExecutionMode.SINGLE) 1 else concurrency)
            .put("coordinator", coordinator)
            .put("permission", permission.wire)

        val conn = open("POST", "/api/prompts/stream", connectTimeoutMs = 10000, readTimeoutMs = 0)
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(err).optString("error") }.getOrDefault("")
                    .ifBlank { err.take(180).ifBlank { "HTTP $code" } }
                throw RemoteHttpException(code, msg)
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    parseStreamLine(line)?.let(onEvent)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // -- /debate --------------------------------------------------------------------------------------------------

    fun debateStatus(): JSONObject = requestJson("GET", "/api/debate/status")

    fun debateModels(): List<String> {
        val arr = requestJson("GET", "/api/debate/models").optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun startDebate(task: String): JSONObject =
        requestJson("POST", "/api/debate/start", JSONObject().put("task", task))

    fun stopDebate(): JSONObject =
        requestJson("POST", "/api/debate/stop", JSONObject())

    // -- Screen, Window Picker, Keyboard, Hotkeys, Controller, Voice & Spotify -----------------------------------

    fun screenInfo(): JSONObject = requestJson("GET", "/api/screen/info")

    fun screenFrame(
        resolution: String = "720p",
        quality: Int = 70,
        monitor: String = "primary",
    ): ByteArray = getBytes(
        "/api/screen/frame",
        mapOf("res" to resolution, "quality" to "$quality", "monitor" to monitor),
    )

    fun screenInput(
        action: String,
        x: Double = 0.0,
        y: Double = 0.0,
        button: String = "left",
        delta: Int = 0,
        key: String = "",
        text: String = "",
        monitor: String = "primary",
        pointer: String = "",
    ): JSONObject = requestJson(
        "POST",
        "/api/screen/input",
        JSONObject()
            .put("action", action)
            .put("x", x)
            .put("y", y)
            .put("button", button)
            .put("delta", delta)
            .put("key", key)
            .put("text", text)
            .put("monitor", monitor)
            .put("pointer", pointer),
        readTimeoutMs = 8000,
    )

    fun sendHotkey(key: String): JSONObject =
        requestJson("POST", "/api/screen/hotkey", JSONObject().put("key", key), readTimeoutMs = 8000)

    fun shortcuts(): List<Pair<String, String>> {
        val arr = requestJson("GET", "/api/shortcuts").optJSONArray("shortcuts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = obj.optString("label", "")
            val key = obj.optString("key", "")
            if (label.isNotBlank() && key.isNotBlank()) label to key else null
        }
    }

    fun saveShortcuts(items: List<Pair<String, String>>): List<Pair<String, String>> {
        val arr = JSONArray()
        items.forEach { (l, k) -> arr.put(JSONObject().put("label", l).put("key", k)) }
        val res = requestJson("POST", "/api/shortcuts", JSONObject().put("shortcuts", arr))
            .optJSONArray("shortcuts") ?: return items
        return (0 until res.length()).mapNotNull { i ->
            val o = res.optJSONObject(i) ?: return@mapNotNull null
            o.optString("label", "") to o.optString("key", "")
        }
    }

    fun controllerKey(key: String, state: String): JSONObject =
        requestJson("POST", "/api/controller/key", JSONObject().put("key", key).put("state", state), readTimeoutMs = 4000)

    fun releaseAllControllerKeys(): JSONObject =
        requestJson("POST", "/api/controller/release-all", JSONObject(), readTimeoutMs = 4000)

    fun desktopWindows(): List<DesktopWindowInfo> {
        val arr = requestJson("GET", "/api/desktop/windows").optJSONArray("running_windows") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val w = arr.optJSONObject(i) ?: return@mapNotNull null
            val hwnd = w.optLong("hwnd", 0L)
            if (hwnd <= 0L) return@mapNotNull null
            DesktopWindowInfo(
                hwnd = hwnd,
                title = w.optString("title", "Window"),
                process = w.optString("process", ""),
            )
        }.sortedWith(compareByDescending<DesktopWindowInfo> { it.isCapri }.thenBy { it.title.lowercase() })
    }

    fun focusDesktopWindow(hwnd: Long): JSONObject =
        requestJson("POST", "/api/desktop/windows/focus", JSONObject().put("hwnd", hwnd))

    fun voiceInput(text: String): JSONObject =
        requestJson("POST", "/api/voice/input", JSONObject().put("text", text).put("mode", "command"))

    fun spotifyControl(action: String): JSONObject =
        requestJson("POST", "/api/spotify/control", JSONObject().put("action", action))

    // -- Workspace Tree, Files & Git -----------------------------------------------------------------------------

    fun tree(relativePath: String = ""): List<WorkspaceTreeEntry> {
        val arr = requestJson("GET", "/api/tree", query = mapOf("path" to relativePath))
            .optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            WorkspaceTreeEntry(
                name = e.optString("name", ""),
                path = e.optString("path", ""),
                isDirectory = e.optString("kind", "") == "directory",
            )
        }
    }

    fun readFile(relativePath: String): WorkspaceFileDoc {
        val obj = requestJson("GET", "/api/file", query = mapOf("path" to relativePath))
        val f = obj.optJSONObject("file") ?: obj
        return decodeFileDoc(f)
    }

    fun saveFile(relativePath: String, content: String, baseHash: String): WorkspaceFileDoc {
        val obj = requestJson(
            "PUT",
            "/api/file",
            JSONObject().put("path", relativePath).put("content", content).put("base_hash", baseHash),
        )
        val f = obj.optJSONObject("file") ?: obj
        return decodeFileDoc(f)
    }

    fun gitStatus(fetch: Boolean = false): GitStatusSnapshot {
        val d = requestJson("GET", "/api/git-status", query = mapOf("fetch" to if (fetch) "1" else "0"), readTimeoutMs = 30000)
        fun commits(key: String): List<GitCommitItem> {
            val arr = d.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                GitCommitItem(c.optString("hash", ""), c.optString("subject", ""), c.optString("author", ""))
            }
        }
        val filesArr = d.optJSONArray("files") ?: JSONArray()
        val files = (0 until filesArr.length()).mapNotNull { i ->
            val f = filesArr.optJSONObject(i) ?: return@mapNotNull null
            GitFileChange(
                path = f.optString("path", ""),
                status = f.optString("status", "modified"),
                staged = f.optBoolean("staged", false),
            )
        }
        val nestedArr = d.optJSONArray("nested_repositories") ?: JSONArray()
        val nested = (0 until nestedArr.length()).mapNotNull { i ->
            val r = nestedArr.optJSONObject(i) ?: return@mapNotNull null
            val rp = r.optString("path", "")
            if (rp.isBlank()) null else GitNestedRepo(r.optString("name", rp), rp)
        }
        return GitStatusSnapshot(
            isGitRepo = d.optBoolean("is_git_repo", false),
            branch = d.optString("branch", ""),
            remoteUrl = d.optString("remote_url", ""),
            upstream = d.optString("upstream", ""),
            ahead = d.optInt("ahead", 0),
            behind = d.optInt("behind", 0),
            additions = d.optInt("additions", 0),
            deletions = d.optInt("deletions", 0),
            files = files,
            incoming = commits("incoming"),
            outgoing = commits("outgoing"),
            nestedRepos = nested,
        )
    }

    // -- Tasks, Usage & Smart Router -----------------------------------------------------------------------------

    fun tasks(): List<RemoteTask> {
        val arr = requestJson("GET", "/api/tasks").optJSONArray("tasks") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val agArr = t.optJSONArray("agents") ?: JSONArray()
            val agents = (0 until agArr.length()).mapNotNull { agArr.optString(it).takeIf(String::isNotBlank) }
            val stArr = t.optJSONArray("agentStates") ?: JSONArray()
            val states = (0 until stArr.length()).mapNotNull { j ->
                val s = stArr.optJSONObject(j) ?: return@mapNotNull null
                RemoteTaskAgentState(
                    id = s.optString("id", ""),
                    name = s.optString("name", s.optString("id", "Agent")),
                    status = s.optString("status", "unknown"),
                    phase = s.optString("phase", ""),
                    detail = s.optString("detail", ""),
                    elapsedSeconds = s.optInt("elapsedSeconds", 0),
                )
            }
            RemoteTask(
                id = t.optString("id", ""),
                sessionId = t.optString("sessionId", t.optString("session_id", "")),
                title = t.optString("title", "Task"),
                status = t.optString("status", "unknown"),
                phase = t.optString("phase", ""),
                detail = t.optString("detail", ""),
                agents = agents,
                agentStates = states,
                updatedAt = t.optString("updatedAt", t.optString("updated_at", "")),
            )
        }
    }

    fun providerUsage(range: String = "24h", scope: String = "all"): ProviderUsageData {
        val d = requestJson("GET", "/api/provider-usage", query = mapOf("range" to range, "scope" to scope, "limit" to "50"))
        val sum = d.optJSONObject("summary") ?: JSONObject()
        fun strList(arr: JSONArray?): List<String> =
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf(String::isNotBlank) }
        val qaArr = d.optJSONArray("quota_accounts") ?: JSONArray()
        val accounts = (0 until qaArr.length()).mapNotNull { i ->
            val a = qaArr.optJSONObject(i) ?: return@mapNotNull null
            val qArr = a.optJSONArray("quotas") ?: JSONArray()
            val windows = (0 until qArr.length()).mapNotNull { j ->
                val q = qArr.optJSONObject(j) ?: return@mapNotNull null
                val used = if (q.isNull("used_percent")) null else q.optDouble("used_percent")
                val rem = if (q.isNull("remaining_percent")) null else q.optDouble("remaining_percent")
                QuotaWindow(
                    id = q.optString("id", ""),
                    label = q.optString("label", "Quota"),
                    usedPercent = used?.takeIf { !it.isNaN() },
                    remainingPercent = rem?.takeIf { !it.isNaN() },
                    resetAt = q.optString("reset_at", ""),
                )
            }
            QuotaAccount(
                id = a.optString("id", ""),
                provider = a.optString("provider", "unknown"),
                name = a.optString("name", "Account"),
                active = a.optBoolean("active", false),
                status = a.optString("status", ""),
                plan = a.optString("plan", ""),
                model = a.optString("model", ""),
                limitReached = a.optBoolean("limit_reached", false),
                error = a.optString("error", ""),
                quotas = windows,
            )
        }
        val recArr = d.optJSONArray("recent") ?: JSONArray()
        val recent = (0 until recArr.length()).mapNotNull { i ->
            val r = recArr.optJSONObject(i) ?: return@mapNotNull null
            ProviderUsageEntry(
                timestamp = r.optString("timestamp", ""),
                provider = r.optString("provider", ""),
                model = r.optString("model", ""),
                totalTokens = r.optLong("total_tokens", r.optLong("input_tokens", 0L) + r.optLong("output_tokens", 0L)),
                cost = r.optDouble("cost", 0.0),
                account = r.optString("account", ""),
            )
        }
        return ProviderUsageData(
            available = d.optBoolean("available", false),
            source = d.optString("source", "9router"),
            range = d.optString("range", range),
            requests = sum.optInt("requests", 0),
            inputTokens = sum.optLong("input_tokens", 0L),
            outputTokens = sum.optLong("output_tokens", 0L),
            cachedTokens = sum.optLong("cached_tokens", 0L),
            estimatedCost = sum.optDouble("estimated_cost", 0.0),
            providers = strList(d.optJSONArray("providers")),
            models = strList(d.optJSONArray("models")),
            quotaAccounts = accounts,
            recent = recent,
            reason = d.optString("reason", ""),
        )
    }

    fun routerStatus(): JSONObject = requestJson("GET", "/api/router/status")

    fun routerModels(): List<String> {
        val arr = requestJson("GET", "/api/router/models").optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun routerConfig(): SmartRouterConfig {
        val c = requestJson("GET", "/api/router/config")
        return SmartRouterConfig(
            enabled = c.optBoolean("enabled", true),
            mode = c.optString("mode", "auto"),
            simpleModel = c.optString("simple_model", "ag/gemini-3.8-flash-high"),
            complexModel = c.optString("complex_model", "ag/claude-opus-4-6-thinking"),
            complexityThreshold = c.optInt("complexity_threshold", 35),
            listenPort = c.optInt("listen_port", 20130),
            upstreamUrl = c.optString("upstream_url", "http://localhost:20128/v1"),
        )
    }

    fun saveRouterConfig(cfg: SmartRouterConfig) {
        requestJson(
            "POST",
            "/api/router/config",
            JSONObject()
                .put("enabled", cfg.enabled)
                .put("mode", cfg.mode)
                .put("simple_model", cfg.simpleModel)
                .put("complex_model", cfg.complexModel)
                .put("complexity_threshold", cfg.complexityThreshold),
        )
    }

    private fun decodeSession(s: JSONObject): RemoteSession {
        val msgArr = s.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until msgArr.length()).mapNotNull { i ->
            val m = msgArr.optJSONObject(i) ?: return@mapNotNull null
            val content = m.optString("content", m.optString("text", ""))
            RemoteMessage(
                id = m.optString("id", "$i"),
                role = m.optString("role", "assistant"),
                text = content,
                timestamp = m.optString("createdAt", m.optString("timestamp", "")),
                agentId = m.optString("agentId", ""),
            )
        }
        val overrideVal = if (s.isNull("personalizationOverride")) null
        else s.optString("personalizationOverride", "").takeIf { it.isNotBlank() }
        return RemoteSession(
            id = s.optString("id", ""),
            title = s.optString("title", "New task"),
            workspace = s.optString("workspacePath", s.optString("workspace", "")),
            workspaceName = s.optString("workspaceName", ""),
            modelName = s.optString("activeModelName", ""),
            updatedAt = s.optString("updatedAt", ""),
            personalizationOverride = overrideVal,
            messages = msgs,
        )
    }

    private fun decodeFileDoc(f: JSONObject): WorkspaceFileDoc = WorkspaceFileDoc(
        path = f.optString("path", ""),
        name = f.optString("name", ""),
        content = f.optString("content", ""),
        diff = f.optString("diff", ""),
        hash = f.optString("hash", ""),
        size = f.optInt("size", 0),
        lineCount = f.optInt("line_count", 0),
        editable = f.optBoolean("editable", false),
        gitStatus = f.optString("git_status", ""),
    )
}

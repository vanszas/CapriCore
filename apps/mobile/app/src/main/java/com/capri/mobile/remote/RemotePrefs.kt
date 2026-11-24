package com.capri.mobile.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class RemotePrefs(context: Context) {
    private val sp = context.getSharedPreferences("capri_remote", Context.MODE_PRIVATE)
    private val secrets = context.getSharedPreferences("remote_secrets", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            val existing = sp.getString("device_id", "")?.trim().orEmpty()
            if (existing.isNotEmpty()) return existing
            val rand = ByteArray(6)
            SecureRandom().nextBytes(rand)
            val hex = rand.joinToString("") { "%02x".format(it) }
            val created = "capri-android-$hex"
            sp.edit().putString("device_id", created).apply()
            return created
        }

    var activeProfileId: String
        get() = sp.getString("active_profile_id", "") ?: ""
        set(v) = sp.edit().putString("active_profile_id", v).apply()

    var backgroundMonitor: Boolean
        get() = sp.getBoolean("bg_monitor", false)
        set(v) = sp.edit().putBoolean("bg_monitor", v).apply()

    var zeroAvatarReasoning: Boolean
        get() = sp.getBoolean("zero_avatar_reasoning", true)
        set(v) = sp.edit().putBoolean("zero_avatar_reasoning", v).apply()

    var notificationSoundUri: String
        get() = sp.getString("notif_sound_uri", "") ?: ""
        set(v) = sp.edit().putString("notif_sound_uri", v).apply()

    var notificationSoundLabel: String
        get() = sp.getString("notif_sound_label", "") ?: ""
        set(v) = sp.edit().putString("notif_sound_label", v).apply()

    fun profiles(): List<PcProfile> {
        val raw = sp.getString("profiles", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PcProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun activeProfile(): PcProfile? {
        val list = profiles()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.id == activeProfileId } ?: list.first()
    }

    fun saveProfile(profile: PcProfile, token: String) {
        val list = profiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(0, profile)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit()
            .putString("profiles", arr.toString())
            .putString("active_profile_id", profile.id)
            .apply()
        if (token.isNotBlank()) {
            secrets.edit().putString("tok_${profile.id}", token.trim()).apply()
        }
    }

    fun updateProfile(profile: PcProfile) {
        val list = profiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            list[idx] = profile
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("profiles", arr.toString()).apply()
        }
    }

    fun deleteProfile(id: String) {
        val list = profiles().filterNot { it.id == id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("profiles", arr.toString()).apply()
        secrets.edit().remove("tok_$id").remove("audio_tok_$id").remove("screen_chat_$id").apply()
        if (activeProfileId == id) {
            activeProfileId = list.firstOrNull()?.id.orEmpty()
        }
    }

    fun tokenFor(profileId: String): String =
        secrets.getString("tok_$profileId", "") ?: ""

    fun hostAudioToken(profileId: String): String =
        secrets.getString("audio_tok_$profileId", "").orEmpty()

    fun screenChat(profileId: String): List<Pair<String, String>> = runCatching {
        val data = JSONArray(secrets.getString("screen_chat_$profileId", "[]"))
        (0 until data.length()).map { index ->
            val turn = data.getJSONObject(index)
            turn.getString("u") to turn.getString("a")
        }
    }.getOrDefault(emptyList())

    fun saveScreenChat(profileId: String, messages: List<Pair<String, String>>) {
        val data = JSONArray()
        messages.takeLast(40).forEach { (user, reply) ->
            data.put(JSONObject().put("u", user).put("a", reply))
        }
        secrets.edit().putString("screen_chat_$profileId", data.toString()).apply()
    }

    fun saveHostAudioToken(profileId: String, token: String) {
        val edit = secrets.edit()
        if (token.isEmpty()) edit.remove("audio_tok_$profileId")
        else edit.putString("audio_tok_$profileId", token)
        edit.apply()
    }

    fun activeToken(): String {
        val p = activeProfile() ?: return ""
        return tokenFor(p.id)
    }

    fun customShortcuts(): List<Pair<String, String>> {
        val raw = sp.getString("shortcuts", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = obj.optString("label", "")
                val key = obj.optString("key", "")
                if (label.isNotBlank() && key.isNotBlank()) label to key else null
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomShortcuts(items: List<Pair<String, String>>) {
        val arr = JSONArray()
        items.forEach { (l, k) -> arr.put(JSONObject().put("label", l).put("key", k)) }
        sp.edit().putString("shortcuts", arr.toString()).apply()
    }
}

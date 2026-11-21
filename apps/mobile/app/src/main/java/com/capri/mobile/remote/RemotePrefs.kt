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
        secrets.edit().remove("tok_$id").apply()
        if (activeProfileId == id) {
            activeProfileId = list.firstOrNull()?.id.orEmpty()
        }
    }

    fun tokenFor(profileId: String): String =
        secrets.getString("tok_$profileId", "") ?: ""

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

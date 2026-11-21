package com.capri.mobile

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.capri.mobile.remote.RemotePrefs
import com.capri.mobile.remote.RemoteState
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Capri's spoken voice on Android: streams Capri's PC voice via /api/voice/tts when paired & online,
 * and falls back seamlessly to the device's TextToSpeech engine.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var player: MediaPlayer? = null
    private var ready = false
    private var pending: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        ready = true
        applyLocale()
        tts?.setPitch(1.10f)
        tts?.setSpeechRate(1.04f)
        pending?.let {
            pending = null
            speakNow(it)
        }
    }

    private fun applyLocale() {
        val code = Lang.code
        val locale = when (code) {
            "id" -> Locale("id", "ID")
            "pt" -> Locale("pt", "BR")
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.forLanguageTag(code)
        }
        val res = tts?.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.US)
        }
    }

    fun speak(raw: String) {
        val clean = cleanForSpeech(raw)
        if (clean.isEmpty()) return
        val prof = RemoteState.activeProfile
        if (RemoteState.connected && prof != null && prof.activeEndpoint().isNotBlank()) {
            val endpoint = prof.activeEndpoint().trimEnd('/')
            val token = RemotePrefs(app).tokenFor(prof.id)
            io.execute {
                if (!tryRemoteTts(endpoint, token, clean)) {
                    speakLocal(clean)
                }
            }
            return
        }
        speakLocal(clean)
    }

    private fun speakLocal(clean: String) {
        if (!ready) {
            pending = clean
            return
        }
        speakNow(clean)
    }

    private fun tryRemoteTts(endpoint: String, token: String, text: String): Boolean {
        return runCatching {
            val conn = (URL("$endpoint/api/voice/tts").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val payload = JSONObject()
                    .put("text", text)
                    .put("persona", "assistant")
                    .toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) return false
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 256) return false
                val ext = if ((conn.contentType ?: "").contains("wav", ignoreCase = true)) "wav" else "mp3"
                val tmp = File(app.cacheDir, "capri_tts_reply.$ext")
                tmp.writeBytes(bytes)
                playAudioFile(tmp)
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun playAudioFile(file: File) {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { it.release(); if (player === it) player = null }
        mp.prepare()
        mp.start()
    }

    private fun speakNow(text: String) {
        applyLocale()
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "capri-${System.nanoTime()}")
    }

    fun stop() {
        pending = null
        runCatching { player?.stop() }
        runCatching { tts?.stop() }
    }

    fun release() {
        pending = null
        ready = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }

    companion object {
        internal fun cleanForSpeech(raw: String): String {
            var s = raw
                .replace(Regex("""\[ACTION:[^\]]+\]""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""https?://\S+"""), " ")
                .replace(Regex("""[*_`~#>]"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (s == "..." || s == "…") return ""
            if (s.length > 420) s = s.take(420)
            return s
        }
    }
}

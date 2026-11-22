package com.capri.mobile

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
class Speaker(context: Context, private val onError: (() -> Unit)? = null) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var player: MediaPlayer? = null
    private var ready = false
    private var initFailed = false
    private var generation = 0
    private var pending: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            initFailed = true
            if (pending != null) onError?.invoke()
            pending = null
            return
        }
        ready = true
        applyLocale()
        tts?.setPitch(1.10f)
        tts?.setSpeechRate(1.04f)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Required by Android's TTS callback")
            override fun onError(utteranceId: String?) {
                main.post { if (utteranceId == "capri-$generation") this@Speaker.onError?.invoke() }
            }
        })
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
        stop()
        val request = generation
        val prof = RemoteState.activeProfile
        if (RemoteState.connected && prof != null && prof.activeEndpoint().isNotBlank()) {
            val endpoint = prof.activeEndpoint().trimEnd('/')
            val prefs = RemotePrefs(app)
            val token = prefs.tokenFor(prof.id)
            io.execute {
                val file = remoteAudioFile(endpoint, token, prefs.deviceId, clean)
                main.post {
                    if (request != generation) {
                        file?.delete()
                    } else if (file == null || !runCatching { playAudioFile(file, clean) }.getOrDefault(false)) {
                        file?.delete()
                        speakLocal(clean)
                    }
                }
            }
            return
        }
        speakLocal(clean)
    }

    private fun speakLocal(clean: String) {
        if (initFailed) {
            onError?.invoke()
            return
        }
        if (!ready) {
            pending = clean
            return
        }
        speakNow(clean)
    }

    private fun remoteAudioFile(endpoint: String, token: String, deviceId: String, text: String): File? {
        return runCatching {
            val conn = (URL("$endpoint/api/voice/tts").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Id", deviceId)
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val payload = JSONObject()
                    .put("text", text)
                    .put("persona", "assistant")
                    .toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 256) return null
                val ext = if ((conn.contentType ?: "").contains("wav", ignoreCase = true)) "wav" else "mp3"
                File.createTempFile("capri_tts_reply", ".$ext", app.cacheDir).apply { writeBytes(bytes) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun playAudioFile(file: File, fallbackText: String): Boolean {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null }
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed) {
                    player = null
                    failed.release()
                    speakLocal(fallbackText)
                }
                true
            }
            mp.prepare()
            mp.start()
            return true
        } catch (failure: Exception) {
            mp.release()
            player = null
            return false
        } finally {
            file.delete()
        }
    }

    private fun speakNow(text: String) {
        applyLocale()
        val params = Bundle()
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "capri-$generation") != TextToSpeech.SUCCESS)
            onError?.invoke()
    }

    fun stop() {
        generation++
        pending = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tts?.stop() }
    }

    fun release() {
        stop()
        io.shutdownNow()
        ready = false
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

package com.capri.mobile.remote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal suspend fun playHostAudio(
    client: RemoteClient,
    prefs: RemotePrefs,
    profileId: String,
    onApproval: () -> Unit,
    onReady: () -> Unit,
) = withContext(Dispatchers.IO) {
    val saved = prefs.hostAudioToken(profileId)
    val audio = if (saved.isNotEmpty()) RemoteClient(client.baseUrl, saved, client.deviceId) else {
        withContext(Dispatchers.Main) { onApproval() }
        client.approveHostAudio().also { prefs.saveHostAudioToken(profileId, it.token) }
    }
    val sampleRate = 16000
    val format = AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .build()
    val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    check(minimum > 0) { "Android tidak mendukung output audio host" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        .setAudioFormat(format)
        .setBufferSizeInBytes(maxOf(minimum, sampleRate * Float.SIZE_BYTES / 5))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        check(track.state == AudioTrack.STATE_INITIALIZED) { "Output audio Android gagal dibuka" }
        track.play()
        var after = 0L
        var ready = false
        while (isActive) {
            val response = audio.hostAudio(after)
            if (!ready) {
                ready = true
                withContext(Dispatchers.Main) { onReady() }
            }
            val packets = response.optJSONArray("packets")
            if (packets != null) {
                for (i in 0 until packets.length()) {
                    if (!isActive) break
                    val packet = packets.getJSONObject(i)
                    val raw = Base64.decode(packet.getString("pcmBase64"), Base64.DEFAULT)
                    val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val samples = FloatArray(floats.remaining())
                    floats.get(samples)
                    if (samples.isNotEmpty()) {
                        check(track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) >= 0) {
                            "Output audio Android terputus"
                        }
                    }
                }
            }
            after = response.optLong("seq", after)
            delay(120)
        }
    } catch (denied: RemoteHttpException) {
        if (denied.code !in listOf(401, 403)) throw denied
        prefs.saveHostAudioToken(profileId, "")
        throw IllegalStateException("Izin suara tidak berlaku. Tekan Suara PC lagi untuk meminta izin.", denied)
    } finally {
        track.release()
    }
}

package com.capri.mobile.remote

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

internal data class RemoteVideoSource(
    val endpoint: String,
    val token: String,
    val device: String,
    val monitor: String,
    val fps: Int,
    val mbps: Int,
)

@Composable
internal fun RemoteVideo(source: RemoteVideoSource, modifier: Modifier, onSize: (Float) -> Unit, onError: () -> Unit) {
    val failure by rememberUpdatedState(onError)
    val sizeChanged by rememberUpdatedState(onSize)
    var rendering by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        delay(8_000)
        if (!rendering) failure()
    }
    androidx.compose.runtime.key(source) {
        AndroidView(modifier = modifier, factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var player: MediaPlayer? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val media = MediaPlayer()
                        player = media
                        try {
                            val surface = Surface(texture)
                            try { media.setSurface(surface) } finally { surface.release() }
                            val uri = Uri.parse(source.endpoint + "/api/screen/video").buildUpon()
                                .appendQueryParameter("monitor", source.monitor)
                                .appendQueryParameter("fps", source.fps.toString())
                                .appendQueryParameter("mbps", source.mbps.toString()).build()
                            media.setDataSource(context, uri, mapOf(
                                "Authorization" to "Bearer ${source.token}",
                                "X-Device-Id" to source.device,
                            ))
                            media.setOnVideoSizeChangedListener { _, w, h ->
                                if (player === media && w > 0 && h > 0) sizeChanged(w.toFloat() / h)
                            }
                            media.setOnInfoListener { _, what, _ ->
                                if (player === media && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true
                                false
                            }
                            media.setOnPreparedListener { if (player === media) it.start() }
                            media.setOnErrorListener { _, _, _ ->
                                if (player === media) failure()
                                true
                            }
                            media.setOnCompletionListener { if (player === media) failure() }
                            media.prepareAsync()
                        } catch (_: Exception) {
                            player = null
                            media.release()
                            failure()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { rendering = true }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        val previous = player
                        player = null
                        previous?.release()
                        return true
                    }
                }
            }
        })
    }
}

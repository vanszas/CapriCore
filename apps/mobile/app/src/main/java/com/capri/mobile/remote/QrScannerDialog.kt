package com.capri.mobile.remote

import android.Manifest
import com.capri.mobile.CYAN
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.capri.mobile.BG
import com.capri.mobile.CapriButton
import com.capri.mobile.EDGE
import com.capri.mobile.GREEN
import com.capri.mobile.INK
import com.capri.mobile.Label
import com.capri.mobile.MONO
import com.capri.mobile.MUTED
import com.capri.mobile.PANEL
import com.capri.mobile.PINK
import com.capri.mobile.SANS
import com.capri.mobile.Segments
import com.capri.mobile.capriField
import com.capri.mobile.chamfer
import com.capri.mobile.tr
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
fun PairPcDialog(
    onDismiss: () -> Unit,
    onPaired: () -> Unit,
) {
    val ctx = LocalContext.current
    var mode by remember { mutableIntStateOf(0) } // 0 = QR Camera/Image, 1 = Manual / Paste link
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var name by remember { mutableStateOf("Capri PC") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        if (!it) error = tr("Camera permission denied. Pick a QR image or enter details manually.")
    }

    fun applyPayload(raw: String) {
        val parsed = PairingPayload.parse(raw)
        if (parsed == null) {
            error = tr("Not a valid Capri / Agent Remote pairing QR or link.")
            return
        }
        name = parsed.name
        endpoint = parsed.endpoint
        token = parsed.token
        busy = true
        error = ""
        RemoteState.pairAndSave(ctx, name, endpoint, token) { err ->
            busy = false
            if (err != null) error = err else onPaired()
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.let { bmp ->
            val decoded = decodeQrFromBitmap(bmp)
            if (decoded != null) applyPayload(decoded)
            else error = tr("No QR code found in the selected image.")
        } ?: run { error = tr("Could not read that image.") }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.93f)
                .background(PANEL, chamfer(14.dp))
                .border(1.dp, EDGE, chamfer(14.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(tr("Pair PC")) }
                CapriButton(tr("Close"), quiet = true, color = MUTED) { onDismiss() }
            }
            Segments(listOf(tr("Scan QR Code"), tr("Manual Connect")), mode) { mode = it }

            if (mode == 0) {
                if (hasCamera) {
                    Text(
                        tr("Point the camera at the Pairing QR inside Capri Desktop Settings → Mobile Remote."),
                        color = MUTED, fontFamily = SANS, fontSize = 12.5.sp, lineHeight = 17.sp,
                    )
                    CameraQrPreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(chamfer(10.dp))
                            .border(1.dp, PINK, chamfer(10.dp)),
                        enabled = !busy,
                        onQrDecoded = { raw -> applyPayload(raw) },
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(BG, chamfer(10.dp))
                            .border(1.dp, EDGE, chamfer(10.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            tr("Allow camera access to scan the desktop QR code live."),
                            color = INK, fontFamily = SANS, fontSize = 13.sp,
                        )
                        CapriButton(tr("Allow Camera"), Modifier.fillMaxWidth()) {
                            camPerm.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
                CapriButton(tr("Pick QR Image"), Modifier.fillMaxWidth(), quiet = true, enabled = !busy) {
                    galleryPicker.launch("image/*")
                }
            } else {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = {
                        pasteText = it
                        PairingPayload.parse(it)?.let { p ->
                            name = p.name
                            endpoint = p.endpoint
                            token = p.token
                            error = ""
                        }
                    },
                    label = { Text(tr("Paste agentremote://pair?... link (optional)")) },
                    singleLine = true,
                    colors = capriField(),
                    shape = chamfer(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("PC Name")) },
                    singleLine = true,
                    colors = capriField(),
                    shape = chamfer(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text(tr("PC URL / Tailscale IP (e.g. 100.x.x.x or http://192.168.1.10:9120)")) },
                    singleLine = true,
                    colors = capriField(),
                    shape = chamfer(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(tr("Pairing Token")) },
                    singleLine = true,
                    colors = capriField(),
                    shape = chamfer(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                CapriButton(
                    label = if (busy) tr("Connecting...") else tr("Connect"),
                    modifier = Modifier.fillMaxWidth(),
                    color = GREEN,
                    enabled = !busy && endpoint.isNotBlank() && token.isNotBlank(),
                ) {
                    busy = true
                    error = ""
                    RemoteState.pairAndSave(ctx, name, endpoint, token) { err ->
                        busy = false
                        if (err != null) error = err else onPaired()
                    }
                }
            }
            if (busy) Text(tr("Pairing with PC..."), color = GREEN, fontFamily = MONO, fontSize = 12.sp)
            if (error.isNotEmpty()) Text(error, color = PINK, fontFamily = SANS, fontSize = 12.5.sp)
            if (RemoteState.connectionLogs.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(BG, chamfer(8.dp))
                        .border(1.dp, EDGE, chamfer(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("Connection Log:", color = CYAN, fontFamily = MONO, fontSize = 10.5.sp)
                    for (line in RemoteState.connectionLogs.takeLast(6)) {
                        Text(line, color = MUTED, fontFamily = MONO, fontSize = 10.sp, lineHeight = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun CameraQrPreview(
    modifier: Modifier,
    enabled: Boolean,
    onQrDecoded: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }
    var fired by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdownNow() }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewCtx ->
            val previewView = PreviewView(viewCtx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val future = ProcessCameraProvider.getInstance(viewCtx)
            future.addListener({
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    if (enabled && !fired) {
                        val decoded = decodeQrFromImageProxy(reader, image)
                        if (decoded != null) {
                            fired = true
                            ContextCompat.getMainExecutor(ctx).execute { onQrDecoded(decoded) }
                        }
                    }
                    image.close()
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(viewCtx))
            previewView
        },
    )
}

private fun decodeQrFromImageProxy(reader: MultiFormatReader, image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val w = image.width
    val h = image.height
    val rowStride = plane.rowStride
    val buf = plane.buffer
    val data = ByteArray(w * h)
    if (rowStride == w) {
        buf.get(data, 0, w * h)
    } else {
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(data, y * w, w)
        }
    }
    return runCatching {
        val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also { reader.reset() }
}

private fun decodeQrFromBitmap(bmp: Bitmap): String? {
    val w = bmp.width
    val h = bmp.height
    val pixels = IntArray(w * h)
    bmp.getPixels(pixels, 0, w, 0, 0, w, h)
    val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    return runCatching {
        val source = RGBLuminanceSource(w, h, pixels)
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}

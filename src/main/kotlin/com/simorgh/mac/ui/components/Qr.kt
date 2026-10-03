package com.simorgh.mac.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tag present once the QR has been drawn. */
const val QR_READY_TAG = "qr_ready"

/** Encodes [text] into a boolean module grid (true = dark). */
fun encodeQr(text: String): Array<BooleanArray>? = runCatching {
    val matrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
}.getOrNull()

/**
 * A scannable QR code: always black on white (scanners expect it), on a
 * rounded white plate so it reads as an object in dark themes too. Desktop
 * draws the modules as Canvas rectangles — crisp at any scale, no bitmap.
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val modules by produceState<Array<BooleanArray>?>(null, text) {
        value = withContext(Dispatchers.Default) { encodeQr(text) }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(12.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val m = modules
        if (m != null) {
            val rows = m.size
            val cols = m.first().size
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = QR_READY_TAG }) {
                val w = size.width / cols
                val h = size.height / rows
                for (y in 0 until rows) {
                    for (x in 0 until cols) {
                        if (m[y][x]) {
                            drawRect(Color.Black, androidx.compose.ui.geometry.Offset(x * w, y * h), Size(w + 0.5f, h + 0.5f))
                        }
                    }
                }
            }
        } else {
            CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp)
        }
    }
}

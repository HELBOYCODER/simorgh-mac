package com.simorgh.mac.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import com.simorgh.mac.str.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simorgh.mac.R
import com.simorgh.mac.ui.theme.ZeroTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.imageio.ImageIO

/**
 * The desktop counterpart of the Android camera scanner (no camera in this
 * app): pick an image file containing a QR code — a screenshot of the phone's
 * share sheet works — and decode it. Also decodes the clipboard image when
 * nothing is picked? No: file and clipboard text are both handled by the
 * import sheet; this sheet is image files only.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = ZeroTheme.colors
    val scope = rememberCoroutineScope()
    var decoding by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                stringResource(R.string.scan_from_photo),
                onClick = {
                    val file = pickImageFile() ?: return@PrimaryButton
                    decoding = true
                    notFound = false
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { decodeQrImage(file) }
                        decoding = false
                        if (text.isNullOrBlank()) notFound = true else onResult(text)
                    }
                },
            )
            TonalButton(stringResource(R.string.update_later), onCancel)
        }
        if (decoding) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 3.dp)
        }
        if (notFound) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.scan_not_found),
                style = MaterialTheme.typography.bodySmall,
                color = c.err,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.err.copy(alpha = 0.08f)).padding(10.dp),
            )
        }
    }
}

private fun pickImageFile(): File? {
    val dialog = FileDialog(null as Frame?, "QR image", FileDialog.LOAD)
    dialog.filenameFilter = java.io.FilenameFilter { _, name ->
        name.lowercase().endsWith(".png") || name.lowercase().endsWith(".jpg") ||
            name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".webp")
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

/** zxing decode of one image file — the desktop half of the Android QrScanner. */
fun decodeQrImage(file: File): String? = runCatching {
    val image = ImageIO.read(file) ?: return@runCatching null
    val pixels = IntArray(image.width * image.height)
    image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    val source = RGBLuminanceSource(image.width, image.height, pixels)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    QRCodeReader().decode(bitmap).text
}.getOrNull()

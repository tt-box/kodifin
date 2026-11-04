package io.github.jmorille.zapette.ui.transfer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Matrice du QR code (ZXing core, pur Java, hors ligne). Correction d'erreur M : bon compromis
 * entre densité et tolérance aux reflets d'un écran. Une marge de 2 modules suffit : le
 * composable ajoute un fond blanc autour.
 */
fun qrMatrix(payload: String): BitMatrix = QRCodeWriter().encode(
    payload,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 2,
    ),
)

/** Pixels ARGB (un pixel par module) de la matrice : noir sur blanc. */
fun BitMatrix.toArgb(): IntArray = IntArray(width * height) { i ->
    if (get(i % width, i / width)) BLACK else WHITE
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** QR code agrandi sans lissage (modules nets), toujours noir sur fond blanc, même en thème sombre. */
@Composable
fun QrCodeImage(payload: String, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 280.dp) {
    val bitmap = remember(payload) {
        val matrix = qrMatrix(payload)
        Bitmap.createBitmap(matrix.toArgb(), matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        modifier = modifier.background(Color.White).padding(8.dp).size(size),
    )
}

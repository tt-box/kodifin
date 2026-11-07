package io.github.ttbox.kodifin.ui.remote

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Décodeur Android : lit d'abord les dimensions puis sous-échantillonne (inSampleSize). */
object BitmapImageDecoder : ImageDecoder<ImageBitmap> {
    override fun decode(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, targetWidthPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    override fun sizeOf(image: ImageBitmap): Int = image.width * image.height * 4

    /** Plus grande puissance de 2 qui garde une largeur au moins égale à la cible. */
    internal fun sampleSize(width: Int, target: Int): Int {
        var sample = 1
        while (target > 0 && width / (sample * 2) >= target) sample *= 2
        return sample
    }
}

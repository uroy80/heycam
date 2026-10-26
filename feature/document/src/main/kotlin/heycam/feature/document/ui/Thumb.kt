package heycam.feature.document.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded thumbnails; the key carries the file's mtime, so a re-rendered thumb.jpg is never stale. */
private object ThumbCache : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 32).toInt().coerceAtLeast(1)) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

private fun decodeThumb(path: String, targetPx: Int): ImageBitmap? {
    val file = File(path)
    if (!file.isFile) return null
    val key = "$path#${file.lastModified()}#$targetPx"
    ThumbCache.get(key)?.let { return it.asImageBitmap() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    ThumbCache.put(key, bitmap)
    return bitmap.asImageBitmap()
}

/**
 * A page thumbnail filling its box on the sheet colour; a LoadBar while the page is still rendering.
 * [stamp] changes when the page is re-rendered (its edits version), so the image is re-read.
 */
@Composable
internal fun PageThumb(path: String?, stamp: Any?, targetPx: Int, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, path, stamp, targetPx) {
        value = path?.let { withContext(Dispatchers.IO) { runCatching { decodeThumb(it, targetPx) }.getOrNull() } }
    }
    Box(modifier.background(Paper.sheet), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else if (path == null) {
            LoadBar(progress = null, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

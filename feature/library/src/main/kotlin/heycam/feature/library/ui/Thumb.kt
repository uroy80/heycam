package heycam.feature.library.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import heycam.feature.library.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small decoded-thumbnail cache; the key carries the file's mtime, so a re-rendered thumb.jpg is never stale. */
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

/** A page thumbnail with a hairline border; a plain glyph while it renders. [stamp] re-reads a re-rendered file. */
@Composable
internal fun Thumb(path: String?, stamp: Any?, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val targetPx = with(LocalDensity.current) { maxOf(width, height).roundToPx() }
    val image by produceState<ImageBitmap?>(null, path, stamp, targetPx) {
        value = path?.let { withContext(Dispatchers.IO) { runCatching { decodeThumb(it, targetPx) }.getOrNull() } }
    }
    Box(
        modifier
            .size(width, height)
            .background(Paper.canvas)
            .border(1.dp, Paper.line),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width, height))
        } else {
            Icon(painterResource(R.drawable.library_ic_image), contentDescription = null, tint = Paper.rule, modifier = Modifier.size(20.dp))
        }
    }
}

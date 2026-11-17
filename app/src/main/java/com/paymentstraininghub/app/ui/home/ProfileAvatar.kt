package com.paymentstraininghub.app.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paymentstraininghub.app.ui.theme.PfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads a remote profile picture, or null when it cannot be
 * loaded. Loaders return null on failure instead of throwing,
 * and run their own network work off the main thread.
 */
fun interface AvatarImageLoader {
    suspend fun load(url: String): ImageBitmap?
}

/**
 * The downsample factor for a picture of [outWidth] by
 * [outHeight], capped at [maxPx] on each side, so a large
 * uploaded photo never costs full-size memory for a small
 * circle. Powers of two only, as BitmapFactory requires.
 */
internal fun avatarSampleSize(outWidth: Int, outHeight: Int, maxPx: Int = 256): Int {
    if (outWidth <= 0 || outHeight <= 0) return 1
    var sample = 1
    var width = outWidth
    var height = outHeight
    while (width / 2 >= maxPx && height / 2 >= maxPx) {
        width /= 2
        height /= 2
        sample *= 2
    }
    return sample
}

/**
 * Decodes picture bytes into a bitmap capped at [maxPx] on
 * each side. Returns null when the bytes are empty or are not
 * a decodable picture. Never throws.
 *
 * Robolectric cannot prove the BitmapFactory path: its image
 * parsing throws for real pictures in the sandbox while
 * yielding placeholder bitmaps for garbage, so the decode of
 * real bytes is verified on device, with the fallback proven
 * here. The sampling math is proven by [avatarSampleSize].
 */
internal fun decodeAvatarBitmap(bytes: ByteArray, maxPx: Int = 256): ImageBitmap? {
    return try {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = avatarSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private val avatarHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}

private suspend fun defaultFetchAvatarBytes(url: String): ByteArray? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            avatarHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

/**
 * The network picture loader, with a small in-memory cache:
 * the greeting picture is fetched once per URL and reused
 * while the app runs. The fetch and decode steps are
 * injectable for tests: Robolectric cannot run the real
 * BitmapFactory decode (see [decodeAvatarBitmap]).
 */
class HttpAvatarImageLoader(
    private val fetchBytes: suspend (String) -> ByteArray? = { url ->
        defaultFetchAvatarBytes(url)
    },
    private val decode: (ByteArray) -> ImageBitmap? = { bytes -> decodeAvatarBitmap(bytes) }
) : AvatarImageLoader {

    private val cache = LinkedHashMap<String, ImageBitmap>(8, 0.75f, true)

    override suspend fun load(url: String): ImageBitmap? {
        synchronized(cache) { cache[url] }?.let { return it }
        val bytes = runCatching { fetchBytes(url) }.getOrNull() ?: return null
        val bitmap = runCatching { decode(bytes) }.getOrNull() ?: return null
        synchronized(cache) {
            cache[url] = bitmap
            while (cache.size > MAX_CACHED_AVATARS) {
                cache.remove(cache.keys.first())
            }
        }
        return bitmap
    }

    companion object {
        const val MAX_CACHED_AVATARS = 8
    }
}

/** The shared loader used by the greeting header. */
val defaultAvatarImageLoader: AvatarImageLoader = HttpAvatarImageLoader()

/**
 * The greeting header picture: a clean circle, cropped
 * centre-first with no distortion, ringed in gold, tappable
 * to open the profile. While the picture loads, while there
 * is no picture, and whenever loading fails, the default
 * person icon shows instead: a broken frame is never shown.
 * The test tag reports which state rendered, so tests can
 * prove the picture path and the fallback path separately.
 */
@Composable
internal fun ProfileAvatar(
    avatarUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    imageLoader: AvatarImageLoader = defaultAvatarImageLoader
) {
    var bitmap by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarUrl) {
        bitmap = null
        if (!avatarUrl.isNullOrBlank()) {
            bitmap = runCatching { imageLoader.load(avatarUrl) }.getOrNull()
        }
    }
    val photo = bitmap
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(PfColors.card)
            .border(2.dp, PfColors.gold, CircleShape)
            .clickable(onClick = onClick)
            .testTag(if (photo != null) "profile-avatar-photo" else "profile-avatar-fallback")
            .semantics {
                contentDescription = if (photo != null) {
                    "Profile picture"
                } else {
                    "Default profile picture"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = PfColors.royal,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

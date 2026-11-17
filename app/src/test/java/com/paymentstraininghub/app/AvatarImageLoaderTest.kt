// AvatarImageLoaderTest.kt
// Wave 3 greeting-picture loader proofs: the loader caches per
// URL, never throws on fetch or decode failure, and returns
// null instead of a broken frame. The decode step is injected:
// Robolectric cannot run the real BitmapFactory decode (its
// image parsing throws for real pictures in the sandbox while
// yielding placeholder bitmaps for garbage), so the decode of
// real bytes is verified on device and the sampling math is
// proven pure. The empty-bytes guard is proven through the real
// decoder, which returns before touching BitmapFactory.

package com.paymentstraininghub.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.paymentstraininghub.app.ui.home.HttpAvatarImageLoader
import com.paymentstraininghub.app.ui.home.avatarSampleSize
import com.paymentstraininghub.app.ui.home.decodeAvatarBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

class AvatarSampleSizeTest {

    @Test
    fun smallPicturesAreNotDownsampled() {
        assertEquals(1, avatarSampleSize(100, 100))
        assertEquals(1, avatarSampleSize(256, 256))
        assertEquals(1, avatarSampleSize(300, 200))
    }

    @Test
    fun largePicturesDownsampleInPowersOfTwo() {
        assertEquals(2, avatarSampleSize(512, 512))
        assertEquals(4, avatarSampleSize(1024, 1024))
        // 4000x3000 at 8 is 500x375: halving again would drop
        // below the cap on the short side, so 8 is the factor.
        assertEquals(8, avatarSampleSize(4000, 3000))
    }

    @Test
    fun unknownDimensionsAreNotDownsampled() {
        assertEquals(1, avatarSampleSize(0, 0))
        assertEquals(1, avatarSampleSize(-1, -1))
    }

    @Test
    fun emptyBytesDecodeToNullWithoutTouchingBitmapFactory() {
        assertNull(decodeAvatarBitmap(byteArrayOf()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestWorkApp::class)
class AvatarImageLoaderTest {

    /**
     * A stand-in decoder: "good" bytes decode, anything else
     * does not. The bitmap is built through Bitmap.createBitmap
     * (shadowed under Robolectric): the ImageBitmap constructor
     * hits an unshadowed createBitmap overload and throws.
     */
    private fun fakeDecode(bytes: ByteArray): ImageBitmap? {
        val text = bytes.toString(Charsets.UTF_8)
        return if (text.startsWith("GOOD")) {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            null
        }
    }

    @Test
    fun loaderCachesPerUrl() = runBlocking {
        var fetches = 0
        val loader = HttpAvatarImageLoader(
            fetchBytes = {
                fetches++
                "GOOD".toByteArray()
            },
            decode = ::fakeDecode
        )
        val first = loader.load("https://example.com/a.jpg")
        val second = loader.load("https://example.com/a.jpg")
        assertNotNull(first)
        assertTrue(first === second)
        assertEquals(1, fetches)
        loader.load("https://example.com/b.jpg")
        assertEquals(2, fetches)
    }

    @Test
    fun loaderReturnsNullOnFetchFailureWithoutThrowing() = runBlocking {
        val throwing = HttpAvatarImageLoader(
            fetchBytes = { throw IOException("network down") },
            decode = ::fakeDecode
        )
        assertNull(throwing.load("https://example.com/a.jpg"))

        val nullBody = HttpAvatarImageLoader(
            fetchBytes = { null },
            decode = ::fakeDecode
        )
        assertNull(nullBody.load("https://example.com/a.jpg"))
    }

    @Test
    fun loaderReturnsNullWhenDecodingFailsWithoutThrowing() = runBlocking {
        val undecodable = HttpAvatarImageLoader(
            fetchBytes = { "BAD".toByteArray() },
            decode = ::fakeDecode
        )
        assertNull(undecodable.load("https://example.com/a.jpg"))

        val throwingDecode = HttpAvatarImageLoader(
            fetchBytes = { "GOOD".toByteArray() },
            decode = { throw RuntimeException("decoder blew up") }
        )
        assertNull(throwingDecode.load("https://example.com/a.jpg"))
    }

    @Test
    fun loaderEvictsOldestBeyondTheCacheCap() = runBlocking {
        var fetches = 0
        val loader = HttpAvatarImageLoader(
            fetchBytes = {
                fetches++
                "GOOD".toByteArray()
            },
            decode = ::fakeDecode
        )
        for (n in 1..HttpAvatarImageLoader.MAX_CACHED_AVATARS + 1) {
            loader.load("https://example.com/$n.jpg")
        }
        assertEquals(HttpAvatarImageLoader.MAX_CACHED_AVATARS + 1, fetches)
        // The first URL was evicted: loading it fetches again.
        loader.load("https://example.com/1.jpg")
        assertEquals(HttpAvatarImageLoader.MAX_CACHED_AVATARS + 2, fetches)
        // The last URL is still cached: no new fetch.
        loader.load("https://example.com/${HttpAvatarImageLoader.MAX_CACHED_AVATARS + 1}.jpg")
        assertEquals(HttpAvatarImageLoader.MAX_CACHED_AVATARS + 2, fetches)
    }
}

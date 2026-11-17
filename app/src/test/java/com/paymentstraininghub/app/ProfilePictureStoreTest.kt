// ProfilePictureStoreTest.kt
// Wave 3 greeting-picture proofs: the profiles-row parsing, the
// direct-URL vs storage-path decision, and the storage signed
// URL reply parsing. All pure: no network, no Android. The
// signed-URL and profiles contracts mirror iOS exactly.

package com.paymentstraininghub.app

import com.paymentstraininghub.app.auth.ProfilePictureStore
import com.paymentstraininghub.app.auth.SupabaseClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePictureStoreTest {

    @Test
    fun parseAvatarUrlReadsFirstRow() {
        val body = """[{"avatar_url": "user-123/avatar.jpg", "id": "user-123"}]"""
        assertEquals("user-123/avatar.jpg", ProfilePictureStore.parseAvatarUrl(body))
    }

    @Test
    fun parseAvatarUrlReadsDirectUrl() {
        val body = """[{"avatar_url": "https://example.com/photos/me.jpg"}]"""
        assertEquals(
            "https://example.com/photos/me.jpg",
            ProfilePictureStore.parseAvatarUrl(body)
        )
    }

    @Test
    fun parseAvatarUrlReturnsNullWithoutARowOrField() {
        assertNull(ProfilePictureStore.parseAvatarUrl("[]"))
        assertNull(ProfilePictureStore.parseAvatarUrl("""[{"id": "user-123"}]"""))
        assertNull(ProfilePictureStore.parseAvatarUrl("""[{"avatar_url": null}]"""))
        assertNull(ProfilePictureStore.parseAvatarUrl("""[{"avatar_url": "  "}]"""))
        assertNull(ProfilePictureStore.parseAvatarUrl("not json"))
        assertNull(ProfilePictureStore.parseAvatarUrl(""))
    }

    @Test
    fun directUrlDetection() {
        assertTrue(ProfilePictureStore.isDirectUrl("https://example.com/a.jpg"))
        assertTrue(ProfilePictureStore.isDirectUrl("http://example.com/a.jpg"))
        assertFalse(ProfilePictureStore.isDirectUrl("user-123/avatar.jpg"))
        assertFalse(ProfilePictureStore.isDirectUrl("avatars/user-123/avatar.jpg"))
    }

    @Test
    fun storagePathStripsBucketPrefixAndSlashes() {
        assertEquals(
            "user-123/avatar.jpg",
            ProfilePictureStore.storagePathFromStored("avatars/user-123/avatar.jpg")
        )
        assertEquals(
            "user-123/avatar.jpg",
            ProfilePictureStore.storagePathFromStored("user-123/avatar.jpg")
        )
        assertEquals(
            "user-123/avatar.jpg",
            ProfilePictureStore.storagePathFromStored("/user-123/avatar.jpg")
        )
        assertNull(ProfilePictureStore.storagePathFromStored("avatars/"))
        assertNull(ProfilePictureStore.storagePathFromStored(""))
        assertNull(ProfilePictureStore.storagePathFromStored("   "))
    }

    @Test
    fun parseSignedUrlResolvesRelativeReply() {
        val base = "https://eofsotgclhwsdwvbbtib.supabase.co"
        val body = """{"signedURL": "/object/sign/avatars/user-123/avatar.jpg?token=abc123"}"""
        assertEquals(
            "$base/storage/v1/object/sign/avatars/user-123/avatar.jpg?token=abc123",
            SupabaseClient.parseSignedUrl(body, base)
        )
    }

    @Test
    fun parseSignedUrlPassesAbsoluteReplyThrough() {
        val absolute = "https://eofsotgclhwsdwvbbtib.supabase.co/storage/v1/object/sign/avatars/a.jpg?token=x"
        assertEquals(
            absolute,
            SupabaseClient.parseSignedUrl("""{"signedURL": "$absolute"}""", "https://unused.example")
        )
    }

    @Test
    fun parseSignedUrlReturnsNullWithoutASignedUrl() {
        assertNull(SupabaseClient.parseSignedUrl("{}", "https://example.co"))
        assertNull(SupabaseClient.parseSignedUrl("""{"signedURL": ""}""", "https://example.co"))
        assertNull(SupabaseClient.parseSignedUrl("not json", "https://example.co"))
    }
}

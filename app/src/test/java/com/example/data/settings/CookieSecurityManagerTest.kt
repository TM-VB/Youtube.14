package com.example.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CookieSecurityManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CookieSecurityManager.clearCookies(context)
    }

    @Test
    fun testEncryptionAndDecryptionRoundtrip() {
        val sampleCookies = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t2147483647\tSID\tAbCdEf12345"

        val encrypted = CookieSecurityManager.encrypt(sampleCookies)
        assertNotNull("Encryption should produce non-null ciphertext", encrypted)
        assertNotEquals("Encrypted ciphertext must never match plaintext", sampleCookies, encrypted)

        val decrypted = CookieSecurityManager.decrypt(encrypted!!)
        assertEquals("Decrypted content must match original plaintext", sampleCookies, decrypted)
    }

    @Test
    fun testSavingAndRetrievingCookies() {
        val sampleCookies = "# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t2147483647\tHSID\tTestSecretValue"

        val saved = CookieSecurityManager.saveCookies(context, sampleCookies)
        assertTrue("Saving cookies should succeed", saved)

        val retrieved = CookieSecurityManager.getCookies(context)
        assertEquals("Retrieved cookies should match saved cookies", sampleCookies, retrieved)

        // Verify that SharedPreferences does NOT contain any plaintext cookies
        val prefs = context.getSharedPreferences("cookie_security_prefs", Context.MODE_PRIVATE)
        val storedEncrypted = prefs.getString("encrypted_cookies_payload", null)
        assertNotNull(storedEncrypted)
        assertNotEquals(sampleCookies, storedEncrypted)
        assertFalse("Ciphertext should not contain raw cookie secrets", storedEncrypted!!.contains("TestSecretValue"))

        // Legacy plaintext keys must not exist
        assertFalse(prefs.contains("key_cookies_content"))
    }

    @Test
    fun testClearCookies() {
        val sampleCookies = "cookie_name=cookie_value"
        CookieSecurityManager.saveCookies(context, sampleCookies)
        assertTrue(CookieSecurityManager.hasCookies(context))

        CookieSecurityManager.clearCookies(context)
        assertFalse(CookieSecurityManager.hasCookies(context))
        assertEquals("", CookieSecurityManager.getCookies(context))
    }

    @Test
    fun testCreateTempCookieFileAndCleanup() {
        val sampleCookies = "# Netscape Cookie Header\n.google.com\tTRUE\t/\tTRUE\t1700000000\tSSID\txyz987"
        CookieSecurityManager.saveCookies(context, sampleCookies)

        val tag = "task_test_123"
        val tempFile = CookieSecurityManager.createTempCookieFile(context, tag)
        assertNotNull("Temp cookie file should be created when cookies are present", tempFile)
        assertTrue("Temp cookie file must exist on disk", tempFile!!.exists())
        assertEquals("Temp cookie file content must match", sampleCookies, tempFile.readText())

        // Ensure temp file is in private cache storage
        assertTrue("Temp cookie file must reside inside cache directory", tempFile.canonicalPath.startsWith(context.cacheDir.canonicalPath))

        // Deterministic deletion of specific file
        CookieSecurityManager.deleteTempCookieFile(tempFile)
        assertFalse("Temp cookie file must be deleted", tempFile.exists())
    }

    @Test
    fun testDeleteTempCookieFilesForTag() {
        val sampleCookies = "cookie_key=secret"
        CookieSecurityManager.saveCookies(context, sampleCookies)

        val tag = "task_batch_456"
        val tempFile1 = CookieSecurityManager.createTempCookieFile(context, tag)
        val tempFile2 = CookieSecurityManager.createTempCookieFile(context, tag)

        assertNotNull(tempFile1)
        assertNotNull(tempFile2)
        assertTrue(tempFile1!!.exists())
        assertTrue(tempFile2!!.exists())

        CookieSecurityManager.deleteTempCookieFilesForTag(context, tag)

        assertFalse("Temp file 1 must be deleted by tag cleanup", tempFile1.exists())
        assertFalse("Temp file 2 must be deleted by tag cleanup", tempFile2.exists())
    }

    @Test
    fun testMigrationFromLegacyPlaintextStorage() {
        val legacyPrefs = context.getSharedPreferences("download_videos_settings", Context.MODE_PRIVATE)
        val legacySecret = "legacy_auth_cookie_token_12345"
        legacyPrefs.edit().putString("key_cookies_content", legacySecret).apply()

        // CookieSecurityManager should detect and migrate legacy plaintext cookies
        val migrated = CookieSecurityManager.getCookies(context)
        assertEquals("Should retrieve migrated cookie content", legacySecret, migrated)

        // Legacy key in download_videos_settings must be purged
        assertNull("Legacy plaintext key must be removed from legacy prefs", legacyPrefs.getString("key_cookies_content", null))

        // New storage in cookie_security_prefs must be encrypted
        val securityPrefs = context.getSharedPreferences("cookie_security_prefs", Context.MODE_PRIVATE)
        val storedEncrypted = securityPrefs.getString("encrypted_cookies_payload", null)
        assertNotNull("Should have stored encrypted payload", storedEncrypted)
        assertNotEquals("Should not store plaintext in security prefs", legacySecret, storedEncrypted)
    }
}

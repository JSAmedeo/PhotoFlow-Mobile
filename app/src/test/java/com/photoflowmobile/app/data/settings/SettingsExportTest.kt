package com.photoflowmobile.app.data.settings

import com.photoflowmobile.app.data.model.AppSettings
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.ConnectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards FR-7: a settings export must never carry credentials.
 *
 * The export lands in shared Downloads storage, readable by any app with storage permission and
 * swept up by cloud backup. An earlier version wrote FTP passwords there — read back out of the
 * encrypted store specifically to put them in the file — plus the cloud API key and setup code.
 *
 * These tests exist because that kind of regression is silent: re-adding a `put("password", …)`
 * breaks nothing visible, and the leak would ship unnoticed.
 */
class SettingsExportTest {

    private val profile = ConnectionProfile(
        id = 7,
        name = "P-SERVER-ALPHA",
        connectionType = ConnectionType.FTP,
        host = "192.168.1.144",
        port = 21,
        username = "operator",
        password = "hunter2",
        remotePath = "/uploads",
        photoOp = "SpringFair",
        isActive = true
    )

    private val settings = AppSettings(
        cloudSetupCode = "SETUP-SECRET-123",
        cloudVenueSlug = "spring-fair",
        cloudVenueId = 42,
        cloudDeviceDisplayName = "Booth 1",
        cloudStationName = "Station A"
    )

    @Test
    fun `profile export contains no password key`() {
        val map = SettingsExport.profileToMap(profile)
        assertFalse("password must never be exported", map.containsKey("password"))
    }

    @Test
    fun `profile export contains no value matching the password`() {
        // Stronger than a key check: catches the secret being smuggled under another name.
        val map = SettingsExport.profileToMap(profile)
        assertFalse(
            "no exported value may equal the password",
            map.values.any { it.toString() == "hunter2" }
        )
    }

    @Test
    fun `settings export contains no secret-bearing keys`() {
        val map = SettingsExport.settingsToMap(settings)
        SettingsExport.SECRET_KEYS.forEach { key ->
            assertFalse("$key must never be exported", map.containsKey(key))
        }
    }

    @Test
    fun `settings export contains no value matching the setup code`() {
        val map = SettingsExport.settingsToMap(settings)
        assertFalse(
            "the setup code provisions a device against a venue - it is a credential",
            map.values.any { it.toString() == "SETUP-SECRET-123" }
        )
    }

    @Test
    fun `profile export round-trips photoOp`() {
        // Schema-v8 field that the original export silently dropped, losing the FTP subfolder
        // on every export/import cycle.
        assertEquals("SpringFair", SettingsExport.profileToMap(profile)["photo_op"])
    }

    @Test
    fun `profile export carries the non-secret connection fields`() {
        val map = SettingsExport.profileToMap(profile)
        assertEquals("P-SERVER-ALPHA", map["name"])
        assertEquals("192.168.1.144", map["host"])
        assertEquals(21, map["port"])
        assertEquals("operator", map["username"])
        assertEquals("/uploads", map["remote_path"])
        assertEquals("FTP", map["connection_type"])
        assertEquals(true, map["is_active"])
    }

    @Test
    fun `settings export carries non-secret cloud config`() {
        val map = SettingsExport.settingsToMap(settings)
        assertEquals("spring-fair", map["cloud_venue_slug"])
        assertEquals(42, map["cloud_venue_id"])
        assertEquals("Booth 1", map["cloud_device_display_name"])
        assertEquals("Station A", map["cloud_station_name"])
    }

    @Test
    fun `settings export omits device identity`() {
        // uuid and device_id identify this handset, not its configuration; importing them onto
        // a second device would give two handsets the same cloud identity.
        val map = SettingsExport.settingsToMap(AppSettings(cloudDeviceUuid = "uuid-1", cloudDeviceId = 9))
        assertFalse(map.containsKey("cloud_device_uuid"))
        assertFalse(map.containsKey("cloud_device_id"))
    }

    @Test
    fun `settings export still carries operational preferences`() {
        val map = SettingsExport.settingsToMap(AppSettings())
        assertTrue(map.containsKey("auto_retry_max_count"))
        assertTrue(map.containsKey("naming_fields"))
        assertTrue(map.containsKey("session_history_max"))
        assertEquals(3, map["auto_retry_max_count"])
    }
}

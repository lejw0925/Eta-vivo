package io.github.mangi.eta.agent.display

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenFallbackSettingsTest {
    @Test
    fun nativePreviewReadsLegacyEnablementAndDiscardsObsoleteStyleOnSave() = runBlocking {
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        @Suppress("UNCHECKED_CAST")
        val store = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(SettingsDataStore) as DataStore<Preferences>
        val original = store.data.first()
        val enabled = booleanPreferencesKey("virtual_screen_floating_preview")
        val mode = stringPreferencesKey("virtual_screen_preview_mode")
        try {
            for (legacyMode in listOf(null, "floating", "picture_in_picture", "future-mode")) {
                for (legacyEnabled in listOf(false, true)) {
                    store.edit { preferences ->
                        preferences[enabled] = legacyEnabled
                        if (legacyMode == null) preferences.remove(mode) else preferences[mode] = legacyMode
                    }
                    val loaded = SettingsDataStore.settings()
                    assertEquals(legacyEnabled, loaded.virtualScreenPreviewEnabled)
                    SettingsDataStore.updateSettings { it }
                    assertEquals(loaded, SettingsDataStore.settings())
                    assertFalse(store.data.first().contains(mode))
                    assertEquals(legacyEnabled, store.data.first()[enabled])
                }
            }
        } finally { store.updateData { original } }
    }

    @Test
    fun serializedSettingsPreserveExistingPreviewEnablementName() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"virtualScreenFloatingPreviewEnabled":true,"virtualScreenPreviewMode":"FLOATING"}"""
        assertTrue(json.decodeFromString<Settings>(legacy).virtualScreenPreviewEnabled)
        val saved = json.encodeToString(Settings(virtualScreenPreviewEnabled = true))
        assertTrue(saved.contains("\"virtualScreenFloatingPreviewEnabled\":true"))
        assertFalse(saved.contains("virtualScreenPreviewMode"))
    }

    @Test
    fun restartAndIdleSettingsPersistWithConservativeDefaults() = runBlocking {
        assertFalse(Settings().virtualScreenAutoRestartApps)
        assertFalse(Settings().virtualScreenPreviewEnabled)
        assertEquals(20, Settings().virtualScreenIdleTimeoutMinutes)
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val original = SettingsDataStore.settings()
        try {
            for (minutes in listOf(10, 20, 60, 0)) {
                SettingsDataStore.updateSettings { it.copy(virtualScreenAutoRestartApps = true,
                    virtualScreenPreviewEnabled = true, virtualScreenIdleTimeoutMinutes = minutes) }
                assertTrue(SettingsDataStore.settings().virtualScreenAutoRestartApps)
                assertTrue(SettingsDataStore.settings().virtualScreenPreviewEnabled)
                assertEquals(minutes, SettingsDataStore.settings().virtualScreenIdleTimeoutMinutes)
            }
            SettingsDataStore.updateSettings { it.copy(virtualScreenIdleTimeoutMinutes = -1) }
            assertEquals(20, SettingsDataStore.settings().virtualScreenIdleTimeoutMinutes)
        } finally { SettingsDataStore.updateSettings { original } }
    }

    @Test
    fun fallbackIsOffByDefaultAndPersistsWithoutChangingOtherPreferences() = runBlocking {
        assertFalse(Settings().virtualScreenFallbackEnabled)
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val original = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings { it.copy(virtualScreenFallbackEnabled = true) }
            val updated = SettingsDataStore.settings()
            assertTrue(updated.virtualScreenFallbackEnabled)
            assertEquals(
                original,
                updated.copy(virtualScreenFallbackEnabled = original.virtualScreenFallbackEnabled)
            )
        } finally {
            SettingsDataStore.updateSettings { original }
        }
    }
}

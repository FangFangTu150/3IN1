package io.github.threeinone

import android.content.Context
import io.github.threeinone.config.PreferenceMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferenceMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun fixture(xml: String): File =
        File(context.cacheDir, "old-indicator.xml").apply { writeText(xml) }
    private val legacy = """<map><boolean name="enabled" value="true"/>
        <int name="size" value="26"/><long name="revision" value="123"/>
        <string name="unrelated">ignored</string></map>"""

    @Test fun importsPreActivationSettingsIntoEmptySharedStore() {
        val p = context.getSharedPreferences("shared", 0)
        assertTrue(PreferenceMigration.importOnce(p, fixture(legacy)))
        assertTrue(p.getBoolean("enabled", false))
        assertEquals(26, p.getInt("size", 0))
        assertEquals(123L, p.getLong("revision", 0))
        assertFalse(p.contains("unrelated"))
    }

    @Test fun existingDisabledSettingWinsOverOldEnabledSetting() {
        val p = context.getSharedPreferences("shared", 0)
        p.edit().putBoolean("enabled", false).commit()
        assertFalse(PreferenceMigration.importOnce(p, fixture(legacy)))
        assertFalse(p.getBoolean("enabled", true))
    }

    @Test fun resetMarkerPreventsRestoringOldEnabledState() {
        val p = context.getSharedPreferences("shared", 0)
        assertTrue(PreferenceMigration.importOnce(p, fixture(legacy)))
        p.edit().clear().putBoolean(PreferenceMigration.MARKER, true).commit()
        assertFalse(PreferenceMigration.importOnce(p, fixture(legacy)))
        assertFalse(p.getBoolean("enabled", false))
    }

    @Test fun invalidFileNeverPartiallyImportsEnabledFlag() {
        val p = context.getSharedPreferences("shared", 0)
        assertFalse(PreferenceMigration.importOnce(p,
            fixture("""<map><boolean name="enabled" value="true"/><int name="size" value="bad"/></map>""")))
        assertTrue(p.all.isEmpty())
    }

    @Test fun missingLegacyFileDoesNotInventSettings() {
        val p = context.getSharedPreferences("shared", 0)
        assertFalse(PreferenceMigration.importOnce(p, File(context.cacheDir, "missing.xml")))
        assertTrue(p.all.isEmpty())
    }
}

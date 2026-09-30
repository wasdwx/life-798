package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, preview: Boolean = false, draft: Boolean = false, size: Int = 100) =
        JSONObject().put("tag_name", tag).put("prerelease", preview).put("draft", draft)
            .put("assets", JSONArray().put(JSONObject().put("name", "WaterWidget-$tag-release.apk")
                .put("size", size).put("state", "uploaded")))

    @Test fun usesNumericVersionsInsteadOfReleaseOrderOrLexicographicOrdering() {
        val releases = JSONArray().put(release("v5.9.9")).put(release("v5.10.0")).put(release("v5.4.6"))
        assertEquals("v5.10.0", AppUpdates.newest(releases, false)?.tag)
        assertTrue(ReleaseVersion.parse("5.4.4")!! > ReleaseVersion.parse("5.4.4-personal.10")!!)
        assertTrue(ReleaseVersion.parse("5.4.4-personal.10")!! > ReleaseVersion.parse("5.4.4-personal.2")!!)
        assertNull(ReleaseVersion.parse("v5.4.6/../../external"))
        assertNull(ReleaseVersion.parse("v999999999999999999999.0.0"))
    }

    @Test fun ignoresDraftsAndMissingApksAndRequiresOptInForPreviews() {
        val releases = JSONArray().put(release("v5.4.2-personal.1", true, true))
            .put(release("v5.4.5", true)).put(release("v5.4.6", size = 0))
        assertNull(AppUpdates.newest(releases, false))
        assertEquals("v5.4.5", AppUpdates.newest(releases, true)?.tag)
        releases.put(release("v5.4.6"))
        assertEquals("v5.4.6", AppUpdates.newest(releases, false)?.tag)
        assertEquals("https://github.com/wasdwx/life-798/releases/tag/v5.4.6", AppUpdates.newest(releases, false)?.url)
    }

    @Test fun previewSuffixIsNotTreatedAsStableWhenGithubFlagIsWrong() {
        assertNull(AppUpdates.newest(JSONArray().put(release("v9.0.0-personal.1")), false))
    }

    @Test fun autoCheckPolicyHandlesIntervalsDisabledStateAndClockRollback() {
        assertFalse(AppUpdates.shouldCheck(UpdatePolicy.OFF, 100, 0))
        assertTrue(AppUpdates.shouldCheck(UpdatePolicy.DAILY, 100, 0))
        assertFalse(AppUpdates.shouldCheck(UpdatePolicy.DAILY, 200, 100))
        assertTrue(AppUpdates.shouldCheck(UpdatePolicy.DAILY, 86_400_100, 100))
        assertTrue(AppUpdates.shouldCheck(UpdatePolicy.DAILY, 50, 100))
        assertTrue(AppUpdates.shouldCheck(UpdatePolicy.ON_OPEN, 100, 100))
    }
}

package one.lmk.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastDisplayHelperTest {

    @Test
    fun castSettingsActionsPreferNativeCastThenFallbacks() {
        assertEquals(
            listOf(
                "android.settings.CAST_SETTINGS",
                "android.settings.WIFI_DISPLAY_SETTINGS",
                "android.settings.DISPLAY_SETTINGS",
            ),
            CastDisplayHelper.castSettingsIntentActions(),
        )
    }

    @Test
    fun selectPreferredDisplayIgnoresDefaultOffAndZeroSizedDisplays() {
        val selected = CastDisplayHelper.selectPreferredPresentationDisplay(
            listOf(
                PresentationDisplaySpec(0, 1280, 2856, isPresentationFlag = true, stateOn = true),
                PresentationDisplaySpec(2, 1920, 1080, isPresentationFlag = true, stateOn = false),
                PresentationDisplaySpec(3, 0, 1080, isPresentationFlag = true, stateOn = true),
            ),
        )
        assertNull(selected)
    }

    @Test
    fun selectPreferredDisplayPrefersPresentationFlagAndLargestArea() {
        val secondaryNonPres = PresentationDisplaySpec(4, 3840, 2160, isPresentationFlag = false, stateOn = true)
        val hdTv = PresentationDisplaySpec(5, 1920, 1080, isPresentationFlag = true, stateOn = true)
        val uhdTv = PresentationDisplaySpec(6, 3840, 2160, isPresentationFlag = true, stateOn = true)

        assertEquals(uhdTv, CastDisplayHelper.selectPreferredPresentationDisplay(listOf(secondaryNonPres, hdTv, uhdTv)))
        assertEquals(hdTv, CastDisplayHelper.selectPreferredPresentationDisplay(listOf(secondaryNonPres, hdTv)))
    }

    @Test
    fun validPresentationFrameBoundsAndByteCount() {
        assertTrue(CastDisplayHelper.isValidPresentationFrame(1, 1, 4))
        assertTrue(CastDisplayHelper.isValidPresentationFrame(1920, 1080, 1920 * 1080 * 4))
        assertFalse(CastDisplayHelper.isValidPresentationFrame(0, 100, 0))
        assertFalse(CastDisplayHelper.isValidPresentationFrame(100, -1, -400))
        assertFalse(CastDisplayHelper.isValidPresentationFrame(5000, 100, 5000 * 100 * 4))
        assertFalse(CastDisplayHelper.isValidPresentationFrame(100, 100, 100 * 100 * 4 - 1))
    }
}

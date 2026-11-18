package dev.sleepalarm.update

import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.Button
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelThemeTest {
    @Test fun `day and night themes give platform text readable contrast`() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = ContextThemeWrapper(app.createConfigurationContext(config), R.style.SleepUpdateTheme)
            val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
            val background = attrs.getColor(0, Color.TRANSPARENT)
            attrs.recycle()
            assertEquals(255, Color.alpha(background))
            assertEquals(night, ColorUtils.calculateLuminance(background) < 0.5)
            assertTrue(ColorUtils.calculateContrast(TextView(themed).currentTextColor, background) >= 4.5)
            assertTrue(Button(themed).isEnabled)
        }
    }
}

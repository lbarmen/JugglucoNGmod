package mod.watch.face

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceIcons
import tk.glucodata.mod.watch.face.FaceStyle

/** JugglucoNGmod, watch block (part 3b): the face's colours and line weights, normal and dim (ambient). */
class FaceStyleTest {

    private val normal = FaceStyle.of(ambient = false)
    private val ambient = FaceStyle.of(ambient = true)

    private fun brightness(color: Int) = ((color shr 16) and 0xFF) + ((color shr 8) and 0xFF) + (color and 0xFF)

    @Test
    fun theBackgroundIsAlwaysBlack() {
        assertEquals(FaceStyle.BLACK, normal.background)
        assertEquals(FaceStyle.BLACK, ambient.background)
    }

    @Test
    fun ambientKeepsTheWholePictureDimmedAndWithThinnerLines() {
        // part 7l, the owner glances at the dim mode all day: the graph's labels there too, dimmed as the rest
        assertNotNull(normal.hourLabel)
        assertEquals(FaceStyle.dim(normal.hourLabel!!, FaceStyle.AMBIENT_DIM), ambient.hourLabel)
        // part 7l: the forecast as well - dimmed as the rest, thinner
        assertNotNull(normal.forecast)
        assertEquals(FaceStyle.dim(normal.forecast!!, FaceStyle.AMBIENT_DIM), ambient.forecast)
        assertTrue(brightness(ambient.forecast!!) < brightness(normal.forecast!!))
        assertTrue(ambient.forecastWidth < normal.forecastWidth && ambient.forecastWidth > 0f)
        assertTrue(ambient.dotRadius < normal.dotRadius)
        assertTrue(ambient.rangeLineWidth < normal.rangeLineWidth)
        assertTrue(ambient.arrowStroke < normal.arrowStroke)
        assertTrue(ambient.dotRadius > 0f && ambient.rangeLineWidth > 0f && ambient.arrowStroke > 0f)
    }

    @Test
    fun everyAmbientColourIsDimmer() {
        val pairs = listOf(
            normal.time to ambient.time, normal.secondary to ambient.secondary, normal.age to ambient.age,
            normal.ageAlarm to ambient.ageAlarm, normal.neutral to ambient.neutral,
            normal.rangeLine to ambient.rangeLine, normal.mark to ambient.mark,
            normal.battery to ambient.battery, normal.batteryLow to ambient.batteryLow,
        )
        pairs.forEach { (n, a) -> assertTrue("$n vs $a", brightness(a) < brightness(n)) }
        val green = 0xFF33CC66.toInt()
        assertEquals(green, normal.valueColor(green))
        assertTrue(brightness(ambient.valueColor(green)) < brightness(green))
        assertTrue(brightness(ambient.traceColor(green)) < brightness(green))
    }

    @Test
    fun theDimNumberIsRegularAndAsLightAsTheArrow() {
        // part 7c, critic 7, S2: the largest lit area of the always-on screen - regular weight
        assertTrue(normal.valueBold)
        assertFalse(ambient.valueBold)
        val green = 0xFF33CC66.toInt()
        // part 7j, the owner on the watch: "too dim" - the number as light as the arrow, lighter than the dots
        assertEquals(FaceStyle.dim(green, FaceStyle.AMBIENT_VALUE_DIM), ambient.valueColor(green))
        assertEquals(brightness(ambient.arrowColor(green)), brightness(ambient.valueColor(green)))
        assertTrue(brightness(ambient.traceColor(green)) < brightness(ambient.valueColor(green)))
        // part 7e: the dim arrow lighter than the dots (a dark red one sank into the black), still dimmed
        assertTrue(brightness(ambient.traceColor(green)) < brightness(ambient.arrowColor(green)))
        assertTrue(brightness(ambient.arrowColor(green)) < brightness(green))
        assertEquals(FaceStyle.dim(green, FaceStyle.AMBIENT_ARROW_DIM), ambient.arrowColor(green))
        assertEquals(green, normal.arrowColor(green))
        assertEquals(normal.neutral, normal.arrowColor(null))
    }

    @Test
    fun batteriesRedUnder30YellowUnder50ElseGrey() {
        // part 7c, the owner's word
        for (style in listOf(normal, ambient)) {
            assertEquals(style.batteryLow, style.batteryColor(0))
            assertEquals(style.batteryLow, style.batteryColor(29))
            assertEquals(style.batteryWarn, style.batteryColor(30))
            assertEquals(style.batteryWarn, style.batteryColor(49))
            assertEquals(style.battery, style.batteryColor(50))
            assertEquals(style.battery, style.batteryColor(100))
        }
        assertTrue(brightness(ambient.batteryWarn) < brightness(normal.batteryWarn))
        val warn = normal.batteryWarn
        assertTrue(((warn shr 16) and 0xFF) > 200 && ((warn shr 8) and 0xFF) > 150 && (warn and 0xFF) < 120)
        // a known battery is shown in both modes (part 7j), an unknown one in neither
        assertTrue(FaceIcons.shown(29, ambient = true) && FaceIcons.shown(30, ambient = true))
        assertTrue(FaceIcons.shown(100, ambient = true))
        assertTrue(FaceIcons.shown(30, ambient = false) && FaceIcons.shown(100, ambient = false))
        assertFalse(FaceIcons.shown(null, ambient = false))
        assertFalse(FaceIcons.shown(null, ambient = true))
        assertFalse(FaceIcons.shown(101, ambient = true))
    }

    @Test
    fun theAgeAlarmAndALowBatteryAreRed() {
        for (c in listOf(normal.ageAlarm, normal.batteryLow)) {
            assertTrue(((c shr 16) and 0xFF) > 200 && ((c shr 8) and 0xFF) < 120 && (c and 0xFF) < 120)
        }
    }

    @Test
    fun noRangeColourGivesTheNeutralTone() {
        assertEquals(normal.neutral, normal.valueColor(null))
        assertEquals(ambient.neutral, ambient.valueColor(null))
    }

    @Test
    fun dimScalesChannelsAndKeepsAlpha() {
        // rounded to the nearest: 255 -> 128, 127 -> 64, 63 -> 32
        assertEquals(0xFF804020.toInt(), FaceStyle.dim(0xFFFF7F3F.toInt(), 0.5f))
        assertEquals(0x80000000.toInt(), FaceStyle.dim(0x80FFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), FaceStyle.dim(0xFFFFFFFF.toInt(), 1.5f))
    }

    @Test
    fun rangeLabelsAsTheAppWritesValues() {
        assertEquals("10,0", FaceStyle.rangeLabel(10f, true, Locale.forLanguageTag("ru-RU")))
        assertEquals("3.9", FaceStyle.rangeLabel(3.9f, true, Locale.US))
        assertEquals("180", FaceStyle.rangeLabel(180f, false, Locale.US))
    }
}

package mod.watch.face

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceValues

/**
 * JugglucoNGmod, watch block (part 3a, part 7a, part 7c): the face's rows on round screens (384 x 384 - Galaxy Watch
 * 4/5 44 mm class, 450 x 450 - Galaxy Watch 5 Pro): inside the circle, in order, the time alone at the top and the
 * date with the batteries at the bottom, and the texts fit by a generous estimate.
 */
class FaceLayoutTest {

    private val sizes = listOf(384, 450)

    private fun assertInsideCircle(layout: FaceLayout.Layout, row: FaceLayout.Row, name: String) {
        for (x in listOf(row.left, row.right)) for (y in listOf(row.top, row.bottom)) {
            val d = hypot(x - layout.centerX, y - layout.centerY)
            assertTrue("$name corner ($x, $y) at $d outside radius ${layout.radius}", d <= layout.radius)
        }
    }

    private fun assertFits(width: Float, row: FaceLayout.Row, what: String) =
        assertTrue("$what: $width wider than ${row.width}", width <= row.width)

    @Test
    fun everyRowIsInsideTheCircleOnBothSizes() {
        for (size in sizes) {
            val layout = FaceLayout.compute(size, size)
            val names = listOf("time", "value", "graph", "hours", "status", "mark")
            layout.rows.forEachIndexed { i, row ->
                assertTrue("${names[i]} empty on $size", row.width > 0f && row.height > 0f)
                assertInsideCircle(layout, row, "${names[i]} on $size")
                assertTrue("${names[i]} text taller than its row", row.textSize <= row.height + 0.001f)
            }
        }
    }

    @Test
    fun rowsGoTopToBottomWithoutOverlapAndTheirHeightsFitTheDiameter() {
        for (size in sizes) {
            val layout = FaceLayout.compute(size, size)
            layout.rows.zipWithNext().forEach { (a, b) -> assertTrue("overlap on $size", a.bottom <= b.top) }
            assertTrue(layout.rows.sumOf { it.height.toDouble() } <= size)
            assertTrue(layout.rows.first().top >= 0f && layout.rows.last().bottom <= size)
            assertTrue(layout.arrowSize <= layout.value.height)
            assertTrue(2 * layout.sideTextSize <= layout.value.height)
        }
    }

    @Test
    fun theTimeIsAloneOnTopAndTheDateWithTheBatteriesAtTheBottom() {
        // part 7c, the owner's word: the bottom band once kept free for the system's icon holds the status row
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            assertEquals(l.time, l.rows.first())
            assertTrue(l.time.bottom <= l.value.top)
            assertTrue(l.graph.bottom <= l.hours.top && l.hours.bottom <= l.status.top)
            assertEquals(FaceLayout.STATUS_TOP * size, l.status.top, 0.01f)
            assertEquals(FaceLayout.STATUS_BOTTOM * size, l.status.bottom, 0.01f)
            // in the lowest tenth of the circle above its edge, nothing free below it but the trial mark
            assertTrue(l.status.bottom >= 0.9f * size && l.status.bottom < size - FaceLayout.MARGIN * size)
            assertTrue(l.mark.top >= l.status.bottom)
        }
    }

    @Test
    fun theWholeCircleIsUsedTheValueIsLargeTheTimeLargerAndTheGraphTaller() {
        // part 7a on 450 px: value ~90 px, graph 348 x 153; part 7c: the time ~50 px (was 34), graph ~324 x 173
        val l = FaceLayout.compute(450, 450)
        assertEquals(90f, l.value.textSize, 1f)
        assertEquals(49.5f, l.time.textSize, 0.5f)
        assertEquals(324f, l.graph.width, 3f)
        assertEquals(173f, l.graph.height, 2f)
        assertTrue(l.time.textSize > 0.075f * 450 && l.time.textSize < l.value.textSize)
    }

    @Test
    fun theDimModeMovesThePictureFiveStepsRoundAndRoundAndKeepsItInside() {
        val d = 450f
        val steps = (0L until 5L).map { FaceLayout.burnInOffset(it, d, strong = false) }
        assertEquals(5, steps.toSet().size)
        assertEquals(0f to 0f, steps[0])
        assertEquals(steps, (5L until 10L).map { FaceLayout.burnInOffset(it, d, strong = false) })
        assertEquals(FaceLayout.burnInOffset(-1L, d, false), FaceLayout.burnInOffset(4L, d, false))
        val far = steps.maxOf { maxOf(kotlin.math.abs(it.first), kotlin.math.abs(it.second)) }
        assertEquals(Math.round(FaceLayout.BURN_IN_SHIFT * d).toFloat(), far, 0.01f)
        val strong = (0L until 5L).maxOf { FaceLayout.burnInOffset(it, d, strong = true).let { p -> maxOf(kotlin.math.abs(p.first), kotlin.math.abs(p.second)) } }
        assertEquals(2f * far, strong, 0.01f)
        // even the strong step stays inside the margin every row keeps from the round edge
        assertTrue(strong < FaceLayout.MARGIN * d / 2f)
    }

    @Test
    fun theTargetLinesReachTheRoundEdgeButNotPastIt() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            for (y in listOf(l.graph.top, l.graph.centerY, l.graph.bottom)) {
                val half = FaceLayout.halfWidthAt(l, y, 0.03f * size)
                assertTrue(half >= l.graph.width / 2f)
                assertTrue(hypot(half, y - l.centerY) <= l.radius)
                assertTrue(hypot(half, y - l.centerY) >= l.radius - 0.031f * size)
            }
            assertEquals(0f, FaceLayout.halfWidthAt(l, -10f, 0f), 0f)
        }
    }

    @Test
    fun theTextsFit() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            assertFits(FaceLayout.estimatedWidth("23:59", l.time.textSize), l.time, "24 h clock on $size")
            // 12-hour clock: the day part is drawn at 0.4 of the clock's size, as upstream's face does
            assertFits(FaceLayout.estimatedWidth("12:59", l.time.textSize) +
                FaceLayout.estimatedWidth("pm", l.time.textSize * 0.4f), l.time, "12 h clock on $size")
            // the status row (part 7e): the day and the month on each side of the centre, then the gap and a battery
            // (icon at most 0.62 of its height wide, its gap, the number); "81" at the full gap by the generous
            // estimate, "100" at the narrowest gap by the digits' real width (0.56 of the size, all digits alike)
            val t = l.status.textSize
            fun battery(number: Float) = 0.62f * l.statusIcon + FaceLayout.STATUS_ICON_GAP * size + number
            val half = FaceLayout.DATE_GAP * size / 2f
            for ((number, gap) in listOf(FaceLayout.estimatedWidth("81", t) to FaceLayout.STATUS_GAP,
                    3 * 0.56f * t to FaceLayout.STATUS_MIN_GAP)) {
                for (word in listOf("30", "ноя", "мая", "Nov", "May")) {
                    val side = FaceLayout.estimatedWidth(word, t) + half + gap * size + battery(number)
                    assertTrue("status $word / $number on $size: $side in ${l.status.width / 2f}", side <= l.status.width / 2f)
                }
            }
            // three characters fit at full size even by the generous estimate; four are scaled down together, but
            // not much (measured digits and the comma are narrower, so the real scale is closer to 1)
            for (value in listOf("9,8", "180")) {
                assertFits(FaceLayout.valueRowWidth(l, value, "+0,5", "20 мин"), l.value, "$value on $size")
            }
            for (value in listOf("12,4", "27,8", "500", FaceValues.NONE)) {
                for (age in listOf("20 min", "20 мин", "999 min")) {
                    val width = FaceLayout.valueRowWidth(l, value, "−10.5", age)
                    assertTrue("$value $age on $size", l.value.width / width >= 0.75f)
                }
            }
            // range labels at the graph's left end leave room for the dots
            assertTrue(FaceLayout.estimatedWidth("10,0", l.graphLabelSize) < l.graph.width / 4f)
        }
    }

    @Test
    fun theStatusRowPutsTheDateGapOnTheCentreAndKeepsTheBatteriesInside() {
        val row = FaceLayout.compute(450, 450).status
        // part 7e: the day ends and the month starts half the date's gap from the centre, where the system's dot is
        val place = FaceLayout.placeStatus(row, 28f, 40f, 50f, 50f, 8f, 22f, 11f)
        assertEquals(row.centerX - 4f - 28f, place.dayX, 0.01f)
        assertEquals(row.centerX + 4f, place.monthX, 0.01f)
        assertTrue(place.dayX + 28f < row.centerX && place.monthX > row.centerX)
        assertEquals(place.dayX - 22f - 50f, place.phoneX, 0.01f)
        assertEquals(place.monthX + 40f + 22f, place.watchX, 0.01f)
        // a wide battery: its gap narrows down to the least one first
        val wider = FaceLayout.placeStatus(row, 28f, 40f, 50f, row.right - row.centerX - 4f - 40f - 15f, 8f, 22f, 11f)
        assertEquals(place.monthX + 40f + 15f, wider.watchX, 0.01f)
        assertEquals(row.right, wider.watchX + row.right - row.centerX - 4f - 40f - 15f, 0.01f)
        val widest = FaceLayout.placeStatus(row, 28f, 40f, 50f, row.right - row.centerX - 4f - 40f - 11f, 8f, 22f, 11f)
        assertEquals(place.monthX + 40f + 11f, widest.watchX, 0.01f)
        // far too wide: pushed back to the row's edges
        val wide = FaceLayout.placeStatus(row, 28f, 40f, 400f, 400f, 8f, 22f, 11f)
        assertEquals(row.left, wide.phoneX, 0.01f)
        assertEquals(row.right - 400f, wide.watchX, 0.01f)
    }

    @Test
    fun theStatusRowHoldsLargerIconsAndNumbersBelowTheHourLabels() {
        // part 7e on 450 px: icons ~24 px, numbers ~25 px (were 21 and 20), the row taller, still under the hours
        val l = FaceLayout.compute(450, 450)
        assertEquals(24.3f, l.statusIcon, 0.5f)
        assertEquals(24.75f, l.status.textSize, 0.5f)
        assertTrue(l.statusIcon <= l.status.height && l.status.textSize <= l.status.height)
        assertTrue(l.hours.bottom <= l.status.top)
        // the system's dot (~0.86 of the diameter) is level with the row's top part, so the date gap is where it is
        assertTrue(0.86f * 450 >= l.status.top - 3f && 0.86f * 450 <= l.status.centerY)
    }

    @Test
    fun theValueRowIsCentredAndShrinksTogetherWhenTooWide() {
        val row = FaceLayout.compute(384, 384).value
        val fits = FaceLayout.placeValueRow(row, 100f, 30f, 50f, 10f)
        assertEquals(1f, fits.scale, 0.0001f)
        val total = 100f + 10f + 30f + 10f + 50f
        assertEquals(row.centerX - total / 2f, fits.valueX, 0.01f)
        assertEquals(fits.valueX + 110f, fits.arrowX, 0.01f)
        assertEquals(fits.arrowX + 40f, fits.sideX, 0.01f)
        // without an arrow the change follows the number after one gap
        val noArrow = FaceLayout.placeValueRow(row, 100f, 0f, 50f, 10f)
        assertEquals(noArrow.valueX + 110f, noArrow.sideX, 0.01f)
        // far too wide: scaled to the row exactly, still centred
        val wide = FaceLayout.placeValueRow(row, 600f, 60f, 200f, 20f)
        assertTrue(wide.scale < 1f)
        assertEquals(row.left, wide.valueX, 0.01f)
        assertEquals(row.right, wide.sideX + 200f * wide.scale, 0.01f)
    }

    @Test
    fun aTapOnTheValueButNotOnTheTimeTheStatusOrTheGraph() {
        for (size in sizes) {
            val l = FaceLayout.compute(size, size)
            val v = l.value
            assertTrue(FaceLayout.isOnValue(l, v.centerX, v.centerY))
            assertTrue(FaceLayout.isOnValue(l, v.left + 1f, v.bottom + v.height * 0.04f))
            assertFalse(FaceLayout.isOnValue(l, l.time.centerX, l.time.centerY))
            assertFalse(FaceLayout.isOnValue(l, l.status.centerX, l.status.centerY))
            assertFalse(FaceLayout.isOnValue(l, l.graph.centerX, l.graph.top + 0.2f * l.graph.height))
            assertFalse(FaceLayout.isOnValue(l, v.left - 5f, v.centerY))
        }
    }

    @Test
    fun aNonSquareScreenUsesTheCentredCircle() {
        val layout = FaceLayout.compute(480, 400)
        assertTrue(layout.centerX == 240f && layout.centerY == 200f && layout.radius == 200f)
        layout.rows.forEach { assertInsideCircle(layout, it, "row on 480x400") }
    }
}

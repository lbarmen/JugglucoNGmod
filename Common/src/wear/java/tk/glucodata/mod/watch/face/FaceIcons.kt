package tk.glucodata.mod.watch.face

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * JugglucoNGmod, watch block (part 7a, the phone redrawn in 7e): the battery icons of the face, drawn on the Canvas
 * without images - a phone (a narrow rounded body with a speaker slit at the top) and a watch (a round body with two
 * straps), each filled from the bottom up to its percent. The number beside an icon is the painter's. One instance
 * per painter, used on its thread.
 */
internal class FaceIcons {

    enum class Kind { PHONE, WATCH }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val box = RectF()

    /** The [kind] icon [height] px tall, its left edge at [left], centred on [centerY], filled to [percent]. */
    fun draw(canvas: Canvas, kind: Kind, percent: Int, left: Float, centerY: Float, height: Float, color: Int) {
        val line = (height * LINE).coerceAtLeast(1f)
        stroke.strokeWidth = line
        stroke.color = color
        fill.color = color
        val width = width(kind, height)
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        val share = fillShare(percent)
        when (kind) {
            Kind.PHONE -> {
                // part 7e (critic of the look): a phone, not a battery - well rounded corners, a speaker slit at the
                // top, the screen below it filled from the bottom
                box.set(left + line / 2f, top + line / 2f, left + width - line / 2f, bottom - line / 2f)
                val corner = width * 0.3f
                canvas.drawRoundRect(box, corner, corner, stroke)
                val speakerY = top + line * 2.4f
                val speaker = width * 0.34f
                box.set(left + (width - speaker) / 2f, speakerY - line * 0.45f, left + (width + speaker) / 2f,
                    speakerY + line * 0.45f)
                canvas.drawRoundRect(box, line, line, fill)
                val inset = line * 1.6f
                val innerTop = speakerY + line * 1.4f
                val innerBottom = bottom - inset - line * 0.6f
                val level = innerBottom - (innerBottom - innerTop) * share
                if (share > 0f) canvas.drawRect(left + inset, level, left + width - inset, innerBottom, fill)
            }
            Kind.WATCH -> {
                val cx = left + width / 2f
                val radius = width / 2f - line / 2f
                val strap = width * 0.56f
                // the straps above and below the body
                canvas.drawRect(cx - strap / 2f, top, cx + strap / 2f, centerY - radius + line, fill)
                canvas.drawRect(cx - strap / 2f, centerY + radius - line, cx + strap / 2f, bottom, fill)
                canvas.drawCircle(cx, centerY, radius, stroke)
                val inner = radius - line * 1.1f
                if (share > 0f && inner > 0f) {
                    canvas.save()
                    path.reset()
                    path.addCircle(cx, centerY, inner, Path.Direction.CW)
                    canvas.clipPath(path)
                    val level = centerY + inner - 2f * inner * share
                    canvas.drawRect(cx - inner, level, cx + inner, centerY + inner, fill)
                    canvas.restore()
                }
            }
        }
    }

    companion object {
        /**
         * Below this percent the icon and its number are red, and the dim mode still shows them (part 7c, the
         * owner's word: under 30 % red, under 50 % yellow).
         */
        const val LOW_PERCENT = 30

        /** Below this percent (and not low) the icon and its number are yellow (part 7c). */
        const val WARN_PERCENT = 50

        /** The outline's weight, share of the icon's height. */
        private const val LINE = 0.09f

        /** The icon's width for its [height]. */
        fun width(kind: Kind, height: Float): Float = height * if (kind == Kind.PHONE) 0.52f else 0.62f

        /**
         * A battery has an icon when its percent is known - in the dim mode too (owner's request 19.11.2026, part 7j;
         * before it the dim mode showed only a low one). [ambient] stays for the callers.
         */
        @Suppress("UNUSED_PARAMETER")
        fun shown(percent: Int?, ambient: Boolean): Boolean = percent != null && percent in 0..100

        fun isLow(percent: Int): Boolean = percent < LOW_PERCENT

        /** Yellow: under [WARN_PERCENT] but not [isLow]. */
        fun isWarn(percent: Int): Boolean = percent < WARN_PERCENT && !isLow(percent)

        /** How much of the icon is filled, 0..1. */
        fun fillShare(percent: Int): Float = percent.coerceIn(0, 100) / 100f
    }
}

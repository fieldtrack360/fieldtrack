package com.field360.traker.maps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * Marker bitmaps, drawn rather than shipped as drawables.
 *
 * Two reasons: the AAR carries no image assets a host has to theme around, and a
 * numbered pin needs its number rendered at runtime anyway.
 *
 * Descriptors are cheap to hold and expensive to rebuild, so callers should cache them —
 * [TrackRenderer] builds the chevron once per render pass.
 */
public object ArrowIcons {

    /** A direction chevron pointing "up"; the map rotates it to the arrow's bearing. */
    public fun chevron(sizePx: Int, color: Int = Color.WHITE): BitmapDescriptor {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(120, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = sizePx * OUTLINE_WIDTH_FRACTION
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = sizePx * STROKE_WIDTH_FRACTION
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path().apply {
            moveTo(sizePx * 0.25f, sizePx * 0.65f)
            lineTo(sizePx * 0.5f, sizePx * 0.32f)
            lineTo(sizePx * 0.75f, sizePx * 0.65f)
        }

        // Dark outline underneath so the chevron stays legible over any map tile.
        canvas.drawPath(path, outline)
        canvas.drawPath(path, fill)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /** White circle with the stop number and a downward pointer (spec §23.3). */
    public fun numberedPin(
        number: Int,
        sizePx: Int,
        fillColor: Int = Color.WHITE,
        textColor: Int = Color.BLACK,
    ): BitmapDescriptor {
        val height = (sizePx * PIN_HEIGHT_FRACTION).toInt()
        val bitmap = createBitmap(sizePx, height)
        val canvas = Canvas(bitmap)

        val radius = sizePx / 2f
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
            setShadowLayer(sizePx * 0.06f, 0f, sizePx * 0.03f, Color.argb(90, 0, 0, 0))
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.04f
        }

        canvas.drawCircle(radius, radius, radius * CIRCLE_INSET, circle)
        canvas.drawCircle(radius, radius, radius * CIRCLE_INSET, border)

        // Pointer down to the exact coordinate; the marker anchors at (0.5, 1).
        val pointer = Path().apply {
            moveTo(radius - sizePx * 0.16f, radius + radius * CIRCLE_INSET * 0.75f)
            lineTo(radius, height.toFloat())
            lineTo(radius + sizePx * 0.16f, radius + radius * CIRCLE_INSET * 0.75f)
            close()
        }
        canvas.drawPath(pointer, circle)

        val text = number.toString()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = sizePx * TEXT_SIZE_FRACTION
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val metrics = Rect()
        textPaint.getTextBounds(text, 0, text.length, metrics)
        canvas.drawText(text, radius, radius + metrics.height() / 2f, textPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    /**
     * The current-position puck: white ring, coloured disc, white heading notch. Drawn
     * pointing "up"; [LiveTrackRenderer] rotates the flat marker to the smoothed
     * heading (SMOOTH-NAV-PLAN Phase 3).
     */
    public fun puck(sizePx: Int, color: Int): BitmapDescriptor {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val centre = sizePx / 2f

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(sizePx * 0.08f, 0f, sizePx * 0.02f, Color.argb(100, 0, 0, 0))
        }
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.FILL
        }

        canvas.drawCircle(centre, centre, centre * PUCK_RING_INSET, ring)
        canvas.drawCircle(centre, centre, centre * PUCK_DISC_INSET, disc)

        // Heading notch: a triangle from just above centre to the disc's top edge.
        val tip = centre - centre * PUCK_DISC_INSET * 0.8f
        val base = centre - centre * PUCK_DISC_INSET * 0.1f
        val halfWidth = sizePx * 0.11f
        val arrow = Path().apply {
            moveTo(centre, tip)
            lineTo(centre - halfWidth, base)
            lineTo(centre + halfWidth, base)
            close()
        }
        canvas.drawPath(arrow, notch)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    private const val STROKE_WIDTH_FRACTION = 0.12f
    private const val OUTLINE_WIDTH_FRACTION = 0.20f
    private const val PUCK_RING_INSET = 0.92f
    private const val PUCK_DISC_INSET = 0.68f
    private const val PIN_HEIGHT_FRACTION = 1.35f
    private const val CIRCLE_INSET = 0.86f
    private const val TEXT_SIZE_FRACTION = 0.44f
}

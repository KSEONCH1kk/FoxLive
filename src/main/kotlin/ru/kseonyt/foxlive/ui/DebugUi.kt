package ru.kseonyt.foxlive.ui

import kotlin.math.cos
import kotlin.math.sin

/**
 * Immediate-mode debug UI + 2D canvas. Supports multiple textures via [useTexture] —
 * the renderer splits geometry into per-texture draw calls.
 */
object DebugUi {
    val frame = UiFrame()

    var mouseX: Float = 0f; private set
    var mouseY: Float = 0f; private set
    var mouseDown: Boolean = false; private set
    var mouseClicked: Boolean = false; private set
    var mouseReleased: Boolean = false; private set
    private var prevMouseDown: Boolean = false
    var activeId: String? = null
    var hotId: String? = null

    fun newFrame(
        font: FontAtlas,
        defaultDescriptorSet: Long,
        screenW: Float,
        screenH: Float,
        mouseX: Float = 0f,
        mouseY: Float = 0f,
        mouseDown: Boolean = false,
    ) {
        frame.reset(font, defaultDescriptorSet, screenW, screenH)
        this.mouseX = mouseX
        this.mouseY = mouseY
        mouseClicked = mouseDown && !prevMouseDown
        mouseReleased = !mouseDown && prevMouseDown
        this.mouseDown = mouseDown
        prevMouseDown = mouseDown
        // NOTE: do NOT clear activeId here — widgets need to see mouseReleased
        // while still active. They clear it themselves after triggering.
        hotId = null
    }

    inline fun panel(title: String, x: Float, y: Float, width: Float = 240f, block: () -> Unit) {
        frame.beginPanel(title, x, y, width)
        try { block() } finally { frame.endPanel() }
    }

    fun text(s: String, color: Int = 0xFFFFFFFF.toInt()) = frame.text(s, color)
    fun valueInt(label: String, value: Int) = frame.text("$label: $value")
    fun valueFloat(label: String, value: Float, dec: Int = 2) =
        frame.text("$label: ${"%.${dec}f".format(value)}")
    fun valueStr(label: String, value: String) = frame.text("$label: $value")
    fun separator() = frame.separator()
    fun spacer(h: Float = 4f) = frame.spacer(h)

    fun button(label: String): Boolean = frame.button(label)
    fun slider(label: String, value: Float, min: Float = 0f, max: Float = 1f): Float =
        frame.slider(label, value, min, max)

    /** Switch the active descriptor set for upcoming geometry. Pass back [useFont] to draw text. */
    fun useTexture(descriptorSet: Long) = frame.useTexture(descriptorSet)
    fun useFont() = frame.useFont()

    // Canvas primitives (use the font/white texture)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int = 0xFFFFFFFF.toInt(), thickness: Float = 1f) =
        frame.line(x0, y0, x1, y1, color, thickness)
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = frame.rect(x, y, w, h, color)
    fun circle(cx: Float, cy: Float, radius: Float, color: Int, segments: Int = 24) =
        frame.circle(cx, cy, radius, color, segments)
    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) =
        frame.triangle(x0, y0, x1, y1, x2, y2, color)
    fun roundedRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) =
        frame.roundedRect(x, y, w, h, radius, color)
    fun arcFan(cx: Float, cy: Float, radius: Float, startRad: Float, endRad: Float,
               color: Int, segments: Int = 8) =
        frame.arcFan(cx, cy, radius, startRad, endRad, color, segments)
    fun textScaled(s: String, x: Float, y: Float, scale: Float, color: Int = 0xFFFFFFFF.toInt()) =
        frame.textScaled(s, x, y, scale, color)
    fun textRotated180(s: String, cx: Float, cy: Float, scale: Float, color: Int = 0xFFFFFFFF.toInt()) =
        frame.textRotated180(s, cx, cy, scale, color)

    // Sprite drawing — uses the currently bound texture (caller MUST useTexture(...) first)
    fun sprite(x: Float, y: Float, w: Float, h: Float,
               u0: Float, v0: Float, u1: Float, v1: Float,
               color: Int = 0xFFFFFFFF.toInt()) =
        frame.sprite(x, y, w, h, u0, v0, u1, v1, color)

    fun spriteRotated(cx: Float, cy: Float, w: Float, h: Float, angleRad: Float,
                      u0: Float, v0: Float, u1: Float, v1: Float,
                      color: Int = 0xFFFFFFFF.toInt()) =
        frame.spriteRotated(cx, cy, w, h, angleRad, u0, v0, u1, v1, color)

    fun textCentered(s: String, cx: Float, y: Float, color: Int = 0xFFFFFFFF.toInt()) =
        frame.textAt(s, cx - s.length * frame.cellWidth() * 0.5f, y, color)

    fun textAt(s: String, x: Float, y: Float, color: Int = 0xFFFFFFFF.toInt()) =
        frame.textAt(s, x, y, color)
}

class UiDrawCall(val descriptorSet: Long, val firstIndex: Int, var indexCount: Int)

class UiFrame {
    private lateinit var font: FontAtlas
    private var defaultSet: Long = 0L
    private var currentSet: Long = 0L
    private var screenW: Float = 0f
    private var screenH: Float = 0f

    private var panelX = 0f; private var panelY = 0f; private var panelW = 0f
    private var cursorY = 0f
    private var panelBgVertOffset: Int = 0
    private var inPanel = false
    private val padding = 6f
    private val titleHeight: Float get() = font.cellH.toFloat() + 2f
    private val lineHeight: Float get() = font.cellH.toFloat()

    val vertices = FloatArray(MAX_VERTS * FLOATS_PER_VERTEX)
    val indices = IntArray(MAX_INDICES)
    var vertCount: Int = 0
    var indexCount: Int = 0
    val drawCalls: ArrayList<UiDrawCall> = ArrayList(8)

    companion object {
        const val MAX_VERTS = 65536
        const val MAX_INDICES = MAX_VERTS * 6 / 4
        const val FLOATS_PER_VERTEX = 8
    }

    fun cellWidth(): Float = font.cellW.toFloat()
    fun cellHeight(): Float = font.cellH.toFloat()

    fun reset(font: FontAtlas, defaultDescriptorSet: Long, screenW: Float, screenH: Float) {
        this.font = font
        this.screenW = screenW
        this.screenH = screenH
        this.defaultSet = defaultDescriptorSet
        this.currentSet = defaultDescriptorSet
        vertCount = 0
        indexCount = 0
        drawCalls.clear()
        inPanel = false
    }

    fun useTexture(set: Long) {
        if (set == currentSet) return
        currentSet = set
        // Force a new draw call by appending an empty one
        drawCalls += UiDrawCall(set, indexCount, 0)
    }

    fun useFont() = useTexture(defaultSet)

    private fun ensureDrawCall() {
        if (drawCalls.isEmpty() || drawCalls.last().descriptorSet != currentSet) {
            drawCalls += UiDrawCall(currentSet, indexCount, 0)
        }
    }

    fun beginPanel(title: String, x: Float, y: Float, width: Float) {
        if (inPanel) endPanel()
        useFont()
        inPanel = true
        panelX = x; panelY = y; panelW = width
        cursorY = y + padding + titleHeight
        panelBgVertOffset = vertCount
        val w = font.whiteUV()
        emitQuad(x, y, width, padding * 2 + titleHeight, w[0], w[1], w[0], w[1],
            0.07f, 0.08f, 0.10f, 0.84f)
        emitQuad(x, y + titleHeight + 2f, width, 1f, w[0], w[1], w[0], w[1],
            1f, 1f, 1f, 0.18f)
        drawString(title, x + padding, y + padding * 0.5f, 1f, 1f, 1f, 1f)
    }

    fun endPanel() {
        if (!inPanel) return
        inPanel = false
        val newHeight = cursorY - panelY + padding
        val bottomVertY = panelY + newHeight
        val brIdx = (panelBgVertOffset + 2) * FLOATS_PER_VERTEX + 1
        val blIdx = (panelBgVertOffset + 3) * FLOATS_PER_VERTEX + 1
        vertices[brIdx] = bottomVertY
        vertices[blIdx] = bottomVertY
    }

    fun text(s: String, color: Int = 0xFFFFFFFF.toInt()) {
        if (!inPanel) return
        useFont()
        val (r, g, b, a) = unpackColor(color)
        drawString(s, panelX + padding, cursorY, r, g, b, a)
        cursorY += lineHeight
    }

    fun textAt(s: String, x: Float, y: Float, color: Int) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        drawString(s, x, y, r, g, b, a)
    }

    fun separator() {
        if (!inPanel) return
        useFont()
        val w = font.whiteUV()
        emitQuad(panelX + padding, cursorY + 2f, panelW - padding * 2, 1f,
            w[0], w[1], w[0], w[1], 1f, 1f, 1f, 0.25f)
        cursorY += 6f
    }

    fun spacer(h: Float) {
        if (!inPanel) return
        cursorY += h
    }

    fun button(label: String): Boolean {
        if (!inPanel) return false
        useFont()
        val bx = panelX + padding
        val by = cursorY + 2f
        val bw = panelW - padding * 2
        val bh = lineHeight + 6f
        cursorY += bh + 4f

        val mx = DebugUi.mouseX; val my = DebugUi.mouseY
        val hovered = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh
        val isActive = DebugUi.activeId == label

        if (hovered) DebugUi.hotId = label
        if (hovered && DebugUi.mouseClicked) DebugUi.activeId = label

        var triggered = false
        if (DebugUi.mouseReleased) {
            if (isActive) {
                if (hovered) triggered = true
                DebugUi.activeId = null
            }
        }

        val w = font.whiteUV()
        val (r, g, b) = when {
            isActive && hovered -> Triple(0.55f, 0.65f, 0.85f)
            hovered             -> Triple(0.32f, 0.38f, 0.52f)
            else                -> Triple(0.20f, 0.22f, 0.30f)
        }
        emitQuad(bx, by, bw, bh, w[0], w[1], w[0], w[1], r, g, b, 0.95f)
        emitQuad(bx, by, bw, 1f, w[0], w[1], w[0], w[1], 1f, 1f, 1f, 0.18f)
        emitQuad(bx, by + bh - 1f, bw, 1f, w[0], w[1], w[0], w[1], 0f, 0f, 0f, 0.35f)

        val textX = (bx + (bw - label.length * font.cellW) * 0.5f).coerceAtLeast(bx + 2f)
        val textY = by + (bh - lineHeight) * 0.5f
        drawString(label, textX, textY, 1f, 1f, 1f, 1f)
        return triggered
    }

    fun slider(label: String, value: Float, min: Float, max: Float): Float {
        if (!inPanel) return value
        useFont()
        // Label line above slider
        drawString("$label: ${"%.2f".format(value.coerceIn(min, max))}",
            panelX + padding, cursorY, 1f, 1f, 1f, 1f)
        cursorY += lineHeight + 1f

        val sx = panelX + padding
        val sy = cursorY + 2f
        val sw = panelW - padding * 2
        val sh = lineHeight + 4f
        cursorY += sh + 4f

        val w = font.whiteUV()
        emitQuad(sx, sy + sh * 0.4f, sw, sh * 0.2f, w[0], w[1], w[0], w[1],
            0.10f, 0.10f, 0.13f, 1f)

        val range = (max - min).coerceAtLeast(1e-6f)
        var v = value.coerceIn(min, max)

        val mx = DebugUi.mouseX; val my = DebugUi.mouseY
        val hovered = mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh
        val isActive = DebugUi.activeId == label
        if (hovered) DebugUi.hotId = label
        if (hovered && DebugUi.mouseClicked) DebugUi.activeId = label
        if (isActive && DebugUi.mouseDown) {
            val t = ((mx - sx) / sw).coerceIn(0f, 1f)
            v = min + t * range
        }
        if (DebugUi.mouseReleased && isActive) DebugUi.activeId = null

        val tNorm = ((v - min) / range).coerceIn(0f, 1f)
        val handleX = sx + tNorm * sw - 4f
        val (hr, hg, hb) = when {
            isActive -> Triple(0.85f, 0.92f, 1f)
            hovered  -> Triple(0.65f, 0.78f, 0.95f)
            else     -> Triple(0.45f, 0.58f, 0.85f)
        }
        emitQuad(handleX, sy, 8f, sh, w[0], w[1], w[0], w[1], hr, hg, hb, 1f)
        return v
    }

    // ─── Canvas primitives ──────────────────────────────────────────────────

    fun rect(x: Float, y: Float, wf: Float, hf: Float, color: Int) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        val w = font.whiteUV()
        emitQuad(x, y, wf, hf, w[0], w[1], w[0], w[1], r, g, b, a)
    }

    fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, thickness: Float) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        val w = font.whiteUV()
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) return
        val nx = -dy / len * thickness * 0.5f
        val ny = dx / len * thickness * 0.5f
        if (vertCount + 4 > MAX_VERTS || indexCount + 6 > MAX_INDICES) return
        ensureDrawCall()
        putVert(x0 + nx, y0 + ny, w[0], w[1], r, g, b, a)
        putVert(x1 + nx, y1 + ny, w[0], w[1], r, g, b, a)
        putVert(x1 - nx, y1 - ny, w[0], w[1], r, g, b, a)
        putVert(x0 - nx, y0 - ny, w[0], w[1], r, g, b, a)
        val base = vertCount - 4
        emitIndex(base); emitIndex(base + 1); emitIndex(base + 2)
        emitIndex(base); emitIndex(base + 2); emitIndex(base + 3)
    }

    fun circle(cx: Float, cy: Float, radius: Float, color: Int, segments: Int) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        val w = font.whiteUV()
        val seg = segments.coerceAtLeast(3)
        if (vertCount + seg + 2 > MAX_VERTS || indexCount + seg * 3 > MAX_INDICES) return
        ensureDrawCall()
        val centerIdx = vertCount
        putVert(cx, cy, w[0], w[1], r, g, b, a)
        val twoPi = (2.0 * Math.PI).toFloat()
        for (i in 0..seg) {
            val ang = i.toFloat() / seg * twoPi
            putVert(cx + cos(ang) * radius, cy + sin(ang) * radius, w[0], w[1], r, g, b, a)
        }
        for (i in 0 until seg) {
            emitIndex(centerIdx)
            emitIndex(centerIdx + 1 + i)
            emitIndex(centerIdx + 2 + i)
        }
    }

    fun triangle(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        val w = font.whiteUV()
        if (vertCount + 3 > MAX_VERTS || indexCount + 3 > MAX_INDICES) return
        ensureDrawCall()
        putVert(x0, y0, w[0], w[1], r, g, b, a)
        putVert(x1, y1, w[0], w[1], r, g, b, a)
        putVert(x2, y2, w[0], w[1], r, g, b, a)
        val base = vertCount - 3
        emitIndex(base); emitIndex(base + 1); emitIndex(base + 2)
    }

    /**
     * Triangle-fan arc from (cx, cy) sweeping angles [startRad, endRad].
     * Screen-space convention: angle 0 = +X, π/2 = +Y (down), π = -X, 3π/2 = -Y (up).
     */
    fun arcFan(cx: Float, cy: Float, radius: Float, startRad: Float, endRad: Float,
               color: Int, segments: Int) {
        useFont()
        val (r, g, b, a) = unpackColor(color)
        val w = font.whiteUV()
        val seg = segments.coerceAtLeast(2)
        if (vertCount + seg + 2 > MAX_VERTS || indexCount + seg * 3 > MAX_INDICES) return
        ensureDrawCall()
        val centerIdx = vertCount
        putVert(cx, cy, w[0], w[1], r, g, b, a)
        for (i in 0..seg) {
            val t = i.toFloat() / seg
            val angle = startRad + (endRad - startRad) * t
            putVert(cx + kotlin.math.cos(angle) * radius,
                    cy + kotlin.math.sin(angle) * radius,
                    w[0], w[1], r, g, b, a)
        }
        for (i in 0 until seg) {
            emitIndex(centerIdx); emitIndex(centerIdx + 1 + i); emitIndex(centerIdx + 2 + i)
        }
    }

    /**
     * Filled rounded rectangle composed from 3 axis-aligned bands + 4 arc-fan corners.
     * Looks identical to CSS `border-radius`.
     */
    fun roundedRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        val r = radius.coerceAtMost(minOf(w, h) * 0.5f).coerceAtLeast(0f)
        if (r <= 0.5f) { rect(x, y, w, h, color); return }
        // Bands
        rect(x + r, y, w - 2f * r, r, color)              // top edge band
        rect(x, y + r, w, h - 2f * r, color)              // middle full-width
        rect(x + r, y + h - r, w - 2f * r, r, color)      // bottom edge band
        // Corner quadrants (screen-down convention)
        val pi = Math.PI.toFloat()
        val seg = (r.toInt().coerceAtLeast(4)).coerceAtMost(12)
        arcFan(x + r,     y + r,     r, pi,           pi * 1.5f, color, seg)  // TL
        arcFan(x + w - r, y + r,     r, pi * 1.5f,    pi * 2f,   color, seg)  // TR
        arcFan(x + w - r, y + h - r, r, 0f,           pi * 0.5f, color, seg)  // BR
        arcFan(x + r,     y + h - r, r, pi * 0.5f,    pi,        color, seg)  // BL
    }

    /** Draw text at an arbitrary scale. cellW/cellH grow proportionally. */
    fun textScaled(s: String, x: Float, y: Float, scale: Float, color: Int) {
        val (r, g, b, a) = unpackColor(color)
        val w = font.cellW.toFloat() * scale
        val h = font.cellH.toFloat() * scale
        var penX = x
        for (c in s) {
            val uv = font.glyphUV(c)
            if (uv != null) emitQuad(penX, y, w, h, uv[0], uv[1], uv[2], uv[3], r, g, b, a)
            penX += w
        }
    }

    /**
     * Draw text rotated 180° as if read upside-down — used for the bottom-right
     * corner of a playing card. Pivots around (cx, cy).
     */
    fun textRotated180(s: String, cx: Float, cy: Float, scale: Float, color: Int) {
        val (r, g, b, a) = unpackColor(color)
        val cw = font.cellW.toFloat() * scale
        val ch = font.cellH.toFloat() * scale
        val totalW = s.length * cw
        // Glyphs in reverse order so the upside-down read is correct
        var pen = cx - totalW * 0.5f
        for (c in s.reversed()) {
            val uv = font.glyphUV(c)
            if (uv != null) {
                if (vertCount + 4 > MAX_VERTS || indexCount + 6 > MAX_INDICES) return
                ensureDrawCall()
                val gcx = pen + cw * 0.5f
                val xL = gcx - cw * 0.5f
                val xR = gcx + cw * 0.5f
                val yT = cy - ch * 0.5f
                val yB = cy + ch * 0.5f
                putVert(xL, yT, uv[2], uv[3], r, g, b, a)
                putVert(xR, yT, uv[0], uv[3], r, g, b, a)
                putVert(xR, yB, uv[0], uv[1], r, g, b, a)
                putVert(xL, yB, uv[2], uv[1], r, g, b, a)
                val base = vertCount - 4
                emitIndex(base); emitIndex(base + 1); emitIndex(base + 2)
                emitIndex(base); emitIndex(base + 2); emitIndex(base + 3)
            }
            pen += cw
        }
    }

    // ─── Sprites (uses currently bound texture set; caller switches with useTexture) ───

    fun sprite(x: Float, y: Float, w: Float, h: Float,
               u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        val (r, g, b, a) = unpackColor(color)
        emitQuad(x, y, w, h, u0, v0, u1, v1, r, g, b, a)
    }

    fun spriteRotated(cx: Float, cy: Float, w: Float, h: Float, angleRad: Float,
                      u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        if (vertCount + 4 > MAX_VERTS || indexCount + 6 > MAX_INDICES) return
        ensureDrawCall()
        val (r, g, b, a) = unpackColor(color)
        val hw = w * 0.5f; val hh = h * 0.5f
        val c = cos(angleRad); val s = sin(angleRad)
        // 4 corners around (cx, cy): (-hw,-hh), (hw,-hh), (hw,hh), (-hw,hh)
        fun rotXY(lx: Float, ly: Float, dx: FloatArray) {
            dx[0] = cx + lx * c - ly * s
            dx[1] = cy + lx * s + ly * c
        }
        val out = FloatArray(2)
        rotXY(-hw, -hh, out); putVert(out[0], out[1], u0, v0, r, g, b, a)
        rotXY( hw, -hh, out); putVert(out[0], out[1], u1, v0, r, g, b, a)
        rotXY( hw,  hh, out); putVert(out[0], out[1], u1, v1, r, g, b, a)
        rotXY(-hw,  hh, out); putVert(out[0], out[1], u0, v1, r, g, b, a)
        val base = vertCount - 4
        emitIndex(base); emitIndex(base + 1); emitIndex(base + 2)
        emitIndex(base); emitIndex(base + 2); emitIndex(base + 3)
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private fun unpackColor(color: Int): FloatArray {
        val a = ((color ushr 24) and 0xFF) / 255f
        val r = ((color ushr 16) and 0xFF) / 255f
        val g = ((color ushr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return floatArrayOf(r, g, b, a)
    }

    private fun drawString(s: String, x: Float, y: Float, r: Float, g: Float, bl: Float, a: Float) {
        var penX = x
        val cw = font.cellW.toFloat()
        val ch = font.cellH.toFloat()
        for (c in s) {
            val uv = font.glyphUV(c)
            if (uv != null) {
                emitQuad(penX, y, cw, ch, uv[0], uv[1], uv[2], uv[3], r, g, bl, a)
            }
            penX += cw  // advance pen even for missing glyphs to preserve layout
        }
    }

    private fun emitQuad(x: Float, y: Float, w: Float, h: Float,
                         u0: Float, v0: Float, u1: Float, v1: Float,
                         r: Float, g: Float, bl: Float, a: Float) {
        if (vertCount + 4 > MAX_VERTS || indexCount + 6 > MAX_INDICES) return
        ensureDrawCall()
        putVert(x,     y,     u0, v0, r, g, bl, a)
        putVert(x + w, y,     u1, v0, r, g, bl, a)
        putVert(x + w, y + h, u1, v1, r, g, bl, a)
        putVert(x,     y + h, u0, v1, r, g, bl, a)
        val base = vertCount - 4
        emitIndex(base); emitIndex(base + 1); emitIndex(base + 2)
        emitIndex(base); emitIndex(base + 2); emitIndex(base + 3)
    }

    private fun emitIndex(i: Int) {
        indices[indexCount] = i
        indexCount++
        drawCalls.last().indexCount++
    }

    private fun putVert(x: Float, y: Float, u: Float, v: Float,
                        r: Float, g: Float, bl: Float, a: Float) {
        val o = vertCount * FLOATS_PER_VERTEX
        vertices[o] = x; vertices[o + 1] = y
        vertices[o + 2] = u; vertices[o + 3] = v
        vertices[o + 4] = r; vertices[o + 5] = g; vertices[o + 6] = bl; vertices[o + 7] = a
        vertCount++
    }
}

private operator fun FloatArray.component1(): Float = this[0]
private operator fun FloatArray.component2(): Float = this[1]
private operator fun FloatArray.component3(): Float = this[2]
private operator fun FloatArray.component4(): Float = this[3]

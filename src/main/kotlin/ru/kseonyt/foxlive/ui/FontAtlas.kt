package ru.kseonyt.foxlive.ui

import org.lwjgl.system.MemoryUtil
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Glyph atlas baked from a chain of system fonts. For each requested character we use
 * the first font in [fontChain] that can display it (via Font.canDisplay) — this keeps
 * Cyrillic, Latin and dingbat symbols all rendered cleanly without falling back to the
 * notdef "tofu" box. Glyphs whose font chain has no support are simply not baked, and
 * [glyphUV] returns null for them so text rendering can skip without leaving boxes.
 */
class FontAtlas(
    val pixels: ByteBuffer,
    val width: Int,
    val height: Int,
    val cellW: Int,
    val cellH: Int,
    val ascent: Int,
    private val glyphs: Map<Char, IntArray>,
    private val whiteCol: Int,
    private val whiteRow: Int,
    val cols: Int,
    val rows: Int,
) {
    fun glyphUV(c: Char): FloatArray? {
        val pos = glyphs[c] ?: return null
        val col = pos[0]; val row = pos[1]
        val u0 = (col * cellW).toFloat() / width
        val v0 = (row * cellH).toFloat() / height
        val u1 = u0 + cellW.toFloat() / width
        val v1 = v0 + cellH.toFloat() / height
        return floatArrayOf(u0, v0, u1, v1)
    }

    fun whiteUV(): FloatArray {
        val cx = (whiteCol * cellW + 0.5f) / width
        val cy = (whiteRow * cellH + 0.5f) / height
        return floatArrayOf(cx, cy)
    }

    fun destroy() = MemoryUtil.memFree(pixels)

    companion object {
        // All special chars expressed as \u escapes — bypasses any source-encoding ambiguity.
        private val SUITS = listOf(
            '♠', // ♠ spade
            '♥', // ♥ heart
            '♦', // ♦ diamond
            '♣', // ♣ club
        )
        private val SYMBOLS = listOf(
            '№', // № numero
            '€', // € euro
            '★', // ★ star
            '✦', // ✦ four-point star
            '❄', // ❄ snowflake
            '✕', // ✕ multiplication X
            '←', // ← left arrow
            '→', // → right arrow
            '↑', // ↑ up arrow
            '↓', // ↓ down arrow
            '•', // • bullet
            '▶', // ▶ play
            '◀', // ◀ play left
            '·', // · middle dot
            '…', // … ellipsis
            '—', // — em dash
            '–', // – en dash
            '‹', // ‹ single left quote
            '›', // › single right quote
        )
        private val CYRILLIC_SUPPL = listOf('Ё', 'ё') // Ё ё
        private const val CYRILLIC_RANGE_START = 0x0410 // А
        private const val CYRILLIC_RANGE_END = 0x044F   // я

        fun build(pointSize: Int = 14): FontAtlas {
            // Font chain — first font that can display a char wins.
            val fontChain = buildFontChain(pointSize)

            // Metrics ALWAYS come from a guaranteed-monospace font so cellW stays tight.
            // (Otherwise picking e.g. "Segoe UI Symbol" as primary inflates the cell to
            // its proportional 'M' width and every text label gets huge gaps.)
            val metricsFont = Font(Font.MONOSPACED, Font.PLAIN, pointSize)
            val tmp = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val tg = tmp.createGraphics()
            tg.font = metricsFont
            val fm = tg.fontMetrics
            val cellW = maxOf(fm.charWidth('M'), 7)
            val cellH = maxOf(fm.height, 14)
            val ascent = fm.ascent
            tg.dispose()

            // Charset
            val cs = ArrayList<Char>(256)
            for (i in 32..126) cs += i.toChar()
            cs += CYRILLIC_SUPPL
            for (i in CYRILLIC_RANGE_START..CYRILLIC_RANGE_END) cs += i.toChar()
            cs += SUITS
            cs += SYMBOLS

            // Filter to chars at least one font in the chain can display
            val displayable = cs.filter { c -> fontChain.any { it.canDisplay(c) } }

            val cols = 16
            val cells = displayable.size + 1
            val rows = ceil(cells / cols.toDouble()).toInt()
            val w = cellW * cols
            val h = cellH * rows

            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, w, h)
            g.composite = AlphaComposite.SrcOver
            g.color = Color.WHITE
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            val glyphs = HashMap<Char, IntArray>(displayable.size)
            for ((idx, ch) in displayable.withIndex()) {
                val font = fontChain.first { it.canDisplay(ch) }
                g.font = font
                val gx = (idx % cols) * cellW
                val gy = (idx / cols) * cellH
                val charWidth = g.fontMetrics.charWidth(ch)
                val drawX = gx + (cellW - charWidth) / 2
                g.drawString(ch.toString(), drawX, gy + ascent)
                glyphs[ch] = intArrayOf(idx % cols, idx / cols)
            }

            // Reserve last cell as solid white
            val whiteIdx = displayable.size
            val whiteCol = whiteIdx % cols
            val whiteRow = whiteIdx / cols
            g.color = Color(255, 255, 255, 255)
            g.fillRect(whiteCol * cellW, whiteRow * cellH, cellW, cellH)
            g.dispose()

            // Convert ARGB → RGBA bytes for Vulkan
            val argb = IntArray(w * h)
            img.getRGB(0, 0, w, h, argb, 0, w)
            val buf = MemoryUtil.memAlloc(w * h * 4)
            for (px in argb) {
                val a = (px ushr 24) and 0xFF
                val r = (px ushr 16) and 0xFF
                val gr = (px ushr 8) and 0xFF
                val b = px and 0xFF
                buf.put(r.toByte()).put(gr.toByte()).put(b.toByte()).put(a.toByte())
            }
            buf.flip()
            return FontAtlas(buf, w, h, cellW, cellH, ascent, glyphs, whiteCol, whiteRow, cols, rows)
        }

        private fun buildFontChain(size: Int): List<Font> {
            // 1) optional user TTF override from resources
            val ttfFonts = ArrayList<Font>()
            val cl = FontAtlas::class.java.classLoader
            for (path in listOf("fonts/ui.ttf", "fonts/font.ttf", "fonts/PressStart2P-Regular.ttf")) {
                val stream = cl.getResourceAsStream(path) ?: continue
                try {
                    val base = Font.createFont(Font.TRUETYPE_FONT, stream)
                    ttfFonts += base.deriveFont(Font.PLAIN, size.toFloat())
                } catch (_: Exception) { /* ignore */ }
                finally { try { stream.close() } catch (_: Exception) {} }
            }
            // 2) system Unicode-rich fonts in priority order
            val available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames.toHashSet()
            // Monospace fonts FIRST — they handle Latin/Cyrillic and the cell stays tight.
            // Symbol/emoji fonts come last as fallback for characters monospace can't draw.
            val candidates = listOf(
                "Cascadia Mono",       // monospace, Win11 default
                "Cascadia Code",
                "Consolas",
                "Courier New",
                "DejaVu Sans Mono",
                Font.MONOSPACED,
                "Segoe UI Symbol",     // dingbats / suits / arrows fallback
                "Segoe UI Emoji",
                "Arial Unicode MS",
                "Lucida Sans Unicode",
                "DejaVu Sans",
                "Segoe UI",
                Font.SANS_SERIF,
            )
            val sysFonts = candidates
                .distinct()
                .filter { it == Font.MONOSPACED || it == Font.SANS_SERIF || it in available }
                .map { Font(it, Font.PLAIN, size) }
            return ttfFonts + sysFonts
        }
    }
}

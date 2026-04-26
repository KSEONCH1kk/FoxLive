package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryUtil
import ru.kseonyt.foxlive.game.Card
import ru.kseonyt.foxlive.game.Rank
import ru.kseonyt.foxlive.game.Suit
import ru.kseonyt.foxlive.ui.UiRenderer
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage

/**
 * 13×4 procedural playing-card atlas (52 face-up cards) baked at startup via Java AWT.
 * Diamond suit gets a distinct orange color from hearts (matches the user's mockup).
 * Number cards use a centered pip grid; face cards use a large rank letter art.
 */
class CardAtlas private constructor(
    val texture: Texture,
    val descriptorSet: Long,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val cardW: Int,
    val cardH: Int,
) {
    fun uvOf(card: Card): FloatArray {
        val col = card.rank.ordinal
        val row = card.suit.ordinal
        val u0 = (col * cardW).toFloat() / atlasWidth
        val v0 = (row * cardH).toFloat() / atlasHeight
        val u1 = u0 + cardW.toFloat() / atlasWidth
        val v1 = v0 + cardH.toFloat() / atlasHeight
        return floatArrayOf(u0, v0, u1, v1)
    }

    fun destroy(ctx: VulkanContext) = texture.destroy(ctx)

    companion object {
        fun build(ctx: VulkanContext, ui: UiRenderer, cardW: Int = 80, cardH: Int = 118): CardAtlas {
            val cols = 13; val rows = 4
            val w = cardW * cols; val h = cardH * rows
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, w, h)
            g.composite = AlphaComposite.SrcOver
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            for (s in Suit.values()) for (r in Rank.values()) {
                val cx = r.ordinal * cardW
                val cy = s.ordinal * cardH
                drawCard(g, cx, cy, cardW, cardH, s, r)
            }
            g.dispose()

            val argb = IntArray(w * h)
            img.getRGB(0, 0, w, h, argb, 0, w)
            val buf = MemoryUtil.memAlloc(w * h * 4)
            try {
                for (px in argb) {
                    val a = (px ushr 24) and 0xFF
                    val r = (px ushr 16) and 0xFF
                    val gr = (px ushr 8) and 0xFF
                    val b = px and 0xFF
                    buf.put(r.toByte()).put(gr.toByte()).put(b.toByte()).put(a.toByte())
                }
                buf.flip()
                val tex = VulkanImage.createRGBA(ctx, w, h, buf)
                val set = ui.registerTexture(tex.view, tex.sampler)
                return CardAtlas(tex, set, w, h, cardW, cardH)
            } finally {
                MemoryUtil.memFree(buf)
            }
        }

        private fun suitColor(s: Suit): Color = when (s) {
            Suit.HEARTS -> Color(0xD3, 0x2F, 0x2F)
            Suit.DIAMONDS -> Color(0xC0, 0x70, 0x00)
            Suit.SPADES, Suit.CLUBS -> Color(0x1A, 0x1A, 0x2E)
        }

        private fun drawCard(g: java.awt.Graphics2D, x: Int, y: Int, w: Int, h: Int, suit: Suit, rank: Rank) {
            val pad = 2

            // Drop shadow
            g.color = Color(0, 0, 0, 80)
            g.fillRoundRect(x + pad + 1, y + pad + 2, w - pad * 2, h - pad * 2, 10, 10)

            // Card body
            g.color = Color(0xFA, 0xFB, 0xFF)
            g.fillRoundRect(x + pad, y + pad, w - pad * 2, h - pad * 2, 10, 10)

            // Border
            g.color = Color(0xD0, 0xD8, 0xE8)
            g.stroke = BasicStroke(1.4f)
            g.drawRoundRect(x + pad, y + pad, w - pad * 2 - 1, h - pad * 2 - 1, 10, 10)

            val color = suitColor(suit)
            g.color = color

            // Top-left rank
            val rankFontSize = (h * 0.13f).toInt().coerceAtLeast(11)
            val rankFont = Font(Font.SANS_SERIF, Font.BOLD, rankFontSize)
            g.font = rankFont
            val rfm = g.fontMetrics
            g.drawString(rank.short, x + 5, y + 5 + rfm.ascent)

            // Top-left tiny suit
            val smallFontSize = (h * 0.10f).toInt().coerceAtLeast(9)
            val smallFont = Font(Font.SANS_SERIF, Font.PLAIN, smallFontSize)
            g.font = smallFont
            val sfm = g.fontMetrics
            // Center the suit char under the rank
            val symW = sfm.stringWidth(suit.symbol)
            val rankW = rfm.stringWidth(rank.short)
            g.drawString(suit.symbol,
                x + 5 + (rankW - symW) / 2,
                y + 5 + rfm.ascent + sfm.ascent - 2)

            // Center artwork
            if (rank.isFace() || rank == Rank.ACE) {
                drawCenterLetter(g, x, y, w, h, rank, suit, color)
            } else {
                drawPipGrid(g, x, y, w, h, suit.symbol, color, rank.value)
            }

            // Bottom-right corner — rotated 180°
            val rotG = g.create() as java.awt.Graphics2D
            rotG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            rotG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            rotG.color = color
            val tx = AffineTransform()
            tx.translate((x + w - 5).toDouble(), (y + h - 5).toDouble())
            tx.rotate(Math.PI)
            rotG.transform = tx
            rotG.font = rankFont
            rotG.drawString(rank.short, 0, rfm.ascent - rfm.descent)
            rotG.font = smallFont
            rotG.drawString(suit.symbol,
                (rankW - symW) / 2,
                rfm.ascent + sfm.ascent - rfm.descent + 1)
            rotG.dispose()
        }

        private fun drawCenterLetter(
            g: java.awt.Graphics2D, x: Int, y: Int, w: Int, h: Int,
            rank: Rank, suit: Suit, color: Color,
        ) {
            // Faded big rank letter
            val faded = Color(color.red, color.green, color.blue, 60)
            val art = if (rank == Rank.ACE) suit.symbol else rank.short
            val sz = (h * 0.45f).toInt()
            g.font = Font(Font.SANS_SERIF, Font.BOLD, sz)
            val fm = g.fontMetrics
            val tw = fm.stringWidth(art)
            g.color = faded
            g.drawString(art, x + (w - tw) / 2, y + h / 2 + fm.ascent / 2 - 4)
            // Crisp small suit symbols at top-center / bottom-center for face cards (decoration)
            if (rank.isFace()) {
                g.color = color
                val sFont = Font(Font.SANS_SERIF, Font.PLAIN, (h * 0.18f).toInt())
                g.font = sFont
                val sfm = g.fontMetrics
                val sw = sfm.stringWidth(suit.symbol)
                g.drawString(suit.symbol, x + (w - sw) / 2, y + h - 18)
            }
        }

        private fun drawPipGrid(
            g: java.awt.Graphics2D, x: Int, y: Int, w: Int, h: Int,
            symbol: String, color: Color, count: Int,
        ) {
            // Position pips on a 3-column×4-row layout following standard playing-card patterns.
            val pipFont = Font(Font.SANS_SERIF, Font.PLAIN, (h * 0.13f).toInt())
            g.font = pipFont
            val fm = g.fontMetrics
            val sw = fm.stringWidth(symbol)
            val sh = fm.ascent
            // Columns
            val xL = x + w * 1 / 4 - sw / 2
            val xC = x + w / 2 - sw / 2
            val xR = x + w * 3 / 4 - sw / 2
            // Rows (5 vertical positions for distribution)
            val rowYs = intArrayOf(
                y + h * 22 / 100 + sh,
                y + h * 36 / 100 + sh,
                y + h * 50 / 100 + sh,
                y + h * 64 / 100 + sh,
                y + h * 78 / 100 + sh,
            )
            // Standard playing card pip layouts (col,row) coordinates per pip count
            val layout: List<Pair<Int, Int>> = when (count) {
                2 -> listOf(1 to 0, 1 to 4)
                3 -> listOf(1 to 0, 1 to 2, 1 to 4)
                4 -> listOf(0 to 0, 2 to 0, 0 to 4, 2 to 4)
                5 -> listOf(0 to 0, 2 to 0, 1 to 2, 0 to 4, 2 to 4)
                6 -> listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2, 0 to 4, 2 to 4)
                7 -> listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2, 0 to 4, 2 to 4)
                8 -> listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2, 1 to 3, 0 to 4, 2 to 4)
                9 -> listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1, 1 to 2, 0 to 3, 2 to 3, 0 to 4, 2 to 4)
                10 -> listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2, 0 to 2, 2 to 2, 1 to 3, 0 to 4, 2 to 4)
                else -> emptyList()
            }
            g.color = color
            for ((c, r) in layout) {
                val px = when (c) { 0 -> xL; 2 -> xR; else -> xC }
                val py = rowYs[r]
                g.drawString(symbol, px, py)
            }
        }
    }
}

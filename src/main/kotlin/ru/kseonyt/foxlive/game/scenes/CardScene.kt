package ru.kseonyt.foxlive.game.scenes

import ru.kseonyt.foxlive.core.Engine
import ru.kseonyt.foxlive.core.Scene
import ru.kseonyt.foxlive.game.Card
import ru.kseonyt.foxlive.game.Deck
import ru.kseonyt.foxlive.game.HandRank
import ru.kseonyt.foxlive.game.Modifier
import ru.kseonyt.foxlive.game.Rank
import ru.kseonyt.foxlive.game.Suit
import ru.kseonyt.foxlive.game.scenes.cards.Roguelike
import ru.kseonyt.foxlive.game.scenes.cards.Run
import ru.kseonyt.foxlive.game.scenes.cards.RunState
import ru.kseonyt.foxlive.game.scenes.cards.Shop
import ru.kseonyt.foxlive.render.CardAtlas
import ru.kseonyt.foxlive.ui.DebugUi
import java.util.TreeSet
import kotlin.math.sin
import kotlin.random.Random

/**
 * Layout ported 1:1 from the user-supplied ui.html mockup:
 * 230-px sidebar with stacked panels on the left, green felt main area
 * with overlapping card hand at the bottom, deck widget bottom-right.
 */
class CardScene(engine: Engine, @Suppress("UNUSED_PARAMETER") cardAtlas: CardAtlas? = null) : Scene(engine) {
    override val name: String = "Card Game"

    // ─── Palette (matches ui.html CSS variables) ───────────────────────────
    private val FELT          = 0xFF1A6B3A.toInt()
    private val FELT_DARK     = 0xFF145530.toInt()
    private val PANEL_BG      = 0xFF1C2333.toInt()
    private val PANEL_MID     = 0xFF252E42.toInt()
    private val PANEL_LIGHT   = 0xFF2E3A55.toInt()
    private val PANEL_BORDER  = 0xFF3A4A6A.toInt()
    private val BLUE_ACCENT   = 0xFF4A9EFF.toInt()
    private val BLUE_DARK     = 0xFF1A5FAA.toInt()
    private val RED_ACCENT    = 0xFFE03A3A.toInt()
    private val RED_DARK      = 0xFF991A1A.toInt()
    private val ORANGE_ACCENT = 0xFFE8971E.toInt()
    private val GOLD          = 0xFFF5C842.toInt()
    private val GREEN_BTN     = 0xFF2ECC71.toInt()
    private val TEXT_LIGHT    = 0xFFE8EDF5.toInt()
    private val TEXT_DIM      = 0xFF8A9BB5.toInt()

    private val deck = Deck()
    private val hand = mutableListOf<Card>()
    private val selected = TreeSet<Int>()
    private val handSize = 8
    private val run = Run(maxAntes = 3)
    private val shop = Shop()

    // Cards rendered procedurally (no baked texture). Sizes match ui.html: 80×118 px.
    private val cardW: Float = 80f
    private val cardH: Float = 118f
    private val cardRadius: Float = 8f
    private val cardOverlap: Float = 14f
    private var hoverCard = -1

    private var lastScore: Long = 0L
    private var lastHandRank: HandRank? = null
    private var lastPlayTime: Float = -10f
    private var time: Float = 0f

    private class CardVisual(
        var lift: Float = 0f, var liftTarget: Float = 0f,
        var rot: Float = 0f, var rotTarget: Float = 0f,
        var glow: Float = 0f, var glowTarget: Float = 0f,
    )
    private val visuals = HashMap<Card, CardVisual>()
    private fun visualOf(c: Card): CardVisual = visuals.getOrPut(c) { CardVisual() }

    // Sidebar coordinates
    private val sidebarW = 230f

    override fun setup() {
        engine.renderer?.let {
            it.clearR = ((FELT_DARK shr 16) and 0xFF) / 255f
            it.clearG = ((FELT_DARK shr 8) and 0xFF) / 255f
            it.clearB = (FELT_DARK and 0xFF) / 255f
        }
        deck.reset(); hand.clear(); selected.clear()
        run.reset(); shop.resetForNewShop()
        refillHand()
        sprinkleModifiers()
    }

    override fun update(dt: Float) {
        time += dt
        animateCards(dt)
        renderSidebar()
        renderMainArea()

        when (run.state) {
            RunState.SELECTING    -> { /* normal interaction in renderMainArea */ }
            RunState.ROUND_WIN    -> renderRoundWinOverlay()
            RunState.SHOP         -> renderShopOverlay()
            RunState.ROUND_LOSS   -> renderGameOverOverlay()
            RunState.GAME_WON     -> renderGameWonOverlay()
        }
        renderLastResultToast()
    }

    // ─── Sidebar ────────────────────────────────────────────────────────────

    private fun renderSidebar() {
        val sh = engine.window.height.toFloat()
        // Sidebar bg + right border
        DebugUi.rect(0f, 0f, sidebarW, sh, PANEL_BG)
        DebugUi.rect(sidebarW - 3f, 0f, 3f, sh, 0xFF0D1320.toInt())

        // Vertical layout (8px padding, 6px gap)
        var y = 8f
        val pad = 8f
        val innerW = sidebarW - pad * 2

        y = drawBlindHeader(pad, y, innerW); y += 6f
        y = drawBlindInfo(pad, y, innerW); y += 6f
        y = drawRoundPoints(pad, y, innerW); y += 6f
        y = drawHandTypePanel(pad, y, innerW); y += 6f
        y = drawActionRow(pad, y, innerW); y += 6f
        y = drawMoneyRow(pad, y, innerW); y += 6f
        drawBottomRow(pad, y, innerW)
    }

    private fun drawBlindHeader(x: Float, y: Float, w: Float): Float {
        val h = 32f
        // Drop-shadow look (3px down)
        DebugUi.rect(x, y + 3f, w, h, BLUE_DARK)
        DebugUi.rect(x, y, w, h, BLUE_ACCENT)
        // Top hi-light border
        DebugUi.rect(x, y, w, 2f, 0xFF6BBFFF.toInt())
        DebugUi.rect(x, y + h - 2f, w, 2f, BLUE_DARK)
        DebugUi.textCentered("Малый блайнд", x + w * 0.5f, y + 9f, TEXT_LIGHT)
        return y + h
    }

    private fun drawBlindInfo(x: Float, y: Float, w: Float): Float {
        val h = 78f
        panelBox(x, y, w, h)
        // Round purple icon on the left (faked with overlapping rects)
        val icR = 26f
        val icCx = x + 12f + icR
        val icCy = y + h * 0.5f
        DebugUi.circle(icCx, icCy, icR + 1f, 0xFF9E7DE0.toInt(), 28)
        DebugUi.circle(icCx, icCy, icR - 2f, 0xFF4A2D8A.toInt(), 28)
        DebugUi.textCentered("SMALL", icCx, icCy - 10f, TEXT_LIGHT)
        DebugUi.textCentered("BLIND", icCx, icCy + 2f, TEXT_LIGHT)

        val textX = x + 12f + icR * 2 + 12f
        DebugUi.textAt("Минимум очков", textX, y + 8f, TEXT_DIM)
        DebugUi.textAt("❄ ${run.currentBlind.goal}", textX, y + 24f, RED_ACCENT)
        DebugUi.textAt("Награда:", textX, y + 50f, TEXT_DIM)
        DebugUi.textAt("$".repeat(run.currentBlind.reward.coerceAtMost(5)),
            textX + 70f, y + 50f, GOLD)
        return y + h
    }

    private fun drawRoundPoints(x: Float, y: Float, w: Float): Float {
        val h = 40f
        panelBox(x, y, w, h)
        DebugUi.textAt("Раунд", x + 10f, y + 6f, TEXT_DIM)
        DebugUi.textAt("очки", x + 10f, y + 20f, TEXT_DIM)
        val txt = "${run.roundScore}"
        DebugUi.textAt("❄", x + w - 14f * (txt.length + 1) - 6f, y + 12f, BLUE_ACCENT)
        DebugUi.textAt(txt, x + w - 8f * txt.length - 8f, y + 12f, TEXT_LIGHT)
        return y + h
    }

    private fun drawHandTypePanel(x: Float, y: Float, w: Float): Float {
        val h = 70f
        panelBox(x, y, w, h)

        val sel = selected.map { hand[it] }
        val (rankName, level, chips, mult) = if (sel.isNotEmpty()) {
            val r = ru.kseonyt.foxlive.game.PokerEval.evaluate(sel).rank
            val lv = run.handLevels.levelOf(r)
            val ch = r.baseChips + run.handLevels.chipsBonus(r)
            val mu = r.baseMult + run.handLevels.multBonus(r)
            HandTypeInfo(r.displayName, lv, ch, mu)
        } else {
            HandTypeInfo("—", 1, 0, 0)
        }

        DebugUi.textAt(rankName, x + 8f, y + 6f, TEXT_LIGHT)
        DebugUi.textAt("ур.$level", x + 8f + (rankName.length * 8f) + 8f, y + 8f, TEXT_DIM)

        // Score chips: blue × red
        val chipH = 28f
        val chipY = y + 32f
        val chipBlueW = (w - 20f) * 0.42f
        val chipRedW = chipBlueW
        val chipBlueX = x + 8f
        val chipRedX = x + w - 8f - chipRedW
        // Blue chip (chips)
        DebugUi.rect(chipBlueX, chipY + 3f, chipBlueW, chipH, 0xFF1055CC.toInt())
        DebugUi.rect(chipBlueX, chipY, chipBlueW, chipH, BLUE_ACCENT)
        DebugUi.textCentered("$chips", chipBlueX + chipBlueW * 0.5f, chipY + 8f, TEXT_LIGHT)
        // X separator
        DebugUi.textCentered("×", x + w * 0.5f, chipY + 8f, TEXT_LIGHT)
        // Red chip (mult)
        DebugUi.rect(chipRedX, chipY + 3f, chipRedW, chipH, RED_DARK)
        DebugUi.rect(chipRedX, chipY, chipRedW, chipH, RED_ACCENT)
        DebugUi.textCentered("$mult", chipRedX + chipRedW * 0.5f, chipY + 8f, TEXT_LIGHT)
        return y + h
    }

    private data class HandTypeInfo(val name: String, val level: Int, val chips: Int, val mult: Int)

    private fun drawActionRow(x: Float, y: Float, w: Float): Float {
        val h = 50f
        // Party-Detail button (left)
        val pbtnW = 70f
        if (uiButton("party", x, y, pbtnW, h, RED_ACCENT, RED_DARK, "Партия\nДетали"))
            run.state = RunState.SELECTING

        // Stat cells (right side)
        val cellsX = x + pbtnW + 6f
        val cellsW = w - pbtnW - 6f
        val cellW = (cellsW - 4f) * 0.5f
        // Hands cell
        DebugUi.rect(cellsX, y, cellW, h, PANEL_LIGHT)
        DebugUi.rect(cellsX, y, cellW, 1f, PANEL_BORDER)
        DebugUi.rect(cellsX, y + h - 1f, cellW, 1f, PANEL_BORDER)
        DebugUi.textCentered("Руки", cellsX + cellW * 0.5f, y + 6f, TEXT_DIM)
        DebugUi.textCentered("${run.handsLeft}", cellsX + cellW * 0.5f, y + 24f, BLUE_ACCENT)
        // Discards cell
        val dx = cellsX + cellW + 4f
        DebugUi.rect(dx, y, cellW, h, PANEL_LIGHT)
        DebugUi.rect(dx, y, cellW, 1f, PANEL_BORDER)
        DebugUi.rect(dx, y + h - 1f, cellW, 1f, PANEL_BORDER)
        DebugUi.textCentered("Сбросы", dx + cellW * 0.5f, y + 6f, TEXT_DIM)
        DebugUi.textCentered("${run.discardsLeft}", dx + cellW * 0.5f, y + 24f, RED_ACCENT)
        return y + h
    }

    private fun drawMoneyRow(x: Float, y: Float, w: Float): Float {
        val h = 36f
        panelBox(x, y, w, h)
        DebugUi.textCentered("\$${run.money}", x + w * 0.5f, y + 10f, GOLD)
        return y + h
    }

    private fun drawBottomRow(x: Float, y: Float, w: Float) {
        val h = 50f
        val pbtnW = 70f
        if (uiButton("options", x, y, pbtnW, h, ORANGE_ACCENT, 0xFFA55F00.toInt(), "Пара-\nметры")) {
            // open settings (no-op for now)
        }
        val cellsX = x + pbtnW + 6f
        val cellsW = w - pbtnW - 6f
        val cellW = (cellsW - 4f) * 0.5f
        // Ante
        DebugUi.rect(cellsX, y, cellW, h, PANEL_LIGHT)
        DebugUi.textCentered("Анте", cellsX + cellW * 0.5f, y + 6f, TEXT_DIM)
        DebugUi.textCentered("${run.anteNumber}/${run.maxAntes}",
            cellsX + cellW * 0.5f, y + 24f, TEXT_LIGHT)
        // Round
        val dx = cellsX + cellW + 4f
        DebugUi.rect(dx, y, cellW, h, PANEL_LIGHT)
        DebugUi.textCentered("Раунд", dx + cellW * 0.5f, y + 6f, TEXT_DIM)
        DebugUi.textCentered("${run.blindIdx + 1}", dx + cellW * 0.5f, y + 24f, TEXT_LIGHT)
    }

    // ─── Main area ─────────────────────────────────────────────────────────

    private fun renderMainArea() {
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val mx0 = sidebarW
        val mw = sw - sidebarW

        // Felt bg
        DebugUi.rect(mx0, 0f, mw, sh, FELT)
        // Subtle vignette via a darker rim (3 thin rects)
        DebugUi.rect(mx0, sh - 4f, mw, 4f, 0x40000000.toInt())
        DebugUi.rect(mx0, 0f, 4f, sh, 0x30000000.toInt())

        // Top slots: jokers (left) and consumables (right)
        val slotW = 52f; val slotH = 74f
        val slotsY = 14f
        // Jokers (5 slots)
        DebugUi.textAt("${run.jokers.size}/${run.maxJokers}", mx0 + 20f, slotsY - 12f, 0x80FFFFFF.toInt())
        for (i in 0 until run.maxJokers) {
            val sx = mx0 + 20f + i * (slotW + 8f)
            // dashed-ish border using outline rects
            DebugUi.rect(sx, slotsY, slotW, slotH, 0x26000000.toInt())
            DebugUi.rect(sx, slotsY, slotW, 1f, 0x33FFFFFF.toInt())
            DebugUi.rect(sx, slotsY + slotH - 1f, slotW, 1f, 0x33FFFFFF.toInt())
            DebugUi.rect(sx, slotsY, 1f, slotH, 0x33FFFFFF.toInt())
            DebugUi.rect(sx + slotW - 1f, slotsY, 1f, slotH, 0x33FFFFFF.toInt())
            if (i < run.jokers.size) {
                val j = run.jokers[i]
                DebugUi.rect(sx + 1f, slotsY + 1f, slotW - 2f, slotH - 2f, j.rarity.color)
                drawCenteredFitting(j.name.take(8), sx + slotW * 0.5f, slotsY + 4f, slotW - 2f, TEXT_LIGHT)
                drawCenteredFitting(j.statusLine().ifEmpty { "♦" },
                    sx + slotW * 0.5f, slotsY + slotH - 14f, slotW - 2f, GOLD)
            }
        }
        // Consumable slots (right) — placeholder
        val consSlots = 2
        val consBaseX = sw - 220f - consSlots * (slotW + 8f)
        DebugUi.textAt("0/$consSlots", consBaseX + 4f, slotsY - 12f, 0x80FFFFFF.toInt())
        for (i in 0 until consSlots) {
            val sx = consBaseX + i * (slotW + 8f)
            DebugUi.rect(sx, slotsY, slotW, slotH, 0x26000000.toInt())
            DebugUi.rect(sx, slotsY, slotW, 1f, 0x33FFFFFF.toInt())
            DebugUi.rect(sx, slotsY + slotH - 1f, slotW, 1f, 0x33FFFFFF.toInt())
            DebugUi.rect(sx, slotsY, 1f, slotH, 0x33FFFFFF.toInt())
            DebugUi.rect(sx + slotW - 1f, slotsY, 1f, slotH, 0x33FFFFFF.toInt())
        }

        // Cards row (centered, overlapping)
        renderHand()

        // Bottom controls
        renderBottomControls()

        // Deck widget (bottom-right)
        renderDeckWidget()
    }

    private fun animateCards(dt: Float) {
        // Garbage-collect visuals for cards that left the hand
        val live = hand.toHashSet()
        visuals.keys.removeAll { it !in live }

        // Hit-test mouse against (smoothed) card rects to drive hover state
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val n = hand.size
        val step = cardW - cardOverlap
        val totalW = if (n > 0) (n - 1) * step + cardW else 0f
        val baseX = (sidebarW + (sw - sidebarW) * 0.5f) - totalW * 0.5f
        val baseY = sh - 240f

        val mx = DebugUi.mouseX; val my = DebugUi.mouseY
        hoverCard = -1
        if (run.state == RunState.SELECTING) {
            for (i in hand.indices.reversed()) {
                val v = visualOf(hand[i])
                val cx = baseX + i * step
                val cy = baseY - v.lift
                if (mx >= cx && mx <= cx + cardW && my >= cy && my <= cy + cardH) {
                    hoverCard = i; break
                }
            }
            if (DebugUi.mouseClicked && hoverCard >= 0) {
                if (hoverCard in selected) selected.remove(hoverCard)
                else if (selected.size < 5) selected.add(hoverCard)
            }
        }

        // Drive targets per card
        for (i in hand.indices) {
            val v = visualOf(hand[i])
            v.liftTarget = when {
                i in selected -> 22f + sin(time * 3f + i * 0.7f) * 1.5f
                i == hoverCard -> 18f
                else -> 0f
            }
            v.rotTarget = when {
                i == hoverCard && i !in selected -> -1.5f
                i in selected -> 0f
                else -> 0f
            }
            v.glowTarget = if (i in selected) 1f else 0f
        }

        // Critically-damped lerp toward target
        val k = 1f - kotlin.math.exp(-dt * 18f)
        val rk = 1f - kotlin.math.exp(-dt * 14f)
        for (v in visuals.values) {
            v.lift += (v.liftTarget - v.lift) * k
            v.rot  += (v.rotTarget  - v.rot ) * rk
            v.glow += (v.glowTarget - v.glow) * k
        }
    }

    private fun renderHand() {
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val n = hand.size
        val step = cardW - cardOverlap
        val totalW = if (n > 0) (n - 1) * step + cardW else 0f
        val baseX = (sidebarW + (sw - sidebarW) * 0.5f) - totalW * 0.5f
        val baseY = sh - 240f

        // Z-order: normal cards left-to-right, then selected (in order), then hovered last.
        val drawOrder = (hand.indices).sortedBy { i ->
            (if (i == hoverCard) 200 else 0) + (if (i in selected) 100 else 0) + i
        }

        // Pass 1 — soft drop shadow under each card (rounded to match card shape)
        for (i in drawOrder) {
            val v = visualOf(hand[i])
            val cx = baseX + i * step
            val cy = baseY - v.lift
            DebugUi.roundedRect(cx + 3f, cy + 5f, cardW, cardH, cardRadius, 0x55000000.toInt())
        }

        // Pass 2 — gold selection frame BEHIND the card so the card stays visible
        for (i in drawOrder) {
            if (i !in selected) continue
            val v = visualOf(hand[i])
            val cx = baseX + i * step
            val cy = baseY - v.lift
            val ring = 3f
            val a = (v.glow.coerceIn(0f, 1f) * 255).toInt()
            val ringColor = (a shl 24) or (GOLD and 0x00FFFFFF)
            DebugUi.rect(cx - ring, cy - ring, cardW + ring * 2, ring, ringColor)        // top
            DebugUi.rect(cx - ring, cy + cardH, cardW + ring * 2, ring, ringColor)       // bottom
            DebugUi.rect(cx - ring, cy, ring, cardH, ringColor)                          // left
            DebugUi.rect(cx + cardW, cy, ring, cardH, ringColor)                         // right
        }

        // Pass 3 — procedurally-drawn card faces (rounded body + corners + center)
        for (i in drawOrder) {
            val v = visualOf(hand[i])
            val cx = baseX + i * step
            val cy = baseY - v.lift
            drawCardProcedural(hand[i], cx, cy)
        }

        // Pass 4 — modifier tint (covers rounded body) + badge
        for (i in drawOrder) {
            val mod = hand[i].modifier ?: continue
            val v = visualOf(hand[i])
            val cx = baseX + i * step
            val cy = baseY - v.lift
            DebugUi.roundedRect(cx, cy, cardW, cardH, cardRadius, mod.tintArgb)
            DebugUi.roundedRect(cx + cardW - 18f, cy + 4f, 14f, 14f, 3f, 0xCC000000.toInt())
            DebugUi.textAt(mod.badgeLetter.toString(), cx + cardW - 14f, cy + 4f, GOLD)
        }

        // Pass 5 — gold inner glow tint on selected cards (subtle yellow wash, alpha tied to glow)
        for (i in drawOrder) {
            if (i !in selected) continue
            val v = visualOf(hand[i])
            val cx = baseX + i * step
            val cy = baseY - v.lift
            val a = (v.glow.coerceIn(0f, 1f) * 35).toInt()
            DebugUi.roundedRect(cx, cy, cardW, cardH, cardRadius, (a shl 24) or 0xF5C842)
        }
    }

    // ─── Procedural card painter (mirrors ui.html structure) ───────────────

    /** White rounded body + corner rank/suit + pip grid OR face-card art. */
    private fun drawCardProcedural(card: Card, x: Float, y: Float) {
        val color = suitColorArgb(card.suit)
        // Card body
        DebugUi.roundedRect(x, y, cardW, cardH, cardRadius, 0xFFFAFBFF.toInt())
        // Light inner highlight along the top edge (1px) — fakes the inset white shine
        DebugUi.rect(x + cardRadius, y + 1.5f, cardW - cardRadius * 2, 1f, 0x80FFFFFF.toInt())
        // Soft outer border via four thin edge rects (corners stay rounded body)
        DebugUi.rect(x + cardRadius, y, cardW - cardRadius * 2, 1f, 0xFFD0D8E8.toInt())
        DebugUi.rect(x + cardRadius, y + cardH - 1f, cardW - cardRadius * 2, 1f, 0xFFD0D8E8.toInt())
        DebugUi.rect(x, y + cardRadius, 1f, cardH - cardRadius * 2, 0xFFD0D8E8.toInt())
        DebugUi.rect(x + cardW - 1f, y + cardRadius, 1f, cardH - cardRadius * 2, 0xFFD0D8E8.toInt())

        val rankStr = card.rank.short
        val suitStr = card.suit.symbol
        val cellW = DebugUi.frame.cellWidth()
        val cellH = DebugUi.frame.cellHeight()

        // Top-left corner: rank (bigger) + suit (smaller, below)
        DebugUi.textScaled(rankStr, x + 5f, y + 4f, 1.1f, color)
        DebugUi.textScaled(suitStr, x + 5f, y + 4f + cellH * 1.1f - 2f, 0.95f, color)

        // Center: pip grid for number cards, face-card art otherwise
        if (card.rank.isFace() || card.rank == Rank.ACE) {
            drawFaceCenter(card, x, y, color)
        } else {
            drawPipGrid(card.suit, card.rank.value, x, y, color)
        }

        // Bottom-right corner: same as top-left but mirrored 180°
        val brCx = x + cardW - 5f - cellW * 1.1f * 0.5f * rankStr.length
        val brCy = y + cardH - 4f - cellH * 1.1f * 0.5f
        DebugUi.textRotated180(rankStr, brCx, brCy, 1.1f, color)
        DebugUi.textRotated180(suitStr,
            x + cardW - 5f - cellW * 0.95f * 0.5f,
            y + cardH - 4f - cellH * 1.1f - cellH * 0.95f * 0.5f + 2f,
            0.95f, color)
    }

    private fun drawFaceCenter(card: Card, x: Float, y: Float, color: Int) {
        val cellW = DebugUi.frame.cellWidth()
        val cellH = DebugUi.frame.cellHeight()
        // Big faded rank letter (or suit for the Ace)
        val art = if (card.rank == Rank.ACE) card.suit.symbol else card.rank.short
        val scale = if (card.rank == Rank.ACE) 3.4f else 2.8f
        val faded = (60 shl 24) or (color and 0x00FFFFFF)
        val artW = art.length * cellW * scale
        DebugUi.textScaled(art, x + cardW * 0.5f - artW * 0.5f,
            y + cardH * 0.5f - cellH * scale * 0.5f, scale, faded)
        // For face cards, also draw a small crisp suit symbol below center
        if (card.rank.isFace()) {
            val s = 1.4f
            val sw = cellW * s
            DebugUi.textScaled(card.suit.symbol,
                x + cardW * 0.5f - sw * 0.5f,
                y + cardH * 0.72f, s, color)
        }
    }

    private fun drawPipGrid(suit: Suit, rank: Int, x: Float, y: Float, color: Int) {
        val cellW = DebugUi.frame.cellWidth()
        val cellH = DebugUi.frame.cellHeight()
        // Standard 3-column × 5-row pip layout in normalized card space.
        val L = 0.24f; val C = 0.50f; val R = 0.76f
        val Y1 = 0.22f; val Y2 = 0.36f; val YM = 0.50f; val Y4 = 0.64f; val Y5 = 0.78f
        val positions: List<Pair<Float, Float>> = when (rank) {
            2  -> listOf(C to Y1, C to Y5)
            3  -> listOf(C to Y1, C to YM, C to Y5)
            4  -> listOf(L to Y1, R to Y1, L to Y5, R to Y5)
            5  -> listOf(L to Y1, R to Y1, C to YM, L to Y5, R to Y5)
            6  -> listOf(L to Y1, R to Y1, L to YM, R to YM, L to Y5, R to Y5)
            7  -> listOf(L to Y1, R to Y1, C to Y2, L to YM, R to YM, L to Y5, R to Y5)
            8  -> listOf(L to Y1, R to Y1, C to Y2, L to YM, R to YM, C to Y4, L to Y5, R to Y5)
            9  -> listOf(L to Y1, R to Y1, L to Y2, R to Y2, C to YM, L to Y4, R to Y4, L to Y5, R to Y5)
            10 -> listOf(L to Y1, R to Y1, C to Y2, L to Y2, R to Y2, L to Y4, R to Y4, C to Y4, L to Y5, R to Y5)
            else -> emptyList()
        }
        val pipScale = 1.05f
        val pw = cellW * pipScale
        val ph = cellH * pipScale
        for ((nx, ny) in positions) {
            val px = x + cardW * nx - pw * 0.5f
            val py = y + cardH * ny - ph * 0.5f
            // Top half upright, bottom half rotated 180° — like a real playing card
            if (ny < 0.45f) {
                DebugUi.textScaled(suit.symbol, px, py, pipScale, color)
            } else if (ny > 0.55f) {
                DebugUi.textRotated180(suit.symbol, px + pw * 0.5f, py + ph * 0.5f, pipScale, color)
            } else {
                DebugUi.textScaled(suit.symbol, px, py, pipScale, color)
            }
        }
    }

    private fun suitColorArgb(s: Suit): Int = when (s) {
        Suit.HEARTS -> 0xFFD32F2F.toInt()
        Suit.DIAMONDS -> 0xFFC07000.toInt()
        Suit.SPADES, Suit.CLUBS -> 0xFF1A1A2E.toInt()
    }

    private fun renderBottomControls() {
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val by = sh - 80f
        val mx0 = sidebarW + 20f

        // Cards count above
        DebugUi.textAt("${hand.size}/$handSize", sw * 0.5f - 20f, by - 18f, 0x99FFFFFF.toInt())

        // Play button (blue)
        val playW = 130f; val playH = 56f
        if (uiButton("play", mx0, by, playW, playH, BLUE_ACCENT, 0xFF0D4FA0.toInt(), "Играть\nруку"))
            playSelected()

        // Sort group (label + Rank/Suit)
        val sortX = mx0 + playW + 12f
        val sortW = 200f
        DebugUi.rect(sortX, by, sortW, 22f, PANEL_BG)
        DebugUi.rect(sortX, by, sortW, 1f, PANEL_BORDER)
        DebugUi.textCentered("Сортировать руку", sortX + sortW * 0.5f, by + 6f, TEXT_LIGHT)
        val halfW = (sortW - 3f) * 0.5f
        if (uiButton("sortRank", sortX, by + 23f, halfW, playH - 23f, ORANGE_ACCENT, 0xFF804800.toInt(), "Достоинство"))
            hand.sortByDescending { it.rank.value }
        if (uiButton("sortSuit", sortX + halfW + 3f, by + 23f, halfW, playH - 23f, ORANGE_ACCENT, 0xFF804800.toInt(), "Масть"))
            hand.sortWith(compareBy({ it.suit.ordinal }, { -it.rank.value }))

        // Discard button (right)
        val discardW = 130f
        val discardX = sortX + sortW + 12f
        val canDiscard = selected.isNotEmpty() && run.discardsLeft > 0 && run.state == RunState.SELECTING
        val (dBg, dBgDark) = if (canDiscard)
            Pair(GREEN_BTN, 0xFF1A8C4A.toInt())
        else
            Pair(0xFF555E70.toInt(), 0xFF333A45.toInt())
        if (uiButton("discard", discardX, by, discardW, playH, dBg, dBgDark, "Сброс") && canDiscard)
            discardSelected()
    }

    private fun renderDeckWidget() {
        val sw = engine.window.width.toFloat()
        val sh = engine.window.height.toFloat()
        val w = 62f; val h = 88f
        val x = sw - w - 16f
        val y = sh - h - 32f
        // Card back
        DebugUi.rect(x + 2f, y + 3f, w, h, 0x88000000.toInt())
        DebugUi.rect(x, y, w, h, 0xFF8B0000.toInt())
        DebugUi.rect(x + 4f, y + 4f, w - 8f, h - 8f, 0x00000000)
        DebugUi.rect(x + 4f, y + 4f, w - 8f, 1f, 0x55FFFFFF.toInt())
        DebugUi.rect(x + 4f, y + h - 5f, w - 8f, 1f, 0x55FFFFFF.toInt())
        DebugUi.rect(x + 4f, y + 4f, 1f, h - 8f, 0x55FFFFFF.toInt())
        DebugUi.rect(x + w - 5f, y + 4f, 1f, h - 8f, 0x55FFFFFF.toInt())
        DebugUi.textCentered("✦", x + w * 0.5f, y + h * 0.5f - 6f, 0x80FFFFFF.toInt())
        DebugUi.textCentered("${deck.size()}/52", x + w * 0.5f, y + h + 4f, 0xB0FFFFFF.toInt())
    }

    // ─── Overlays ──────────────────────────────────────────────────────────

    private fun dim() {
        DebugUi.rect(0f, 0f, engine.window.width.toFloat(), engine.window.height.toFloat(), 0xB0000000.toInt())
    }

    private fun renderRoundWinOverlay() {
        dim()
        val sw = engine.window.width.toFloat()
        DebugUi.panel("Блайнд побеждён", sw * 0.5f - 180f, 200f, 360f) {
            DebugUi.text("Очки: ${run.roundScore} / ${run.currentBlind.goal}", GREEN_BTN)
            DebugUi.separator()
            DebugUi.text("Награда: \$${run.currentBlind.reward}")
            DebugUi.text("Бонус за руки: \$${run.handsLeft}")
            DebugUi.text("Бонус за сбросы: \$${run.discardsLeft}")
            DebugUi.separator()
            if (DebugUi.button("В магазин →")) run.goToShop()
        }
    }

    private fun renderShopOverlay() {
        dim()
        val sw = engine.window.width.toFloat()
        DebugUi.panel("Магазин   •   \$${run.money}", sw * 0.5f - 280f, 100f, 560f) {
            if (shop.offers.isEmpty()) DebugUi.text("(распродано)", TEXT_DIM)
            else for ((i, j) in shop.offers.withIndex()) {
                val canAfford = run.money >= j.cost && run.jokers.size < run.maxJokers
                val tag = if (run.jokers.size >= run.maxJokers) "ПОЛНО" else "\$${j.cost}"
                if (DebugUi.button("Купить: ${j.name}  ($tag)") && canAfford) shop.buy(i, run)
                DebugUi.text("  ${j.description}", TEXT_DIM)
                DebugUi.spacer(2f)
            }
            DebugUi.separator()
            val canReroll = run.money >= shop.rerollCost
            if (DebugUi.button("Реролл  (\$${shop.rerollCost})") && canReroll) {
                run.money -= shop.rerollCost; shop.reroll()
            }
            if (DebugUi.button("Следующий блайнд →")) {
                shop.resetForNewShop(); run.startNextBlind()
            }
        }
    }

    private fun renderGameOverOverlay() {
        dim()
        val sw = engine.window.width.toFloat()
        DebugUi.panel("Поражение", sw * 0.5f - 160f, 250f, 320f) {
            DebugUi.text("Закончились руки.", RED_ACCENT)
            DebugUi.text("Анте: ${run.anteNumber}")
            DebugUi.separator()
            if (DebugUi.button("Заново")) setup()
        }
    }

    private fun renderGameWonOverlay() {
        dim()
        val sw = engine.window.width.toFloat()
        DebugUi.panel("Победа!", sw * 0.5f - 160f, 250f, 320f) {
            DebugUi.text("Все ${run.maxAntes} анте побеждены.", GREEN_BTN)
            DebugUi.text("Деньги: \$${run.money}")
            DebugUi.separator()
            if (DebugUi.button("Ещё раз")) setup()
        }
    }

    private fun renderLastResultToast() {
        val rank = lastHandRank ?: return
        val elapsed = time - lastPlayTime
        if (elapsed > 2.5f) return
        val sw = engine.window.width.toFloat()
        val a = ((1f - (elapsed / 2.5f)).coerceIn(0f, 1f) * 255).toInt()
        val color = (a shl 24) or 0xFFE060
        DebugUi.textCentered(rank.displayName,
            sidebarW + (sw - sidebarW) * 0.5f, engine.window.height * 0.5f - 30f, color)
        DebugUi.textCentered("+$lastScore",
            sidebarW + (sw - sidebarW) * 0.5f, engine.window.height * 0.5f - 12f, color)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** Skinned button matching the chunky 3-D pressed-pixel look from the mockup. */
    private fun uiButton(
        id: String, x: Float, y: Float, w: Float, h: Float,
        bg: Int, shadow: Int, label: String,
    ): Boolean {
        val mx = DebugUi.mouseX; val my = DebugUi.mouseY
        val hovered = mx >= x && mx <= x + w && my >= y && my <= y + h
        val isActive = DebugUi.activeId == id
        if (hovered && DebugUi.mouseClicked) DebugUi.activeId = id
        var triggered = false
        if (DebugUi.mouseReleased && isActive) {
            if (hovered) triggered = true
            DebugUi.activeId = null
        }
        val pressed = isActive && hovered && DebugUi.mouseDown
        val offset = if (pressed) 2f else 0f
        DebugUi.rect(x, y + 3f, w, h, shadow)
        DebugUi.rect(x, y + offset, w, h - offset, bg)
        // Light top edge
        DebugUi.rect(x, y + offset, w, 1f, 0x40FFFFFF.toInt())
        // Multiline label
        val lines = label.split("\n")
        val lineH = 14f
        val totalH = lines.size * lineH
        val startY = y + offset + (h - offset - totalH) * 0.5f
        for ((i, ln) in lines.withIndex()) {
            DebugUi.textCentered(ln, x + w * 0.5f, startY + i * lineH, TEXT_LIGHT)
        }
        return triggered
    }

    private fun panelBox(x: Float, y: Float, w: Float, h: Float) {
        DebugUi.rect(x, y, w, h, PANEL_MID)
        DebugUi.rect(x, y, w, 2f, PANEL_LIGHT)
        DebugUi.rect(x, y + h - 2f, w, 2f, PANEL_LIGHT)
        DebugUi.rect(x, y, 2f, h, PANEL_LIGHT)
        DebugUi.rect(x + w - 2f, y, 2f, h, PANEL_LIGHT)
    }

    // ─── Game actions ──────────────────────────────────────────────────────

    private fun playSelected() {
        if (selected.isEmpty() || run.handsLeft <= 0 || run.state != RunState.SELECTING) return
        val played = selected.map { hand[it] }
        val (rank, score) = Roguelike.scoreHand(played, run)
        run.roundScore += score
        run.handsLeft--
        lastHandRank = rank; lastScore = score; lastPlayTime = time
        for (idx in selected.descendingIterator()) hand.removeAt(idx)
        selected.clear()
        refillHand()
        sprinkleModifiersFor(hand.takeLast(played.size))
        if (run.roundScore >= run.currentBlind.goal) run.beatBlind()
        else if (run.handsLeft <= 0) run.state = RunState.ROUND_LOSS
    }

    private fun discardSelected() {
        if (selected.isEmpty() || run.discardsLeft <= 0) return
        for (idx in selected.descendingIterator()) hand.removeAt(idx)
        selected.clear()
        run.discardsLeft--
        refillHand()
        sprinkleModifiersFor(hand.takeLast(1))
    }

    private fun refillHand() {
        while (hand.size < handSize) {
            val c = deck.draw() ?: break
            hand += c
        }
    }

    private fun sprinkleModifiers() {
        for (c in hand) if (Random.nextFloat() < 0.18f) c.modifier = Modifier.values().random()
    }
    private fun sprinkleModifiersFor(newCards: List<Card>) {
        for (c in newCards) if (Random.nextFloat() < 0.18f) c.modifier = Modifier.values().random()
    }

    private fun drawCenteredFitting(s: String, cx: Float, y: Float, maxW: Float, color: Int) {
        val cellW = DebugUi.frame.cellWidth()
        val maxChars = (maxW / cellW).toInt().coerceAtLeast(1)
        val text = if (s.length > maxChars) s.take(maxChars - 1) + "…" else s
        DebugUi.textCentered(text, cx, y, color)
    }
}

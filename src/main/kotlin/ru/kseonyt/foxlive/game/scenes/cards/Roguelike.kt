package ru.kseonyt.foxlive.game.scenes.cards

import ru.kseonyt.foxlive.game.Card
import ru.kseonyt.foxlive.game.HandRank
import ru.kseonyt.foxlive.game.Modifier
import ru.kseonyt.foxlive.game.PokerEval

/**
 * Engine-original poker-roguelike core — chips×mult scoring with hand-level progression,
 * blind/ante structure, money, jokers, and a small shop. All values are FoxLive-local
 * and not derived from any commercial implementation.
 */

enum class JokerRarity(val displayName: String, val color: Int) {
    COMMON("common", 0xFF5288D8.toInt()),
    UNCOMMON("uncommon", 0xFF50C080.toInt()),
    RARE("rare", 0xFFC050B0.toInt()),
}

abstract class Joker(
    val id: String,
    val name: String,
    val description: String,
    val cost: Int,
    val rarity: JokerRarity,
) {
    open fun onScoreCard(card: Card, ctx: ScoringContext) {}
    open fun onAfterPlay(ctx: ScoringContext) {}
    open fun onRoundStart(run: Run) {}
    open fun onRoundEnd(run: Run) {}
    /** Visual-only state line (e.g. counters) for the joker's UI tile. */
    open fun statusLine(): String = ""
}

class ScoringContext(
    var chips: Int,
    var mult: Float,
    var multX: Float,
    val playedHand: List<Card>,
    val handRank: HandRank,
    val run: Run,
) {
    fun finalScore(): Long = (chips * mult * multX).toLong()
}

class HandLevels {
    private val levels: MutableMap<HandRank, Int> = HandRank.values().associateWith { 1 }.toMutableMap()
    private val plays: MutableMap<HandRank, Int> = HandRank.values().associateWith { 0 }.toMutableMap()

    fun levelOf(r: HandRank): Int = levels[r] ?: 1
    fun playsOf(r: HandRank): Int = plays[r] ?: 0
    fun chipsBonus(r: HandRank): Int = (levelOf(r) - 1) * 8
    fun multBonus(r: HandRank): Int = (levelOf(r) - 1) * 1

    fun played(r: HandRank) {
        val pl = playsOf(r) + 1
        if (pl >= 2) {
            levels[r] = levelOf(r) + 1
            plays[r] = 0
        } else {
            plays[r] = pl
        }
    }
}

class Blind(val name: String, val goal: Long, val reward: Int)

class Ante(val number: Int) {
    val small: Blind
    val big: Blind
    val boss: Blind
    val blinds: List<Blind>
    init {
        val base = (300.0 * Math.pow(1.55, (number - 1).toDouble())).toLong()
        small = Blind("Small Blind", base, 3)
        big = Blind("Big Blind", (base * 1.5).toLong(), 4)
        boss = Blind("Boss Blind", base * 2, 5)
        blinds = listOf(small, big, boss)
    }
}

enum class RunState { SELECTING, ROUND_WIN, SHOP, ROUND_LOSS, GAME_WON }

class Run(val maxAntes: Int = 3) {
    var anteNumber: Int = 1; private set
    var blindIdx: Int = 0; private set
    var ante: Ante = Ante(1); private set
    var money: Int = 4
    val jokers: MutableList<Joker> = mutableListOf()
    val maxJokers: Int = 5
    val handLevels: HandLevels = HandLevels()
    var roundScore: Long = 0L
    var handsLeft: Int = 4
    var discardsLeft: Int = 4
    var state: RunState = RunState.SELECTING
    var lastResultLabel: String = ""

    val currentBlind: Blind get() = ante.blinds[blindIdx]

    fun beatBlind() {
        money += currentBlind.reward + handsLeft + discardsLeft
        for (j in jokers) j.onRoundEnd(this)
        state = if (anteNumber >= maxAntes && blindIdx >= 2) RunState.GAME_WON else RunState.ROUND_WIN
    }

    /** Called from the UI after the win screen, transitions into shop. */
    fun goToShop() {
        if (state == RunState.ROUND_WIN) state = RunState.SHOP
    }

    /** Advance to next blind/ante after shop. */
    fun startNextBlind() {
        blindIdx++
        if (blindIdx >= 3) {
            blindIdx = 0
            anteNumber++
            ante = Ante(anteNumber)
        }
        roundScore = 0L
        handsLeft = 4
        discardsLeft = 4
        state = RunState.SELECTING
        for (j in jokers) j.onRoundStart(this)
    }

    fun reset() {
        anteNumber = 1; blindIdx = 0; ante = Ante(1)
        money = 4; jokers.clear()
        roundScore = 0L; handsLeft = 4; discardsLeft = 4
        state = RunState.SELECTING
    }
}

/**
 * Compute final score for a played hand. Order:
 *   base (hand rank) + level bonus → per-card chips + modifier + jokers → after-play jokers.
 */
object Roguelike {
    fun scoreHand(played: List<Card>, run: Run): Pair<HandRank, Long> {
        val handRank = PokerEval.evaluate(played).rank
        val baseChips = handRank.baseChips + run.handLevels.chipsBonus(handRank)
        val baseMult = (handRank.baseMult + run.handLevels.multBonus(handRank)).toFloat()

        val ctx = ScoringContext(baseChips, baseMult, 1f, played, handRank, run)

        for (card in played) {
            ctx.chips += card.rank.chips()
            when (card.modifier) {
                Modifier.GLAZE -> ctx.chips += 50
                Modifier.AURA  -> ctx.mult += 5f
                Modifier.PRISM -> ctx.multX *= 1.5f
                null -> {}
            }
            for (j in run.jokers) j.onScoreCard(card, ctx)
        }
        for (j in run.jokers) j.onAfterPlay(ctx)
        run.handLevels.played(handRank)
        return handRank to ctx.finalScore()
    }
}

class Shop {
    val offers: MutableList<Joker> = mutableListOf()
    var rerollCost: Int = 5
    init { reroll(0) }

    fun reroll(money: Int? = null): Boolean {
        if (money != null && money < rerollCost) return false
        offers.clear()
        repeat(2) { offers += JokerPool.random() }
        rerollCost += 1
        return true
    }

    fun buy(idx: Int, run: Run): Boolean {
        if (idx !in offers.indices) return false
        val joker = offers[idx]
        if (run.money < joker.cost) return false
        if (run.jokers.size >= run.maxJokers) return false
        run.money -= joker.cost
        run.jokers += joker
        offers.removeAt(idx)
        return true
    }

    fun resetForNewShop() {
        offers.clear()
        rerollCost = 5
        reroll(null)
    }
}

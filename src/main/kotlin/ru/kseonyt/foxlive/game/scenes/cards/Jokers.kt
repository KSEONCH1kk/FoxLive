package ru.kseonyt.foxlive.game.scenes.cards

import ru.kseonyt.foxlive.game.Card
import ru.kseonyt.foxlive.game.HandRank
import ru.kseonyt.foxlive.game.Suit

/**
 * FoxLive-original joker pool. All names, descriptions, costs, and bonus values
 * were chosen for this engine demo and are not derived from any commercial game's data.
 * Each joker is a small ECS-like object with hooks called during scoring.
 */

private class Steady : Joker(
    id = "steady",
    name = "Steady",
    description = "+5 mult per scored card",
    cost = 4,
    rarity = JokerRarity.COMMON,
) {
    override fun onScoreCard(card: Card, ctx: ScoringContext) { ctx.mult += 5f }
}

private class Bedrock : Joker(
    id = "bedrock",
    name = "Bedrock",
    description = "+20 chips per scored card",
    cost = 4,
    rarity = JokerRarity.COMMON,
) {
    override fun onScoreCard(card: Card, ctx: ScoringContext) { ctx.chips += 20 }
}

private class Frugal : Joker(
    id = "frugal",
    name = "Frugal",
    description = "+25 chips if hand has ≤3 cards",
    cost = 4,
    rarity = JokerRarity.COMMON,
) {
    override fun onAfterPlay(ctx: ScoringContext) {
        if (ctx.playedHand.size <= 3) ctx.chips += 25
    }
}

private class WildHeart : Joker(
    id = "wild_heart",
    name = "Wild Heart",
    description = "+1 mult per modified card scored",
    cost = 4,
    rarity = JokerRarity.COMMON,
) {
    override fun onScoreCard(card: Card, ctx: ScoringContext) {
        if (card.modifier != null) ctx.mult += 1f
    }
}

private class HeartHunter : Joker(
    id = "heart_hunter",
    name = "Heart Hunter",
    description = "+30 chips for each ♥ scored",
    cost = 5,
    rarity = JokerRarity.UNCOMMON,
) {
    override fun onScoreCard(card: Card, ctx: ScoringContext) {
        if (card.suit == Suit.HEARTS) ctx.chips += 30
    }
}

private class RoyalTouch : Joker(
    id = "royal_touch",
    name = "Royal Touch",
    description = "+5 mult for each face card scored",
    cost = 5,
    rarity = JokerRarity.UNCOMMON,
) {
    override fun onScoreCard(card: Card, ctx: ScoringContext) {
        if (card.rank.isFace()) ctx.mult += 5f
    }
}

private class Pressure : Joker(
    id = "pressure",
    name = "Pressure",
    description = "+20 mult if 0 discards used this round",
    cost = 6,
    rarity = JokerRarity.UNCOMMON,
) {
    override fun onAfterPlay(ctx: ScoringContext) {
        if (ctx.run.discardsLeft == 4) ctx.mult += 20f
    }
}

private class ComboCounter : Joker(
    id = "combo_counter",
    name = "Combo Counter",
    description = "Permanent +1 mult per hand played",
    cost = 6,
    rarity = JokerRarity.UNCOMMON,
) {
    private var counter: Int = 0
    override fun onAfterPlay(ctx: ScoringContext) {
        counter += 1
        ctx.mult += counter.toFloat()
    }
    override fun statusLine(): String = "+$counter mult"
}

private class Doubler : Joker(
    id = "doubler",
    name = "Doubler",
    description = "x2 mult after scoring",
    cost = 7,
    rarity = JokerRarity.RARE,
) {
    override fun onAfterPlay(ctx: ScoringContext) { ctx.multX *= 2f }
}

private class Specialist : Joker(
    id = "specialist",
    name = "Specialist",
    description = "x1.5 mult if hand is Flush or better",
    cost = 7,
    rarity = JokerRarity.RARE,
) {
    override fun onAfterPlay(ctx: ScoringContext) {
        if (ctx.handRank.ordinal >= HandRank.FLUSH.ordinal) ctx.multX *= 1.5f
    }
}

object JokerPool {
    private val factories: List<() -> Joker> = listOf(
        { Steady() }, { Bedrock() }, { Frugal() }, { WildHeart() },
        { HeartHunter() }, { RoyalTouch() }, { Pressure() }, { ComboCounter() },
        { Doubler() }, { Specialist() },
    )
    fun random(): Joker = factories.random()()
    fun all(): List<Joker> = factories.map { it() }
}

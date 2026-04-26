package ru.kseonyt.foxlive.game

enum class Suit(val symbol: String, val isRed: Boolean) {
    SPADES("♠", false),    // ♠
    HEARTS("♥", true),     // ♥
    DIAMONDS("♦", true),   // ♦
    CLUBS("♣", false),     // ♣
}

enum class Rank(val short: String, val value: Int) {
    TWO("2", 2), THREE("3", 3), FOUR("4", 4), FIVE("5", 5), SIX("6", 6),
    SEVEN("7", 7), EIGHT("8", 8), NINE("9", 9), TEN("10", 10),
    JACK("J", 11), QUEEN("Q", 12), KING("K", 13), ACE("A", 14);
    fun chips(): Int = when (this) {
        JACK, QUEEN, KING -> 10
        ACE -> 11
        else -> value
    }
    fun isFace(): Boolean = this == JACK || this == QUEEN || this == KING
}

/**
 * Engine-original card-modifier types — generic chip / flat-mult / multiplicative-mult variants.
 * Names and numerical effects are local to FoxLive and not derived from any commercial game.
 */
enum class Modifier(val displayName: String, val tintArgb: Int, val badgeLetter: Char) {
    GLAZE("Glaze", 0x6080F0FF.toInt(), 'G'),  // adds chips
    AURA("Aura", 0x60FF80E0.toInt(), 'A'),    // adds flat mult
    PRISM("Prism", 0x60FFD060.toInt(), 'P'),  // multiplies mult
}

class Card(val suit: Suit, val rank: Rank, var modifier: Modifier? = null) {
    val atlasIndex: Int get() = suit.ordinal * 13 + rank.ordinal
    override fun toString(): String = "${rank.short}${suit.symbol}${modifier?.let { "[${it.badgeLetter}]" } ?: ""}"
}

class Deck {
    private val cards = ArrayDeque<Card>()
    init { reset() }
    fun reset() {
        cards.clear()
        for (s in Suit.values()) for (r in Rank.values()) cards.add(Card(s, r))
        shuffle()
    }
    fun shuffle() {
        val list = cards.toMutableList()
        list.shuffle()
        cards.clear(); cards.addAll(list)
    }
    fun draw(): Card? = if (cards.isEmpty()) null else cards.removeFirst()
    fun drawN(n: Int): List<Card> = (0 until n).mapNotNull { draw() }
    fun size(): Int = cards.size
}

enum class HandRank(val displayName: String, val baseChips: Int, val baseMult: Int) {
    HIGH_CARD("High Card", 5, 1),
    PAIR("Pair", 10, 2),
    TWO_PAIR("Two Pair", 20, 2),
    THREE_OF_A_KIND("Three of a Kind", 30, 3),
    STRAIGHT("Straight", 30, 4),
    FLUSH("Flush", 35, 4),
    FULL_HOUSE("Full House", 40, 4),
    FOUR_OF_A_KIND("Four of a Kind", 60, 7),
    STRAIGHT_FLUSH("Straight Flush", 100, 8),
    ROYAL_FLUSH("Royal Flush", 100, 8),
}

data class HandResult(val rank: HandRank, val played: List<Card>, val score: Int)

object PokerEval {
    fun evaluate(cards: List<Card>): HandResult {
        if (cards.isEmpty()) return HandResult(HandRank.HIGH_CARD, emptyList(), 0)

        val byRank = cards.groupBy { it.rank }
        val bySuit = cards.groupBy { it.suit }
        val sortedValues = cards.map { it.rank.value }.sorted().distinct()
        val rankCounts = byRank.values.map { it.size }.sortedDescending()
        val isFlush = cards.size == 5 && bySuit.size == 1
        val isStraight = isStraightSeq(sortedValues, cards.size)

        val handRank = when {
            isFlush && isStraight && sortedValues == listOf(10, 11, 12, 13, 14) -> HandRank.ROYAL_FLUSH
            isFlush && isStraight -> HandRank.STRAIGHT_FLUSH
            rankCounts.firstOrNull() == 4 -> HandRank.FOUR_OF_A_KIND
            rankCounts.size >= 2 && rankCounts[0] == 3 && rankCounts[1] == 2 -> HandRank.FULL_HOUSE
            isFlush -> HandRank.FLUSH
            isStraight -> HandRank.STRAIGHT
            rankCounts.firstOrNull() == 3 -> HandRank.THREE_OF_A_KIND
            rankCounts.size >= 2 && rankCounts[0] == 2 && rankCounts[1] == 2 -> HandRank.TWO_PAIR
            rankCounts.firstOrNull() == 2 -> HandRank.PAIR
            else -> HandRank.HIGH_CARD
        }

        val played = playedCards(handRank, cards, byRank)
        val score = (handRank.baseChips + played.sumOf { it.rank.chips() }) * handRank.baseMult
        return HandResult(handRank, played, score)
    }

    private fun isStraightSeq(sortedDistinct: List<Int>, totalCards: Int): Boolean {
        if (totalCards != 5) return false
        if (sortedDistinct.size != 5) return false
        if (sortedDistinct == listOf(2, 3, 4, 5, 14)) return true
        for (i in 1 until sortedDistinct.size)
            if (sortedDistinct[i] != sortedDistinct[i - 1] + 1) return false
        return true
    }

    private fun playedCards(
        rank: HandRank,
        cards: List<Card>,
        byRank: Map<Rank, List<Card>>,
    ): List<Card> = when (rank) {
        HandRank.PAIR -> byRank.values.first { it.size == 2 }
        HandRank.TWO_PAIR -> byRank.values.filter { it.size == 2 }.flatten()
        HandRank.THREE_OF_A_KIND -> byRank.values.first { it.size == 3 }
        HandRank.FOUR_OF_A_KIND -> byRank.values.first { it.size == 4 }
        HandRank.FULL_HOUSE,
        HandRank.STRAIGHT,
        HandRank.FLUSH,
        HandRank.STRAIGHT_FLUSH,
        HandRank.ROYAL_FLUSH -> cards
        HandRank.HIGH_CARD -> listOf(cards.maxBy { it.rank.value })
    }
}

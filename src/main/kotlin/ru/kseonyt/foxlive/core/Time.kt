package ru.kseonyt.foxlive.core

object Time {
    private var last: Long = System.nanoTime()
    var delta: Float = 0f; private set
    var elapsed: Float = 0f; private set

    fun tick() {
        val now = System.nanoTime()
        delta = ((now - last) / 1_000_000_000.0).toFloat().coerceAtMost(0.1f)
        elapsed += delta
        last = now
    }
}

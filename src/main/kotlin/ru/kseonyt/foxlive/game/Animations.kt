package ru.kseonyt.foxlive.game

import org.joml.Vector3f
import ru.kseonyt.foxlive.ecs.Component
import ru.kseonyt.foxlive.ecs.System
import ru.kseonyt.foxlive.ecs.Transform
import ru.kseonyt.foxlive.ecs.World

object Easing {
    val linear: (Float) -> Float = { it }
    val easeInOut: (Float) -> Float = { t -> if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } * 0.5f }
    val easeOut: (Float) -> Float = { t -> 1f - (1f - t).let { it * it } }
    val easeIn: (Float) -> Float = { t -> t * t }
}

/**
 * Tween a Transform's position and/or scale toward `toPos`/`toScale` over `duration` seconds.
 * If `loop = PingPong`, oscillates between origin and target.
 */
class Tween(
    val toPos: Vector3f? = null,
    val toScale: Vector3f? = null,
    val duration: Float = 1f,
    val easing: (Float) -> Float = Easing.easeInOut,
    val loop: TweenLoop = TweenLoop.None,
    var time: Float = 0f,
) : Component {
    var fromPos: Vector3f? = null   // captured on first tick
    var fromScale: Vector3f? = null
    var done: Boolean = false
}

enum class TweenLoop { None, PingPong, Restart }

class TweenSystem(world: World) : System(world) {
    override fun update(dt: Float) {
        val toRemove = ArrayList<Int>()
        for ((id, tw) in world.all<Tween>()) {
            val tr = world.get<Transform>(id) ?: continue
            if (tw.fromPos == null && tw.toPos != null) tw.fromPos = Vector3f(tr.position)
            if (tw.fromScale == null && tw.toScale != null) tw.fromScale = Vector3f(tr.scale)
            tw.time += dt
            var t = (tw.time / tw.duration).coerceIn(0f, 1f)
            t = tw.easing(t)
            tw.toPos?.let { target ->
                val from = tw.fromPos!!
                tr.position.set(
                    from.x + (target.x - from.x) * t,
                    from.y + (target.y - from.y) * t,
                    from.z + (target.z - from.z) * t,
                )
            }
            tw.toScale?.let { target ->
                val from = tw.fromScale!!
                tr.scale.set(
                    from.x + (target.x - from.x) * t,
                    from.y + (target.y - from.y) * t,
                    from.z + (target.z - from.z) * t,
                )
            }
            if (tw.time >= tw.duration) {
                when (tw.loop) {
                    TweenLoop.None -> { tw.done = true; toRemove += id }
                    TweenLoop.Restart -> { tw.time = 0f }
                    TweenLoop.PingPong -> {
                        // swap from/to
                        tw.toPos?.let { val tmp = Vector3f(it); it.set(tw.fromPos!!); tw.fromPos!!.set(tmp) }
                        tw.toScale?.let { val tmp = Vector3f(it); it.set(tw.fromScale!!); tw.fromScale!!.set(tmp) }
                        tw.time = 0f
                    }
                }
            }
        }
        toRemove.forEach { world.remove<Tween>(it) }
    }
}

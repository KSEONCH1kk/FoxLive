package ru.kseonyt.foxlive.game

import org.lwjgl.glfw.GLFW.*
import ru.kseonyt.foxlive.core.Engine
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orbital camera around the world origin. Helper used by 3D scenes —
 * not an ECS system, just call [update] each frame.
 */
class OrbitCameraController(
    val engine: Engine,
    var yaw: Float = -90f,
    var pitch: Float = -55f,
    var distance: Float = 22f,
    var minDistance: Float = 6f,
    var maxDistance: Float = 60f,
) {
    fun update(dt: Float) {
        val w = engine.window
        if (w.input.isDown(GLFW_KEY_Q)) yaw -= 60f * dt
        if (w.input.isDown(GLFW_KEY_E)) yaw += 60f * dt
        if (w.input.isDown(GLFW_KEY_R)) pitch = (pitch + 40f * dt).coerceAtMost(-10f)
        if (w.input.isDown(GLFW_KEY_F)) pitch = (pitch - 40f * dt).coerceAtLeast(-89f)
        if (w.input.isDown(GLFW_KEY_Z)) distance = (distance - 8f * dt).coerceAtLeast(minDistance)
        if (w.input.isDown(GLFW_KEY_X)) distance = (distance + 8f * dt).coerceAtMost(maxDistance)

        val ry = Math.toRadians(yaw.toDouble()).toFloat()
        val rp = Math.toRadians(pitch.toDouble()).toFloat()
        engine.camera.yaw = yaw
        engine.camera.pitch = pitch
        engine.camera.position.set(
            cos(ry) * cos(rp) * -distance,
            -sin(rp) * distance,
            sin(ry) * cos(rp) * -distance,
        )
        engine.camera.updateVectors()
    }
}

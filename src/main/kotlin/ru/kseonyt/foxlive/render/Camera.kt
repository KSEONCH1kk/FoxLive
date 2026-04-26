package ru.kseonyt.foxlive.render

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

class Camera(
    val position: Vector3f = Vector3f(0f, 5f, 10f),
    var yaw: Float = -90f,
    var pitch: Float = -25f,
    var fovDeg: Float = 60f,
    var near: Float = 0.1f,
    var far: Float = 200f,
) {
    val front = Vector3f(0f, 0f, -1f)
    val up = Vector3f(0f, 1f, 0f)
    val right = Vector3f(1f, 0f, 0f)
    private val worldUp = Vector3f(0f, 1f, 0f)

    /** Sun/key directional light — direction *toward* the light, world space, normalized. */
    val lightDirection: Vector3f = Vector3f(0.5f, 1.0f, 0.4f).normalize()
    /** Light tint multiplier. */
    val lightColor: Vector3f = Vector3f(1f, 0.97f, 0.88f)

    private val viewMat = Matrix4f()
    private val projMat = Matrix4f()
    private val vpMat = Matrix4f()

    init { updateVectors() }

    fun updateVectors() {
        val ry = Math.toRadians(yaw.toDouble()).toFloat()
        val rp = Math.toRadians(pitch.toDouble()).toFloat()
        front.set(cos(ry) * cos(rp), sin(rp), sin(ry) * cos(rp)).normalize()
        front.cross(worldUp, right).normalize()
        right.cross(front, up).normalize()
    }

    fun view(): Matrix4f {
        val center = Vector3f(position).add(front)
        return viewMat.identity().lookAt(position, center, worldUp)
    }

    fun projection(aspect: Float): Matrix4f {
        // Vulkan clip space: Y is flipped vs OpenGL, depth is [0,1]
        return projMat.identity()
            .perspective(Math.toRadians(fovDeg.toDouble()).toFloat(), aspect, near, far, true)
            .also { it.m11(it.m11() * -1f) }
    }

    fun viewProjection(aspect: Float): Matrix4f =
        projection(aspect).mul(view(), vpMat)
}

package ru.kseonyt.foxlive.ecs

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.kseonyt.foxlive.render.Material
import ru.kseonyt.foxlive.render.Mesh

class Transform(
    val position: Vector3f = Vector3f(),
    val rotation: Quaternionf = Quaternionf(),
    val scale: Vector3f = Vector3f(1f, 1f, 1f),
) : Component {
    private val matrix = Matrix4f()
    fun model(): Matrix4f = matrix.identity()
        .translate(position)
        .rotate(rotation)
        .scale(scale)
}

class MeshRenderer(
    val mesh: Mesh,
    val color: Vector3f = Vector3f(1f, 1f, 1f),
    /** null = use renderer's default material (white diffuse + flat normal) */
    val material: Material? = null,
) : Component

class Velocity(val value: Vector3f = Vector3f()) : Component

class Tag(val name: String) : Component

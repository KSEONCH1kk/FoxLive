package ru.kseonyt.foxlive.game.scenes

import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import ru.kseonyt.foxlive.core.Engine
import ru.kseonyt.foxlive.core.Scene
import ru.kseonyt.foxlive.ecs.*
import ru.kseonyt.foxlive.game.OrbitCameraController
import ru.kseonyt.foxlive.game.Particle
import ru.kseonyt.foxlive.physics.*
import ru.kseonyt.foxlive.render.Mesh
import ru.kseonyt.foxlive.ui.DebugUi
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PhysicsScene(engine: Engine, private val physics: PhysicsSystem) : Scene(engine) {
    override val name: String = "Physics"

    private val sphereMesh = Mesh.sphere(0.4f, color = Vector3f(1f, 0.55f, 0.18f))
    private val crateMesh = Mesh.cube(Vector3f(0.78f, 0.55f, 0.28f))
    private val groundMesh = Mesh.plane(30f, Vector3f(0.18f, 0.20f, 0.22f))

    private val orbit = OrbitCameraController(engine, distance = 18f)
    private var spaceHeld = false
    private var bHeld = false
    private var ballsSpawned = 0
    private var cratesSpawned = 0
    private var lightAngle = 35f
    private var lightHeight = 0.9f
    private var gravityStrength = 9.81f

    override fun setup() {
        engine.renderer?.let { it.clearR = 0.05f; it.clearG = 0.07f; it.clearB = 0.10f }

        // Visual ground
        val ground = spawn()
        world.add(ground, Transform(Vector3f(0f, -0.5f, 0f)))
        world.add(ground, MeshRenderer(groundMesh))

        // Static physics floor (no rigidbody = static)
        val floor = spawn()
        world.add(floor, Transform())
        world.add(floor, Collider(Shape.Plane(Vector3f(0f, 1f, 0f), 0.5f)))
    }

    override fun teardown() {
        val particles = world.all<Particle>().map { it.first }.toList()
        particles.forEach { world.destroy(it) }
        super.teardown()
    }

    override fun update(dt: Float) {
        orbit.update(dt)

        val space = engine.window.input.isDown(GLFW_KEY_SPACE)
        val b = engine.window.input.isDown(GLFW_KEY_B)
        if (space && !spaceHeld) spawnBall()
        if (b && !bHeld) spawnCrate()
        spaceHeld = space; bHeld = b

        // Despawn fallen
        val toKill = ArrayList<EntityId>()
        for ((id, _) in world.all<Rigidbody>()) {
            val tr = world.get<Transform>(id) ?: continue
            if (tr.position.y < -40f) toKill += id
        }
        toKill.forEach { world.destroy(it); ownedEntities.remove(it) }

        physics.gravity.y = -gravityStrength

        // Light
        val rad = Math.toRadians(lightAngle.toDouble()).toFloat()
        engine.camera.lightDirection.set(cos(rad), lightHeight, sin(rad)).normalize()

        // UI
        DebugUi.panel("Physics", 10f, 130f, 220f) {
            DebugUi.valueInt("balls", ballsSpawned)
            DebugUi.valueInt("crates", cratesSpawned)
            DebugUi.separator()
            gravityStrength = DebugUi.slider("gravity", gravityStrength, 0f, 30f)
            lightAngle = DebugUi.slider("light_yaw", lightAngle, 0f, 360f)
            lightHeight = DebugUi.slider("light_pitch", lightHeight, 0.1f, 2f)
            DebugUi.separator()
            if (DebugUi.button("Drop Ball")) spawnBall()
            if (DebugUi.button("Drop Crate")) spawnCrate()
            if (DebugUi.button("Clear All")) clearDynamics()
            DebugUi.separator()
            DebugUi.text("SPACE  ball", 0xFFAAAAAA.toInt())
            DebugUi.text("B      crate", 0xFFAAAAAA.toInt())
            DebugUi.text("Q/E R/F  cam", 0xFFAAAAAA.toInt())
        }
    }

    private fun spawnBall() {
        val e = spawn()
        val pos = Vector3f(Random.nextFloat() * 6f - 3f, 8f, Random.nextFloat() * 6f - 3f)
        world.add(e, Transform(position = pos))
        val color = Vector3f(
            0.6f + Random.nextFloat() * 0.4f,
            0.4f + Random.nextFloat() * 0.4f,
            0.2f + Random.nextFloat() * 0.4f)
        world.add(e, MeshRenderer(sphereMesh, color = color))
        world.add(e, Collider(Shape.Sphere(0.4f)))
        world.add(e, Rigidbody(mass = 1f, restitution = 0.55f, linearDamping = 0.05f))
        ballsSpawned++
    }

    private fun spawnCrate() {
        val e = spawn()
        val pos = Vector3f(Random.nextFloat() * 4f - 2f, 10f, Random.nextFloat() * 4f - 2f)
        world.add(e, Transform(position = pos))
        val color = Vector3f(
            0.7f + Random.nextFloat() * 0.3f,
            0.55f + Random.nextFloat() * 0.3f,
            0.2f + Random.nextFloat() * 0.2f)
        world.add(e, MeshRenderer(crateMesh, color = color))
        world.add(e, Collider(Shape.Box(Vector3f(0.5f, 0.5f, 0.5f))))
        world.add(e, Rigidbody(mass = 4f, restitution = 0.25f, linearDamping = 0.15f))
        cratesSpawned++
    }

    private fun clearDynamics() {
        val ids = world.all<Rigidbody>().map { it.first }.toList()
        ids.forEach { world.destroy(it); ownedEntities.remove(it) }
        ballsSpawned = 0; cratesSpawned = 0
    }
}

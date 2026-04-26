package ru.kseonyt.foxlive.game.scenes

import org.joml.Vector3f
import org.lwjgl.glfw.GLFW.*
import ru.kseonyt.foxlive.core.Engine
import ru.kseonyt.foxlive.core.Scene
import ru.kseonyt.foxlive.ecs.*
import ru.kseonyt.foxlive.game.OrbitCameraController
import ru.kseonyt.foxlive.game.Particle
import ru.kseonyt.foxlive.game.ParticleEmitter
import ru.kseonyt.foxlive.render.Mesh
import ru.kseonyt.foxlive.ui.DebugUi
import kotlin.random.Random

private const val GRID = 20
private const val CELL = 1.0f

private enum class Dir(val dx: Int, val dz: Int) {
    NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);
    fun opposite(): Dir = when (this) { NORTH -> SOUTH; SOUTH -> NORTH; EAST -> WEST; WEST -> EAST }
}

private class GridPos(var x: Int, var z: Int) : Component
private class SnakeHead(
    var dir: Dir = Dir.EAST,
    var nextDir: Dir = Dir.EAST,
    var stepInterval: Float = 0.18f,
    var stepTimer: Float = 0f,
    val body: ArrayDeque<EntityId> = ArrayDeque(),
    var pendingGrowth: Int = 2,
) : Component
private class Food : Component

class SnakeScene(engine: Engine) : Scene(engine) {
    override val name: String = "Snake 3D"

    private val bodyMesh = Mesh.cube(Vector3f(0.2f, 0.85f, 0.35f))
    private val headMesh = Mesh.cube(Vector3f(0.95f, 0.85f, 0.2f))
    private val foodMesh = Mesh.cube(Vector3f(0.95f, 0.25f, 0.25f))
    private val groundMesh = Mesh.plane(GRID * CELL, Vector3f(0.18f, 0.18f, 0.22f))
    private val wallMesh = Mesh.cube(Vector3f(0.35f, 0.35f, 0.45f))
    private val sparkMesh = Mesh.cube(Vector3f(1f, 1f, 1f))

    private val orbit = OrbitCameraController(engine)
    private var headEntity: EntityId = -1
    private var foodEaten: Int = 0
    private var lightAngle: Float = 35f
    private var lightHeight: Float = 0.8f

    override fun setup() {
        engine.renderer?.let { it.clearR = 0.05f; it.clearG = 0.07f; it.clearB = 0.10f }

        val ground = spawn()
        world.add(ground, Transform(Vector3f(0f, -0.5f, 0f)))
        world.add(ground, MeshRenderer(groundMesh))

        for (i in -1..GRID) {
            spawnWall(i, -1); spawnWall(i, GRID); spawnWall(-1, i); spawnWall(GRID, i)
        }

        headEntity = spawn()
        val sx = GRID / 2; val sz = GRID / 2
        world.add(headEntity, GridPos(sx, sz))
        world.add(headEntity, Transform(toWorld(sx, sz)))
        world.add(headEntity, MeshRenderer(headMesh))
        world.add(headEntity, SnakeHead())
        world.add(headEntity, ParticleEmitter(
            particleMesh = sparkMesh,
            rate = 30f,
            lifetime = 0.9f,
            startScale = 0.18f,
            endScale = 0.02f,
            startColor = Vector3f(1.0f, 0.85f, 0.35f),
            endColor = Vector3f(0.85f, 0.15f, 0.05f),
        ))
        spawnFood()
    }

    override fun teardown() {
        // Destroy lingering particles so they don't leak into the next scene
        val particles = world.all<Particle>().map { it.first }.toList()
        particles.forEach { world.destroy(it) }
        super.teardown()
    }

    override fun update(dt: Float) {
        orbit.update(dt)

        val head = world.get<SnakeHead>(headEntity) ?: return
        keyToDir()?.let { d -> if (d != head.dir.opposite()) head.nextDir = d }

        head.stepTimer += dt
        if (head.stepTimer >= head.stepInterval) {
            head.stepTimer = 0f
            head.dir = head.nextDir
            tickSnake(head)
        }

        // Light direction — controlled by sliders
        val rad = Math.toRadians(lightAngle.toDouble()).toFloat()
        engine.camera.lightDirection.set(
            kotlin.math.cos(rad), lightHeight, kotlin.math.sin(rad)
        ).normalize()

        // UI
        DebugUi.panel("Snake", 10f, 130f, 220f) {
            DebugUi.valueInt("length", 1 + head.body.size)
            DebugUi.valueFloat("step_s", head.stepInterval, 3)
            DebugUi.valueInt("food_eaten", foodEaten)
            DebugUi.separator()
            lightAngle = DebugUi.slider("light_yaw", lightAngle, 0f, 360f)
            lightHeight = DebugUi.slider("light_pitch", lightHeight, 0.1f, 2f)
            DebugUi.separator()
            DebugUi.text("WASD/Arrows  move", 0xFFAAAAAA.toInt())
            DebugUi.text("Q/E R/F      camera", 0xFFAAAAAA.toInt())
            DebugUi.text("Z/X          zoom", 0xFFAAAAAA.toInt())
        }
    }

    private fun spawnWall(x: Int, z: Int) {
        val e = spawn()
        world.add(e, Transform(toWorld(x, z)))
        world.add(e, MeshRenderer(wallMesh))
    }

    private fun toWorld(gx: Int, gz: Int): Vector3f =
        Vector3f((gx - GRID / 2).toFloat() * CELL, 0f, (gz - GRID / 2).toFloat() * CELL)

    private fun spawnFood() {
        val occupied = HashSet<Long>()
        for ((_, gp) in world.all<GridPos>()) {
            occupied += (gp.x.toLong() shl 32) or (gp.z.toLong() and 0xFFFFFFFFL)
        }
        var fx: Int; var fz: Int
        do { fx = Random.nextInt(0, GRID); fz = Random.nextInt(0, GRID) }
        while (((fx.toLong() shl 32) or (fz.toLong() and 0xFFFFFFFFL)) in occupied)
        val e = spawn()
        world.add(e, GridPos(fx, fz))
        world.add(e, Transform(toWorld(fx, fz)))
        world.add(e, MeshRenderer(foodMesh))
        world.add(e, Food())
    }

    private fun tickSnake(head: SnakeHead) {
        val gp = world.get<GridPos>(headEntity) ?: return
        val nx = gp.x + head.dir.dx
        val nz = gp.z + head.dir.dz
        if (nx !in 0 until GRID || nz !in 0 until GRID) { reset(); return }
        for (bid in head.body) {
            val bp = world.get<GridPos>(bid) ?: continue
            if (bp.x == nx && bp.z == nz) { reset(); return }
        }
        var ate: EntityId = -1
        for ((id, _) in world.all<Food>()) {
            val fp = world.get<GridPos>(id) ?: continue
            if (fp.x == nx && fp.z == nz) { ate = id; break }
        }
        // push old head pos as new body segment
        val seg = spawn()
        world.add(seg, GridPos(gp.x, gp.z))
        world.add(seg, Transform(toWorld(gp.x, gp.z)))
        world.add(seg, MeshRenderer(bodyMesh))
        head.body.addFirst(seg)

        gp.x = nx; gp.z = nz
        world.get<Transform>(headEntity)?.position?.set(toWorld(nx, nz))

        if (ate != -1) {
            world.destroy(ate); ownedEntities.remove(ate)
            head.pendingGrowth += 1
            head.stepInterval = (head.stepInterval - 0.005f).coerceAtLeast(0.07f)
            foodEaten++
            spawnFood()
        }
        if (head.pendingGrowth > 0) head.pendingGrowth--
        else {
            val tail = head.body.removeLast()
            world.destroy(tail); ownedEntities.remove(tail)
        }
    }

    private fun reset() {
        val head = world.get<SnakeHead>(headEntity) ?: return
        for (bid in head.body) { world.destroy(bid); ownedEntities.remove(bid) }
        head.body.clear()
        head.dir = Dir.EAST; head.nextDir = Dir.EAST
        head.pendingGrowth = 2; head.stepInterval = 0.18f
        foodEaten = 0
        val gp = world.get<GridPos>(headEntity)!!
        gp.x = GRID / 2; gp.z = GRID / 2
        world.get<Transform>(headEntity)?.position?.set(toWorld(gp.x, gp.z))
        val foods = world.all<Food>().map { it.first }.toList()
        foods.forEach { world.destroy(it); ownedEntities.remove(it) }
        spawnFood()
    }

    private fun keyToDir(): Dir? {
        val w = engine.window
        return when {
            w.input.isDown(GLFW_KEY_UP) || w.input.isDown(GLFW_KEY_W) -> Dir.NORTH
            w.input.isDown(GLFW_KEY_DOWN) || w.input.isDown(GLFW_KEY_S) -> Dir.SOUTH
            w.input.isDown(GLFW_KEY_LEFT) || w.input.isDown(GLFW_KEY_A) -> Dir.WEST
            w.input.isDown(GLFW_KEY_RIGHT) || w.input.isDown(GLFW_KEY_D) -> Dir.EAST
            else -> null
        }
    }
}

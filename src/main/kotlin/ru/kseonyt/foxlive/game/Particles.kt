package ru.kseonyt.foxlive.game

import org.joml.Vector3f
import ru.kseonyt.foxlive.ecs.*
import ru.kseonyt.foxlive.render.Mesh
import kotlin.random.Random

/**
 * Continuously spawns short-lived particle entities at the emitter's Transform position.
 */
class ParticleEmitter(
    val particleMesh: Mesh,
    var rate: Float = 30f,                              // particles per second
    var lifetime: Float = 1.2f,
    val velocityMin: Vector3f = Vector3f(-1f, 2f, -1f),
    val velocityMax: Vector3f = Vector3f(1f, 4f, 1f),
    val gravity: Vector3f = Vector3f(0f, -2f, 0f),
    var startScale: Float = 0.18f,
    var endScale: Float = 0.02f,
    val startColor: Vector3f = Vector3f(1f, 0.8f, 0.3f),
    val endColor: Vector3f = Vector3f(0.7f, 0.1f, 0.05f),
    var enabled: Boolean = true,
    var spawnAccumulator: Float = 0f,
) : Component

class Particle(
    val velocity: Vector3f,
    var age: Float = 0f,
    val lifetime: Float,
    val startScale: Float,
    val endScale: Float,
    val startColor: Vector3f,
    val endColor: Vector3f,
    val gravity: Vector3f,
) : Component

class ParticleSystem(world: World) : System(world) {
    var liveParticles: Int = 0; private set
    var emitterCount: Int = 0; private set

    override fun update(dt: Float) {
        // 1) Emit new particles per emitter
        emitterCount = 0
        for ((id, em) in world.all<ParticleEmitter>()) {
            emitterCount++
            if (!em.enabled) continue
            val tr = world.get<Transform>(id) ?: continue
            em.spawnAccumulator += em.rate * dt
            val toSpawn = em.spawnAccumulator.toInt()
            em.spawnAccumulator -= toSpawn
            for (n in 0 until toSpawn) spawn(em, tr.position)
        }

        // 2) Age, integrate, update visuals
        liveParticles = 0
        val toDestroy = ArrayList<Int>()
        for ((id, p) in world.all<Particle>()) {
            val tr = world.get<Transform>(id) ?: continue
            val mr = world.get<ru.kseonyt.foxlive.ecs.MeshRenderer>(id)
            p.age += dt
            if (p.age >= p.lifetime) { toDestroy += id; continue }
            val t = p.age / p.lifetime
            // integrate
            p.velocity.x += p.gravity.x * dt
            p.velocity.y += p.gravity.y * dt
            p.velocity.z += p.gravity.z * dt
            tr.position.x += p.velocity.x * dt
            tr.position.y += p.velocity.y * dt
            tr.position.z += p.velocity.z * dt
            // size + color
            val s = p.startScale + (p.endScale - p.startScale) * t
            tr.scale.set(s, s, s)
            mr?.color?.set(
                p.startColor.x + (p.endColor.x - p.startColor.x) * t,
                p.startColor.y + (p.endColor.y - p.startColor.y) * t,
                p.startColor.z + (p.endColor.z - p.startColor.z) * t,
            )
            liveParticles++
        }
        toDestroy.forEach { world.destroy(it) }
    }

    private fun spawn(em: ParticleEmitter, origin: Vector3f) {
        val e = world.create()
        val pos = Vector3f(
            origin.x + Random.nextFloat() * 0.3f - 0.15f,
            origin.y + Random.nextFloat() * 0.3f - 0.15f,
            origin.z + Random.nextFloat() * 0.3f - 0.15f,
        )
        val vel = Vector3f(
            em.velocityMin.x + Random.nextFloat() * (em.velocityMax.x - em.velocityMin.x),
            em.velocityMin.y + Random.nextFloat() * (em.velocityMax.y - em.velocityMin.y),
            em.velocityMin.z + Random.nextFloat() * (em.velocityMax.z - em.velocityMin.z),
        )
        world.add(e, Transform(position = pos, scale = Vector3f(em.startScale, em.startScale, em.startScale)))
        world.add(e, MeshRenderer(em.particleMesh, color = Vector3f(em.startColor)))
        world.add(e, Particle(
            velocity = vel,
            lifetime = em.lifetime,
            startScale = em.startScale,
            endScale = em.endScale,
            startColor = Vector3f(em.startColor),
            endColor = Vector3f(em.endColor),
            gravity = Vector3f(em.gravity),
        ))
    }
}

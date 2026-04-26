package ru.kseonyt.foxlive.physics

import org.joml.Vector3f
import ru.kseonyt.foxlive.ecs.Component
import ru.kseonyt.foxlive.ecs.System
import ru.kseonyt.foxlive.ecs.Transform
import ru.kseonyt.foxlive.ecs.World

sealed class Shape {
    data class Sphere(val radius: Float) : Shape()
    /** AABB (no rotation). halfExtents is half-width along each axis. */
    data class Box(val halfExtents: Vector3f) : Shape()
    /** Plane equation n·x + d = 0; normal must be unit length. */
    data class Plane(val normal: Vector3f, val d: Float) : Shape()
}

class Collider(val shape: Shape) : Component

class Rigidbody(
    var mass: Float = 1f,
    val velocity: Vector3f = Vector3f(),
    val accumulatedForce: Vector3f = Vector3f(),
    var linearDamping: Float = 0.05f,
    var restitution: Float = 0.5f,
    var kinematic: Boolean = false,
) : Component {
    val invMass: Float get() = if (kinematic || mass <= 0f) 0f else 1f / mass
    fun applyForce(f: Vector3f) { accumulatedForce.add(f) }
    fun applyImpulse(j: Vector3f) {
        val im = invMass; if (im == 0f) return
        velocity.x += j.x * im; velocity.y += j.y * im; velocity.z += j.z * im
    }
}

private data class Contact(val normal: Vector3f, val penetration: Float) {
    /** Flip a contact normal (when shapes were swapped during dispatch). */
    fun inverted(): Contact = Contact(Vector3f(-normal.x, -normal.y, -normal.z), penetration)
}

class PhysicsSystem(
    world: World,
    val gravity: Vector3f = Vector3f(0f, -9.81f, 0f),
    val positionalSlop: Float = 0.01f,
    val positionalCorrection: Float = 0.8f,
) : System(world) {
    var bodyCount: Int = 0; private set
    var contactCount: Int = 0; private set
    var colliderCount: Int = 0; private set

    override fun update(dt: Float) {
        bodyCount = 0; contactCount = 0
        // Integration pass
        for ((id, rb) in world.all<Rigidbody>()) {
            val tr = world.get<Transform>(id) ?: continue
            if (!rb.kinematic) {
                rb.velocity.x += (gravity.x + rb.accumulatedForce.x * rb.invMass) * dt
                rb.velocity.y += (gravity.y + rb.accumulatedForce.y * rb.invMass) * dt
                rb.velocity.z += (gravity.z + rb.accumulatedForce.z * rb.invMass) * dt
                val damping = (1f - rb.linearDamping * dt).coerceIn(0f, 1f)
                rb.velocity.mul(damping)
                tr.position.x += rb.velocity.x * dt
                tr.position.y += rb.velocity.y * dt
                tr.position.z += rb.velocity.z * dt
            }
            rb.accumulatedForce.set(0f, 0f, 0f)
            bodyCount++
        }

        // Collect collider entities for pairwise iteration
        val ids = ArrayList<Int>()
        for ((id, _) in world.all<Collider>()) ids += id
        colliderCount = ids.size

        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val ai = ids[i]; val bi = ids[j]
                val ca = world.get<Collider>(ai) ?: continue
                val cb = world.get<Collider>(bi) ?: continue
                val ta = world.get<Transform>(ai) ?: continue
                val tb = world.get<Transform>(bi) ?: continue
                val ra = world.get<Rigidbody>(ai)
                val rbb = world.get<Rigidbody>(bi)
                if (ra == null && rbb == null) continue // both fully static — skip
                val contact = collide(ca.shape, ta.position, cb.shape, tb.position) ?: continue
                resolve(ta, tb, ra, rbb, contact)
                contactCount++
            }
        }
    }

    private fun collide(a: Shape, pa: Vector3f, b: Shape, pb: Vector3f): Contact? = when {
        a is Shape.Sphere && b is Shape.Sphere -> sphereSphere(pa, a.radius, pb, b.radius)
        a is Shape.Sphere && b is Shape.Plane  -> spherePlane(pa, a.radius, b.normal, b.d)
        a is Shape.Plane  && b is Shape.Sphere -> spherePlane(pb, b.radius, a.normal, a.d)?.inverted()
        a is Shape.Sphere && b is Shape.Box    -> sphereBox(pa, a.radius, pb, b.halfExtents)
        a is Shape.Box    && b is Shape.Sphere -> sphereBox(pb, b.radius, pa, a.halfExtents)?.inverted()
        a is Shape.Box    && b is Shape.Box    -> boxBox(pa, a.halfExtents, pb, b.halfExtents)
        a is Shape.Box    && b is Shape.Plane  -> boxPlane(pa, a.halfExtents, b.normal, b.d)
        a is Shape.Plane  && b is Shape.Box    -> boxPlane(pb, b.halfExtents, a.normal, a.d)?.inverted()
        else -> null
    }

    private fun sphereSphere(pa: Vector3f, ra: Float, pb: Vector3f, rb: Float): Contact? {
        val dx = pb.x - pa.x; val dy = pb.y - pa.y; val dz = pb.z - pa.z
        val r = ra + rb
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= r * r) return null
        val d = kotlin.math.sqrt(d2.toDouble()).toFloat()
        val n = if (d > 1e-6f) Vector3f(dx / d, dy / d, dz / d) else Vector3f(0f, 1f, 0f)
        return Contact(n, r - d)
    }

    private fun spherePlane(p: Vector3f, r: Float, n: Vector3f, d: Float): Contact? {
        val dist = n.x * p.x + n.y * p.y + n.z * p.z + d
        if (dist >= r) return null
        // Sphere is shape A, plane is B — normal points from A toward B (into the plane)
        return Contact(Vector3f(-n.x, -n.y, -n.z), r - dist)
    }

    private fun sphereBox(sp: Vector3f, sr: Float, bp: Vector3f, hext: Vector3f): Contact? {
        // Closest point on AABB to sphere center
        val cx = (sp.x).coerceIn(bp.x - hext.x, bp.x + hext.x)
        val cy = (sp.y).coerceIn(bp.y - hext.y, bp.y + hext.y)
        val cz = (sp.z).coerceIn(bp.z - hext.z, bp.z + hext.z)
        val dx = sp.x - cx; val dy = sp.y - cy; val dz = sp.z - cz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= sr * sr) return null
        val d = kotlin.math.sqrt(d2.toDouble()).toFloat()
        val nx: Float; val ny: Float; val nz: Float
        if (d > 1e-6f) { nx = dx / d; ny = dy / d; nz = dz / d }
        else { nx = 0f; ny = 1f; nz = 0f }
        // Normal points from box to sphere (B → A); we want from A to B
        return Contact(Vector3f(-nx, -ny, -nz), sr - d)
    }

    private fun boxBox(pa: Vector3f, ha: Vector3f, pb: Vector3f, hb: Vector3f): Contact? {
        val dx = pb.x - pa.x; val ox = (ha.x + hb.x) - kotlin.math.abs(dx)
        if (ox <= 0) return null
        val dy = pb.y - pa.y; val oy = (ha.y + hb.y) - kotlin.math.abs(dy)
        if (oy <= 0) return null
        val dz = pb.z - pa.z; val oz = (ha.z + hb.z) - kotlin.math.abs(dz)
        if (oz <= 0) return null
        // Smallest overlap axis
        return when {
            ox < oy && ox < oz -> Contact(Vector3f(if (dx < 0) -1f else 1f, 0f, 0f), ox)
            oy < oz            -> Contact(Vector3f(0f, if (dy < 0) -1f else 1f, 0f), oy)
            else               -> Contact(Vector3f(0f, 0f, if (dz < 0) -1f else 1f), oz)
        }
    }

    private fun boxPlane(p: Vector3f, h: Vector3f, n: Vector3f, d: Float): Contact? {
        // Distance from box center to plane minus the projected half-extents
        val r = h.x * kotlin.math.abs(n.x) + h.y * kotlin.math.abs(n.y) + h.z * kotlin.math.abs(n.z)
        val dist = n.x * p.x + n.y * p.y + n.z * p.z + d
        if (dist >= r) return null
        return Contact(Vector3f(-n.x, -n.y, -n.z), r - dist)
    }

    private fun resolve(
        ta: Transform, tb: Transform,
        ra: Rigidbody?, rb: Rigidbody?,
        c: Contact,
    ) {
        val invA = ra?.invMass ?: 0f
        val invB = rb?.invMass ?: 0f
        val total = invA + invB
        if (total <= 0f) return

        // Positional correction (avoid sinking)
        val corrMag = ((c.penetration - positionalSlop).coerceAtLeast(0f) / total) * positionalCorrection
        ta.position.x -= c.normal.x * corrMag * invA
        ta.position.y -= c.normal.y * corrMag * invA
        ta.position.z -= c.normal.z * corrMag * invA
        tb.position.x += c.normal.x * corrMag * invB
        tb.position.y += c.normal.y * corrMag * invB
        tb.position.z += c.normal.z * corrMag * invB

        // Velocity along contact normal (B−A)
        val vax = ra?.velocity?.x ?: 0f; val vay = ra?.velocity?.y ?: 0f; val vaz = ra?.velocity?.z ?: 0f
        val vbx = rb?.velocity?.x ?: 0f; val vby = rb?.velocity?.y ?: 0f; val vbz = rb?.velocity?.z ?: 0f
        val rvx = vbx - vax; val rvy = vby - vay; val rvz = vbz - vaz
        val vAlong = rvx * c.normal.x + rvy * c.normal.y + rvz * c.normal.z
        if (vAlong > 0) return // already separating
        val e = ((ra?.restitution ?: 0f) + (rb?.restitution ?: 0f)) * 0.5f
        val j = -(1f + e) * vAlong / total
        val ix = c.normal.x * j; val iy = c.normal.y * j; val iz = c.normal.z * j
        ra?.let {
            if (!it.kinematic) {
                it.velocity.x -= ix * invA; it.velocity.y -= iy * invA; it.velocity.z -= iz * invA
            }
        }
        rb?.let {
            if (!it.kinematic) {
                it.velocity.x += ix * invB; it.velocity.y += iy * invB; it.velocity.z += iz * invB
            }
        }
    }
}

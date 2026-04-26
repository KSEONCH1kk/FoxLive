package ru.kseonyt.foxlive.render

import org.joml.Vector2f
import org.joml.Vector3f

/**
 * Vertex layout: position(3) + normal(3) + color(3) + uv(2) + tangent(3) = 14 floats = 56 bytes.
 * Bitangent is computed in the shader as cross(N, T).
 */
data class Vertex(
    val pos: Vector3f,
    val normal: Vector3f,
    val color: Vector3f,
    val uv: Vector2f,
    val tangent: Vector3f,
)

class Mesh(val vertices: FloatArray, val indices: IntArray) {
    val indexCount: Int get() = indices.size
    var gpuHandle: Long = 0L
    var indexHandle: Long = 0L
    var vertexMemory: Long = 0L
    var indexMemory: Long = 0L
    var uploaded: Boolean = false

    companion object {
        const val FLOATS_PER_VERTEX = 14
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

        fun fromVertices(verts: List<Vertex>, indices: IntArray): Mesh {
            val arr = FloatArray(verts.size * FLOATS_PER_VERTEX)
            verts.forEachIndexed { i, v ->
                val o = i * FLOATS_PER_VERTEX
                arr[o] = v.pos.x; arr[o + 1] = v.pos.y; arr[o + 2] = v.pos.z
                arr[o + 3] = v.normal.x; arr[o + 4] = v.normal.y; arr[o + 5] = v.normal.z
                arr[o + 6] = v.color.x; arr[o + 7] = v.color.y; arr[o + 8] = v.color.z
                arr[o + 9] = v.uv.x; arr[o + 10] = v.uv.y
                arr[o + 11] = v.tangent.x; arr[o + 12] = v.tangent.y; arr[o + 13] = v.tangent.z
            }
            return Mesh(arr, indices)
        }

        fun cube(color: Vector3f = Vector3f(1f, 1f, 1f)): Mesh {
            // Each face: 4 vertices in order TL, TR, BR, BL (matching tangent T = U axis direction)
            data class Face(val n: Vector3f, val t: Vector3f, val verts: Array<Vector3f>)
            val faces = arrayOf(
                // +X — N=+X, T (along U) = -Z
                Face(Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, -1f), arrayOf(
                    Vector3f(0.5f, -0.5f, 0.5f), Vector3f(0.5f, -0.5f, -0.5f),
                    Vector3f(0.5f, 0.5f, -0.5f), Vector3f(0.5f, 0.5f, 0.5f))),
                // -X — N=-X, T = +Z
                Face(Vector3f(-1f, 0f, 0f), Vector3f(0f, 0f, 1f), arrayOf(
                    Vector3f(-0.5f, -0.5f, -0.5f), Vector3f(-0.5f, -0.5f, 0.5f),
                    Vector3f(-0.5f, 0.5f, 0.5f), Vector3f(-0.5f, 0.5f, -0.5f))),
                // +Y — N=+Y, T = +X
                Face(Vector3f(0f, 1f, 0f), Vector3f(1f, 0f, 0f), arrayOf(
                    Vector3f(-0.5f, 0.5f, 0.5f), Vector3f(0.5f, 0.5f, 0.5f),
                    Vector3f(0.5f, 0.5f, -0.5f), Vector3f(-0.5f, 0.5f, -0.5f))),
                // -Y — N=-Y, T = +X
                Face(Vector3f(0f, -1f, 0f), Vector3f(1f, 0f, 0f), arrayOf(
                    Vector3f(-0.5f, -0.5f, -0.5f), Vector3f(0.5f, -0.5f, -0.5f),
                    Vector3f(0.5f, -0.5f, 0.5f), Vector3f(-0.5f, -0.5f, 0.5f))),
                // +Z — N=+Z, T = +X
                Face(Vector3f(0f, 0f, 1f), Vector3f(1f, 0f, 0f), arrayOf(
                    Vector3f(-0.5f, -0.5f, 0.5f), Vector3f(0.5f, -0.5f, 0.5f),
                    Vector3f(0.5f, 0.5f, 0.5f), Vector3f(-0.5f, 0.5f, 0.5f))),
                // -Z — N=-Z, T = -X
                Face(Vector3f(0f, 0f, -1f), Vector3f(-1f, 0f, 0f), arrayOf(
                    Vector3f(0.5f, -0.5f, -0.5f), Vector3f(-0.5f, -0.5f, -0.5f),
                    Vector3f(-0.5f, 0.5f, -0.5f), Vector3f(0.5f, 0.5f, -0.5f))),
            )
            val verts = ArrayList<Vertex>(24)
            val indices = ArrayList<Int>(36)
            // Per face quad: 0=BL(uv 0,0), 1=BR(1,0), 2=TR(1,1), 3=TL(0,1)
            val uvs = arrayOf(Vector2f(0f, 0f), Vector2f(1f, 0f), Vector2f(1f, 1f), Vector2f(0f, 1f))
            for (face in faces) {
                val base = verts.size
                for (i in 0..3) {
                    verts += Vertex(
                        Vector3f(face.verts[i]),
                        Vector3f(face.n),
                        Vector3f(color),
                        Vector2f(uvs[i]),
                        Vector3f(face.t)
                    )
                }
                indices += base; indices += base + 1; indices += base + 2
                indices += base; indices += base + 2; indices += base + 3
            }
            return fromVertices(verts, indices.toIntArray())
        }

        fun sphere(radius: Float = 0.5f, segments: Int = 24, rings: Int = 16,
                   color: Vector3f = Vector3f(1f, 1f, 1f)): Mesh {
            val verts = ArrayList<Vertex>((rings + 1) * (segments + 1))
            val indices = ArrayList<Int>(rings * segments * 6)
            for (lat in 0..rings) {
                val theta = (lat.toDouble() / rings) * Math.PI
                val sinT = kotlin.math.sin(theta).toFloat()
                val cosT = kotlin.math.cos(theta).toFloat()
                for (lon in 0..segments) {
                    val phi = (lon.toDouble() / segments) * Math.PI * 2.0
                    val sinP = kotlin.math.sin(phi).toFloat()
                    val cosP = kotlin.math.cos(phi).toFloat()
                    val nx = cosP * sinT; val ny = cosT; val nz = sinP * sinT
                    val pos = Vector3f(nx * radius, ny * radius, nz * radius)
                    val normal = Vector3f(nx, ny, nz)
                    val uv = Vector2f(lon.toFloat() / segments, lat.toFloat() / rings)
                    val tangent = Vector3f(-sinP, 0f, cosP)
                    verts += Vertex(pos, normal, Vector3f(color), uv, tangent)
                }
            }
            for (lat in 0 until rings) {
                for (lon in 0 until segments) {
                    val a = lat * (segments + 1) + lon
                    val b = a + segments + 1
                    indices += a; indices += b; indices += a + 1
                    indices += b; indices += b + 1; indices += a + 1
                }
            }
            return fromVertices(verts, indices.toIntArray())
        }

        /**
         * Flat XZ plane subdivided into [subdivisions] × [subdivisions] quads, centered at origin.
         * Use for vertex-displacement effects like waves where you need many vertices.
         */
        fun planeSubdivided(
            size: Float, subdivisions: Int,
            color: Vector3f = Vector3f(0.18f, 0.42f, 0.72f), uvTile: Float = 8f,
        ): Mesh {
            val h = size * 0.5f
            val step = size / subdivisions
            val vCount = subdivisions + 1
            val verts = ArrayList<Vertex>(vCount * vCount)
            val n = Vector3f(0f, 1f, 0f)
            val t = Vector3f(1f, 0f, 0f)
            for (zi in 0..subdivisions) {
                for (xi in 0..subdivisions) {
                    val px = -h + xi * step
                    val pz = -h + zi * step
                    val u = (xi.toFloat() / subdivisions) * uvTile
                    val v = (zi.toFloat() / subdivisions) * uvTile
                    verts += Vertex(Vector3f(px, 0f, pz), Vector3f(n),
                        Vector3f(color), Vector2f(u, v), Vector3f(t))
                }
            }
            val indices = ArrayList<Int>(subdivisions * subdivisions * 6)
            for (zi in 0 until subdivisions) {
                for (xi in 0 until subdivisions) {
                    val a = zi * vCount + xi
                    val b = a + 1
                    val c = a + vCount
                    val d = c + 1
                    indices += a; indices += c; indices += b
                    indices += b; indices += c; indices += d
                }
            }
            return fromVertices(verts, indices.toIntArray())
        }

        fun plane(size: Float = 20f, color: Vector3f = Vector3f(0.2f, 0.2f, 0.25f), uvTile: Float = 1f): Mesh {
            val h = size * 0.5f
            val n = Vector3f(0f, 1f, 0f)
            val t = Vector3f(1f, 0f, 0f)
            val u = uvTile
            val v = listOf(
                Vertex(Vector3f(-h, 0f, -h), Vector3f(n), Vector3f(color), Vector2f(0f, 0f), Vector3f(t)),
                Vertex(Vector3f(h, 0f, -h), Vector3f(n), Vector3f(color), Vector2f(u, 0f), Vector3f(t)),
                Vertex(Vector3f(h, 0f, h), Vector3f(n), Vector3f(color), Vector2f(u, u), Vector3f(t)),
                Vertex(Vector3f(-h, 0f, h), Vector3f(n), Vector3f(color), Vector2f(0f, u), Vector3f(t)),
            )
            return fromVertices(v, intArrayOf(0, 1, 2, 0, 2, 3))
        }
    }
}

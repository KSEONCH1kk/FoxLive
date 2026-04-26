package ru.kseonyt.foxlive.game.scenes

import org.joml.Vector3f
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK10.*
import ru.kseonyt.foxlive.core.Engine
import ru.kseonyt.foxlive.core.Scene
import ru.kseonyt.foxlive.ecs.MeshRenderer
import ru.kseonyt.foxlive.ecs.Transform
import ru.kseonyt.foxlive.game.OrbitCameraController
import ru.kseonyt.foxlive.render.Mesh
import ru.kseonyt.foxlive.render.VulkanPipeline
import ru.kseonyt.foxlive.render.WaterPipeline
import ru.kseonyt.foxlive.ui.DebugUi
import kotlin.math.cos
import kotlin.math.sin

/**
 * Water sandbox: large subdivided plane animated entirely on the GPU via
 * sum-of-sines vertex displacement, with Schlick Fresnel + procedural sky
 * + sun specular in the fragment shader. Floating cubes give scale reference
 * and exercise the main pipeline alongside the water pipeline.
 */
class WaterScene(engine: Engine) : Scene(engine) {
    override val name: String = "Water"

    private val orbit = OrbitCameraController(engine, distance = 30f, pitch = -25f)

    private val waterMesh = Mesh.planeSubdivided(80f, 96, color = Vector3f(0.10f, 0.32f, 0.42f), uvTile = 1f)
    private val rockMesh = Mesh.cube(Vector3f(0.45f, 0.42f, 0.40f))
    private val woodMesh = Mesh.cube(Vector3f(0.55f, 0.36f, 0.20f))

    private var waterPipeline: WaterPipeline? = null

    private var lightAngle: Float = 55f
    private var lightHeight: Float = 0.55f

    override fun setup() {
        // Sky-coloured background blends with the procedural sky in fragment shader
        engine.renderer?.let {
            it.clearR = 0.78f; it.clearG = 0.86f; it.clearB = 0.94f
        }

        val r = engine.renderer ?: return
        rebuildPipeline()

        // Recreate water pipeline whenever the swapchain (and render pass) resets
        r.onSwapchainRecreated = { rebuildPipeline() }

        // Per-frame water draw inside the active render pass
        r.customRender = { cmd, _ -> drawWater(cmd) }

        // A few floating bobbers + sea-bed rocks for scale (rendered via main pipeline)
        for (i in 0 until 6) {
            val a = i * 1.0471f
            val rad = 6f + i * 0.7f
            spawnCube(woodMesh,
                Vector3f(cos(a) * rad, 0.4f, sin(a) * rad),
                Vector3f(0.6f, 0.5f, 0.4f))
        }
        for (i in 0 until 5) {
            val a = i * 1.2566f + 0.4f
            val rad = 14f + i * 0.5f
            spawnCube(rockMesh,
                Vector3f(cos(a) * rad, -0.6f, sin(a) * rad),
                Vector3f(1.4f, 0.6f, 1.4f))
        }
    }

    override fun teardown() {
        engine.renderer?.let {
            it.customRender = null
            it.onSwapchainRecreated = null
            it.waitIdle()
        }
        waterPipeline?.destroy()
        waterPipeline = null
        super.teardown()
    }

    override fun update(dt: Float) {
        orbit.update(dt)

        val rad = Math.toRadians(lightAngle.toDouble()).toFloat()
        engine.camera.lightDirection.set(cos(rad), lightHeight, sin(rad)).normalize()

        DebugUi.panel("Water", 10f, 130f, 220f) {
            DebugUi.text("Sum-of-sines GPU waves",  0xFFAAAAAA.toInt())
            DebugUi.text("Schlick Fresnel + sky",   0xFFAAAAAA.toInt())
            DebugUi.text("Procedural sun specular", 0xFFAAAAAA.toInt())
            DebugUi.separator()
            lightAngle  = DebugUi.slider("sun_yaw",   lightAngle,  0f, 360f)
            lightHeight = DebugUi.slider("sun_pitch", lightHeight, 0.05f, 2f)
            DebugUi.separator()
            DebugUi.text("Q/E R/F  camera", 0xFFAAAAAA.toInt())
            DebugUi.text("Z/X      zoom",   0xFFAAAAAA.toInt())
        }
    }

    private fun rebuildPipeline() {
        val r = engine.renderer ?: return
        waterPipeline?.destroy()
        waterPipeline = WaterPipeline(r.ctx, r.swapchain).apply { create() }
    }

    private fun drawWater(cmd: org.lwjgl.vulkan.VkCommandBuffer) {
        val r = engine.renderer ?: return
        val wp = waterPipeline ?: return
        if (!waterMesh.uploaded) r.uploadMesh(waterMesh)
        stackPush().use { st ->
            // Re-push the 96-byte block under the water pipeline's layout
            val pc = st.malloc(VulkanPipeline.PUSH_CONSTANT_SIZE)
            val vp = engine.camera.viewProjection(r.viewport.aspect)
            vp.get(0, pc)
            pc.position(64)
            pc.putFloat(engine.camera.lightDirection.x)
                .putFloat(engine.camera.lightDirection.y)
                .putFloat(engine.camera.lightDirection.z)
                .putFloat(0f)
            pc.position(80)
            pc.putFloat(engine.camera.position.x)
                .putFloat(engine.camera.position.y)
                .putFloat(engine.camera.position.z)
                .putFloat(r.elapsedSeconds)
            pc.position(0).limit(VulkanPipeline.PUSH_CONSTANT_SIZE)
            vkCmdPushConstants(cmd, wp.pipelineLayout,
                VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, pc)

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, wp.pipeline)
            val pBuf = st.longs(waterMesh.gpuHandle)
            val pOff = st.longs(0L)
            vkCmdBindVertexBuffers(cmd, 0, pBuf, pOff)
            vkCmdBindIndexBuffer(cmd, waterMesh.indexHandle, 0, VK_INDEX_TYPE_UINT32)
            vkCmdDrawIndexed(cmd, waterMesh.indexCount, 1, 0, 0, 0)
        }
    }

    private fun spawnCube(mesh: Mesh, pos: Vector3f, scale: Vector3f) {
        val e = spawn()
        world.add(e, Transform(position = pos, scale = scale))
        world.add(e, MeshRenderer(mesh))
    }
}

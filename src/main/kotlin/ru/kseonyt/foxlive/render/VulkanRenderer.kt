package ru.kseonyt.foxlive.render

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import ru.kseonyt.foxlive.core.Window
import ru.kseonyt.foxlive.ecs.MeshRenderer
import ru.kseonyt.foxlive.ecs.Transform
import ru.kseonyt.foxlive.ecs.World
import ru.kseonyt.foxlive.ui.DebugUi
import ru.kseonyt.foxlive.ui.UiRenderer
import java.nio.ByteBuffer

class InstanceBufferSlot(val handle: Long, val memory: Long, val mapped: ByteBuffer)

class VulkanRenderer(val window: Window, enableValidation: Boolean = false) {
    companion object {
        const val MAX_FRAMES_IN_FLIGHT = 2
        const val MAX_INSTANCES_PER_FRAME = 4096
        const val MAX_MATERIALS = 128
    }

    val ctx = VulkanContext(window, enableValidation)
    val swapchain = VulkanSwapchain(ctx)
    val pipeline = VulkanPipeline(ctx, swapchain)
    val ui = UiRenderer(ctx, swapchain, MAX_FRAMES_IN_FLIGHT)
    val postfx = PostfxPipeline(ctx, swapchain)
    /** Public — game/scene code can mutate any field, the values are read each frame. */
    val postFxParams = PostFxParams()
    val viewport = Viewport(0, 0, window.width, window.height)

    private val commandBuffers = ArrayList<VkCommandBuffer>(MAX_FRAMES_IN_FLIGHT)
    private val imageAvailable = LongArray(MAX_FRAMES_IN_FLIGHT)
    private val renderFinished = LongArray(MAX_FRAMES_IN_FLIGHT)
    private val inFlightFences = LongArray(MAX_FRAMES_IN_FLIGHT)
    private var currentFrame = 0

    private var descriptorPool: Long = 0L
    private val instanceSlots = ArrayList<InstanceBufferSlot>(MAX_FRAMES_IN_FLIGHT)

    private val meshes = HashSet<Mesh>()
    private val managedMaterials = HashSet<Material>()
    private lateinit var defaultDiffuse: Texture
    private lateinit var defaultNormal: Texture
    lateinit var defaultMaterial: Material
        private set

    var clearR = 0.05f; var clearG = 0.07f; var clearB = 0.10f

    /** Elapsed seconds since renderer init — fed into push constants for animated shaders. */
    var elapsedSeconds: Float = 0f
        private set

    /**
     * Optional callback invoked inside the active render pass after the main scene draws
     * but before the UI overlay. Scenes can bind their own pipelines/buffers and issue
     * draw commands here (e.g. water, post-effects). Push constants from the main pipeline
     * may not be compatible with custom pipelines — re-push if your layout differs.
     */
    var customRender: ((VkCommandBuffer, Int) -> Unit)? = null

    /**
     * Invoked after the swapchain (and therefore the render pass) has been recreated.
     * Scenes that own custom pipelines must rebuild them here — they reference the
     * destroyed render pass otherwise.
     */
    var onSwapchainRecreated: (() -> Unit)? = null

    private val pushBytes = MemoryUtil.memAlloc(VulkanPipeline.PUSH_CONSTANT_SIZE)
    private val tmpVP = Matrix4f()

    fun init() {
        ctx.init()
        swapchain.create()
        pipeline.create()
        createDescriptorPool()
        createCommandBuffers()
        createSyncObjects()
        createInstanceBuffers()
        createDefaultTextures()
        ui.init()
        postfx.create()
        viewport.width = swapchain.extentWidth
        viewport.height = swapchain.extentHeight
        println("[FoxLive] Vulkan ready — MSAA x${samplesToInt(swapchain.msaaSamples)}, " +
                "swapchain ${swapchain.extentWidth}x${swapchain.extentHeight}")
    }

    /** Reset DebugUi for the new frame. Game code populates it before drawFrame. */
    fun beginUiFrame() {
        val inp = window.input
        DebugUi.newFrame(
            ui.font,
            ui.fontDescriptorSet,
            swapchain.extentWidth.toFloat(),
            swapchain.extentHeight.toFloat(),
            inp.mouseX.toFloat(),
            inp.mouseY.toFloat(),
            inp.isMouseDown(),
        )
    }

    private fun samplesToInt(s: Int): Int = when (s) {
        VK_SAMPLE_COUNT_8_BIT -> 8; VK_SAMPLE_COUNT_4_BIT -> 4
        VK_SAMPLE_COUNT_2_BIT -> 2; else -> 1
    }

    private fun createDescriptorPool() = stackPush().use { st ->
        val sizes = VkDescriptorPoolSize.calloc(1, st)
        sizes.get(0)
            .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(MAX_MATERIALS * 2)
        val info = VkDescriptorPoolCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(sizes)
            .maxSets(MAX_MATERIALS)
        val p = st.mallocLong(1)
        Vk.check(vkCreateDescriptorPool(ctx.device, info, null, p))
        descriptorPool = p.get(0)
    }

    private fun createCommandBuffers() = stackPush().use { st ->
        val alloc = VkCommandBufferAllocateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(ctx.commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(MAX_FRAMES_IN_FLIGHT)
        val pCmd = st.mallocPointer(MAX_FRAMES_IN_FLIGHT)
        Vk.check(vkAllocateCommandBuffers(ctx.device, alloc, pCmd))
        for (i in 0 until MAX_FRAMES_IN_FLIGHT) commandBuffers += VkCommandBuffer(pCmd.get(i), ctx.device)
    }

    private fun createSyncObjects() = stackPush().use { st ->
        val sem = VkSemaphoreCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
        val fen = VkFenceCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            .flags(VK_FENCE_CREATE_SIGNALED_BIT)
        val pL = st.mallocLong(1)
        for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
            Vk.check(vkCreateSemaphore(ctx.device, sem, null, pL)); imageAvailable[i] = pL.get(0)
            Vk.check(vkCreateSemaphore(ctx.device, sem, null, pL)); renderFinished[i] = pL.get(0)
            Vk.check(vkCreateFence(ctx.device, fen, null, pL)); inFlightFences[i] = pL.get(0)
        }
    }

    private fun createInstanceBuffers() {
        val sizeBytes = (MAX_INSTANCES_PER_FRAME.toLong() * VulkanPipeline.INSTANCE_STRIDE.toLong())
        for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
            val pBuf = MemoryUtil.memAllocLong(1)
            val pMem = MemoryUtil.memAllocLong(1)
            try {
                ctx.createBuffer(sizeBytes,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuf, pMem)
                stackPush().use { st ->
                    val pData = st.mallocPointer(1)
                    vkMapMemory(ctx.device, pMem.get(0), 0, sizeBytes, 0, pData)
                    val mapped = MemoryUtil.memByteBuffer(pData.get(0), sizeBytes.toInt())
                    instanceSlots += InstanceBufferSlot(pBuf.get(0), pMem.get(0), mapped)
                }
            } finally {
                MemoryUtil.memFree(pBuf); MemoryUtil.memFree(pMem)
            }
        }
    }

    private fun createDefaultTextures() {
        defaultDiffuse = VulkanImage.defaultWhite(ctx)
        defaultNormal = VulkanImage.defaultFlatNormal(ctx)
        defaultMaterial = Material(null, null)
        ensureMaterialReady(defaultMaterial)
    }

    private fun ensureMaterialReady(mat: Material) {
        if (mat.initialized) return
        stackPush().use { st ->
            val pSetLayouts = st.longs(pipeline.descriptorSetLayout)
            val alloc = VkDescriptorSetAllocateInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(pSetLayouts)
            val p = st.mallocLong(1)
            Vk.check(vkAllocateDescriptorSets(ctx.device, alloc, p), "vkAllocateDescriptorSets")
            mat.descriptorSet = p.get(0)

            val diffuse = mat.diffuse ?: defaultDiffuse
            val normal = mat.normal ?: defaultNormal
            val infoDiffuse = VkDescriptorImageInfo.calloc(1, st)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .imageView(diffuse.view)
                .sampler(diffuse.sampler)
            val infoNormal = VkDescriptorImageInfo.calloc(1, st)
                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .imageView(normal.view)
                .sampler(normal.sampler)

            val writes = VkWriteDescriptorSet.calloc(2, st)
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(mat.descriptorSet).dstBinding(0).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(infoDiffuse)
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(mat.descriptorSet).dstBinding(1).dstArrayElement(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(infoNormal)
            vkUpdateDescriptorSets(ctx.device, writes, null)
        }
        mat.initialized = true
        managedMaterials += mat
    }

    fun uploadMesh(mesh: Mesh) {
        if (mesh.uploaded) return
        // Vertex buffer
        val vSize = (mesh.vertices.size * 4).toLong()
        val vBuf = MemoryUtil.memAllocLong(1); val vMem = MemoryUtil.memAllocLong(1)
        val sBuf = MemoryUtil.memAllocLong(1); val sMem = MemoryUtil.memAllocLong(1)
        try {
            ctx.createBuffer(vSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, sBuf, sMem)
            stackPush().use { st ->
                val pData = st.mallocPointer(1)
                vkMapMemory(ctx.device, sMem.get(0), 0, vSize, 0, pData)
                pData.getByteBuffer(0, vSize.toInt()).asFloatBuffer().put(mesh.vertices)
                vkUnmapMemory(ctx.device, sMem.get(0))
            }
            ctx.createBuffer(vSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, vBuf, vMem)
            ctx.copyBuffer(sBuf.get(0), vBuf.get(0), vSize)
            vkDestroyBuffer(ctx.device, sBuf.get(0), null)
            vkFreeMemory(ctx.device, sMem.get(0), null)
            mesh.gpuHandle = vBuf.get(0); mesh.vertexMemory = vMem.get(0)
        } finally {
            MemoryUtil.memFree(vBuf); MemoryUtil.memFree(vMem)
            MemoryUtil.memFree(sBuf); MemoryUtil.memFree(sMem)
        }

        val iSize = (mesh.indices.size * 4).toLong()
        val iBuf = MemoryUtil.memAllocLong(1); val iMem = MemoryUtil.memAllocLong(1)
        val ssBuf = MemoryUtil.memAllocLong(1); val ssMem = MemoryUtil.memAllocLong(1)
        try {
            ctx.createBuffer(iSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, ssBuf, ssMem)
            stackPush().use { st ->
                val pData = st.mallocPointer(1)
                vkMapMemory(ctx.device, ssMem.get(0), 0, iSize, 0, pData)
                pData.getByteBuffer(0, iSize.toInt()).asIntBuffer().put(mesh.indices)
                vkUnmapMemory(ctx.device, ssMem.get(0))
            }
            ctx.createBuffer(iSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, iBuf, iMem)
            ctx.copyBuffer(ssBuf.get(0), iBuf.get(0), iSize)
            vkDestroyBuffer(ctx.device, ssBuf.get(0), null)
            vkFreeMemory(ctx.device, ssMem.get(0), null)
            mesh.indexHandle = iBuf.get(0); mesh.indexMemory = iMem.get(0)
        } finally {
            MemoryUtil.memFree(iBuf); MemoryUtil.memFree(iMem)
            MemoryUtil.memFree(ssBuf); MemoryUtil.memFree(ssMem)
        }
        mesh.uploaded = true
        meshes += mesh
    }

    fun drawFrame(world: World, camera: Camera) {
        // Skip rendering when minimized
        val sz = window.frameBufferSize()
        if (sz[0] == 0 || sz[1] == 0) return
        // Advance shader-side time
        elapsedSeconds += ru.kseonyt.foxlive.core.Time.delta

        stackPush().use { st ->
            val fence = st.longs(inFlightFences[currentFrame])
            vkWaitForFences(ctx.device, fence, true, Long.MAX_VALUE)

            val pImageIndex = st.mallocInt(1)
            val acquireResult = vkAcquireNextImageKHR(ctx.device, swapchain.swapchain, Long.MAX_VALUE,
                imageAvailable[currentFrame], VK_NULL_HANDLE, pImageIndex)
            // Only OUT_OF_DATE forces an immediate recreate. SUBOPTIMAL or window-resize
            // are deferred until AFTER present so the imageAvailable semaphore we just
            // signalled is properly waited on by the submit (otherwise it'd be left
            // signalled — undefined to call vkAcquireNextImageKHR with it again).
            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain()
                return@use
            } else if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
                error("vkAcquireNextImageKHR failed: $acquireResult")
            }
            vkResetFences(ctx.device, fence)
            val imageIndex = pImageIndex.get(0)

            val cmd = commandBuffers[currentFrame]
            vkResetCommandBuffer(cmd, 0)
            recordCommandBuffer(cmd, imageIndex, world, camera)

            val waitStages = st.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            val submit = VkSubmitInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(st.longs(imageAvailable[currentFrame]))
                .pWaitDstStageMask(waitStages)
                .pCommandBuffers(st.pointers(cmd.address()))
                .pSignalSemaphores(st.longs(renderFinished[currentFrame]))
            Vk.check(vkQueueSubmit(ctx.graphicsQueue, submit, inFlightFences[currentFrame]))

            val present = VkPresentInfoKHR.calloc(st)
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(st.longs(renderFinished[currentFrame]))
                .swapchainCount(1)
                .pSwapchains(st.longs(swapchain.swapchain))
                .pImageIndices(pImageIndex)
            val presentResult = vkQueuePresentKHR(ctx.presentQueue, present)
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || window.resized) {
                recreateSwapchain()
            } else if (presentResult != VK_SUCCESS) {
                error("vkQueuePresentKHR failed: $presentResult")
            }
            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT
        }
    }

    private fun recreateSwapchain() {
        swapchain.recreate()
        pipeline.destroy()
        pipeline.create()
        ui.recreateForNewRenderPass()
        postfx.destroy()
        postfx.create()
        // Re-allocate descriptor sets are tied to the layout — pipeline.create recreates the layout
        // so existing material descriptor sets reference a destroyed layout. Reset them.
        for (mat in managedMaterials) { mat.initialized = false; mat.descriptorSet = 0L }
        if (descriptorPool != 0L) vkResetDescriptorPool(ctx.device, descriptorPool, 0)
        ensureMaterialReady(defaultMaterial)
        viewport.width = swapchain.extentWidth
        viewport.height = swapchain.extentHeight
        // Notify scenes that hold their own pipelines so they can rebuild them
        onSwapchainRecreated?.invoke()
    }

    private fun recordCommandBuffer(cmd: VkCommandBuffer, imageIndex: Int, world: World, camera: Camera) =
        stackPush().use { st ->
            val begin = VkCommandBufferBeginInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            Vk.check(vkBeginCommandBuffer(cmd, begin))

            // ─── PASS 1: scene → sceneTex ─────────────────────────────────────
            val msaa = swapchain.msaaSamples != VK_SAMPLE_COUNT_1_BIT
            val sceneClearCount = if (msaa) 3 else 2
            val sceneClears = VkClearValue.calloc(sceneClearCount, st)
            sceneClears.get(0).color { it.float32(0, clearR).float32(1, clearG).float32(2, clearB).float32(3, 1f) }
            sceneClears.get(1).depthStencil { it.depth(1f).stencil(0) }
            if (msaa) sceneClears.get(2).color { it.float32(0, clearR).float32(1, clearG).float32(2, clearB).float32(3, 1f) }

            val sceneRpBegin = VkRenderPassBeginInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(swapchain.scenePass)
                .framebuffer(swapchain.sceneFramebuffer)
                .pClearValues(sceneClears)
            sceneRpBegin.renderArea().offset().set(0, 0)
            sceneRpBegin.renderArea().extent().set(swapchain.extentWidth, swapchain.extentHeight)

            vkCmdBeginRenderPass(cmd, sceneRpBegin, VK_SUBPASS_CONTENTS_INLINE)
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline)

            val vp = VkViewport.calloc(1, st)
                .x(0f).y(0f)
                .width(swapchain.extentWidth.toFloat()).height(swapchain.extentHeight.toFloat())
                .minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(cmd, 0, vp)
            val sc = VkRect2D.calloc(1, st)
            sc.offset().set(0, 0)
            sc.extent().set(swapchain.extentWidth, swapchain.extentHeight)
            vkCmdSetScissor(cmd, 0, sc)

            // Push viewProj + light direction + camera-pos / time once per frame
            val viewProj = camera.viewProjection(viewport.aspect)
            pushBytes.clear()
            viewProj.get(0, pushBytes)
            pushBytes.position(64)
            pushBytes.putFloat(camera.lightDirection.x)
                .putFloat(camera.lightDirection.y)
                .putFloat(camera.lightDirection.z)
                .putFloat(0f)
            pushBytes.position(80)
            pushBytes.putFloat(camera.position.x)
                .putFloat(camera.position.y)
                .putFloat(camera.position.z)
                .putFloat(elapsedSeconds)
            pushBytes.position(0).limit(VulkanPipeline.PUSH_CONSTANT_SIZE)
            vkCmdPushConstants(cmd, pipeline.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushBytes)

            // Group entities by (mesh, material) → list of entity ids
            val groups = HashMap<MeshMatKey, ArrayList<Int>>()
            for ((id, mr) in world.all<MeshRenderer>()) {
                if (!world.has<Transform>(id)) continue
                val mat = mr.material ?: defaultMaterial
                val key = MeshMatKey(mr.mesh, mat)
                groups.getOrPut(key) { ArrayList() }.add(id)
            }

            // Write instance data into the per-frame instance buffer; track group offsets
            val slot = instanceSlots[currentFrame]
            val mapped = slot.mapped
            mapped.clear()
            var instanceOffset = 0
            data class GroupDraw(val mesh: Mesh, val mat: Material, val firstInstance: Int, val instanceCount: Int)
            val draws = ArrayList<GroupDraw>(groups.size)

            for ((key, ids) in groups) {
                if (instanceOffset + ids.size > MAX_INSTANCES_PER_FRAME) {
                    System.err.println("[FoxLive] Instance buffer overflow — increase MAX_INSTANCES_PER_FRAME")
                    break
                }
                val first = instanceOffset
                for (id in ids) {
                    val tr = world.get<Transform>(id)!!
                    val mr = world.get<MeshRenderer>(id)!!
                    val byteOff = instanceOffset * VulkanPipeline.INSTANCE_STRIDE
                    val model = tr.model()
                    model.get(byteOff, mapped)
                    mapped.position(byteOff + 64)
                    mapped.putFloat(mr.color.x).putFloat(mr.color.y).putFloat(mr.color.z).putFloat(1f)
                    instanceOffset++
                }
                draws += GroupDraw(key.mesh, key.material, first, ids.size)
            }

            // Bind instance buffer once (binding 1)
            val pInst = st.longs(slot.handle)
            val pInstOff = st.longs(0L)
            vkCmdBindVertexBuffers(cmd, 1, pInst, pInstOff)

            // Per-group draw
            val pVert = st.longs(0L)
            val pVertOff = st.longs(0L)
            val pSet = st.longs(0L)
            for (d in draws) {
                if (!d.mesh.uploaded) uploadMesh(d.mesh)
                ensureMaterialReady(d.mat)

                pSet.put(0, d.mat.descriptorSet)
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.pipelineLayout, 0, pSet, null)

                pVert.put(0, d.mesh.gpuHandle)
                vkCmdBindVertexBuffers(cmd, 0, pVert, pVertOff)
                vkCmdBindIndexBuffer(cmd, d.mesh.indexHandle, 0, VK_INDEX_TYPE_UINT32)

                vkCmdDrawIndexed(cmd, d.mesh.indexCount, d.instanceCount, 0, 0, d.firstInstance)
            }

            // Scene-specific custom rendering (water, etc.) — still inside scenePass
            customRender?.invoke(cmd, currentFrame)

            vkCmdEndRenderPass(cmd)

            // ─── PASS 2: postfx fullscreen + UI overlay → swapchain ──────────
            val compClears = VkClearValue.calloc(1, st)
            compClears.get(0).color { it.float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f) }
            val compRpBegin = VkRenderPassBeginInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                .renderPass(swapchain.compositePass)
                .framebuffer(swapchain.compositeFramebuffers[imageIndex])
                .pClearValues(compClears)
            compRpBegin.renderArea().offset().set(0, 0)
            compRpBegin.renderArea().extent().set(swapchain.extentWidth, swapchain.extentHeight)
            vkCmdBeginRenderPass(cmd, compRpBegin, VK_SUBPASS_CONTENTS_INLINE)

            // Re-set viewport/scissor for composite pass (dynamic state)
            val cVp = VkViewport.calloc(1, st)
                .x(0f).y(0f)
                .width(swapchain.extentWidth.toFloat()).height(swapchain.extentHeight.toFloat())
                .minDepth(0f).maxDepth(1f)
            vkCmdSetViewport(cmd, 0, cVp)
            val cSc = VkRect2D.calloc(1, st)
            cSc.offset().set(0, 0); cSc.extent().set(swapchain.extentWidth, swapchain.extentHeight)
            vkCmdSetScissor(cmd, 0, cSc)

            // Postfx fullscreen-triangle pass — samples sceneTex via descriptor set
            postfx.draw(cmd, postFxParams, elapsedSeconds)

            // UI overlay (sharp, single-sample) draws on top of postfx output
            ui.draw(cmd, currentFrame)

            vkCmdEndRenderPass(cmd)
            Vk.check(vkEndCommandBuffer(cmd))
        }

    fun waitIdle() {
        if (ctx.isReady()) vkDeviceWaitIdle(ctx.device)
    }

    fun destroy() {
        waitIdle()
        if (ctx.isReady()) {
            // Free per-frame instance buffers
            for (slot in instanceSlots) {
                vkUnmapMemory(ctx.device, slot.memory)
                vkDestroyBuffer(ctx.device, slot.handle, null)
                vkFreeMemory(ctx.device, slot.memory, null)
            }
            instanceSlots.clear()

            // Free uploaded meshes
            for (m in meshes) destroyMeshGpu(m)
            meshes.clear()

            // Free default textures (materials' descriptor sets are freed with the pool)
            if (::defaultDiffuse.isInitialized) defaultDiffuse.destroy(ctx)
            if (::defaultNormal.isInitialized) defaultNormal.destroy(ctx)

            for (i in 0 until MAX_FRAMES_IN_FLIGHT) {
                if (imageAvailable[i] != 0L) vkDestroySemaphore(ctx.device, imageAvailable[i], null)
                if (renderFinished[i] != 0L) vkDestroySemaphore(ctx.device, renderFinished[i], null)
                if (inFlightFences[i] != 0L) vkDestroyFence(ctx.device, inFlightFences[i], null)
            }

            if (descriptorPool != 0L) vkDestroyDescriptorPool(ctx.device, descriptorPool, null)
            postfx.destroy()
            ui.destroy()
            pipeline.destroy()
            swapchain.cleanup()
        }
        ctx.destroy()
        MemoryUtil.memFree(pushBytes)
    }

    private fun destroyMeshGpu(mesh: Mesh) {
        if (!mesh.uploaded) return
        if (mesh.gpuHandle != 0L) vkDestroyBuffer(ctx.device, mesh.gpuHandle, null)
        if (mesh.vertexMemory != 0L) vkFreeMemory(ctx.device, mesh.vertexMemory, null)
        if (mesh.indexHandle != 0L) vkDestroyBuffer(ctx.device, mesh.indexHandle, null)
        if (mesh.indexMemory != 0L) vkFreeMemory(ctx.device, mesh.indexMemory, null)
        mesh.uploaded = false
        mesh.gpuHandle = 0L; mesh.vertexMemory = 0L
        mesh.indexHandle = 0L; mesh.indexMemory = 0L
    }

    fun destroyMesh(mesh: Mesh) {
        meshes -= mesh
        destroyMeshGpu(mesh)
    }

    private data class MeshMatKey(val mesh: Mesh, val material: Material)
}

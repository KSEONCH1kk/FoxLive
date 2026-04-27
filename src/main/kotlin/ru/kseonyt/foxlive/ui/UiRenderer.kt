package ru.kseonyt.foxlive.ui

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import ru.kseonyt.foxlive.render.ShaderCompiler
import ru.kseonyt.foxlive.render.Texture
import ru.kseonyt.foxlive.render.Vk
import ru.kseonyt.foxlive.render.VulkanContext
import ru.kseonyt.foxlive.render.VulkanImage
import ru.kseonyt.foxlive.render.VulkanSwapchain
import java.nio.ByteBuffer

private const val UI_VERTEX_STRIDE = UiFrame.FLOATS_PER_VERTEX * 4
private const val UI_VERTEX_BYTES = UiFrame.MAX_VERTS * UI_VERTEX_STRIDE
private const val UI_INDEX_BYTES = UiFrame.MAX_INDICES * 4
private const val UI_MAX_TEXTURES = 32

class UiBufferSlot(
    val vertexBuf: Long, val vertexMem: Long, val vertexMapped: ByteBuffer,
    val indexBuf: Long, val indexMem: Long, val indexMapped: ByteBuffer,
)

class UiRenderer(val ctx: VulkanContext, val swapchain: VulkanSwapchain, val framesInFlight: Int) {
    private var vertModule: Long = 0L
    private var fragModule: Long = 0L
    private var pipeline: Long = 0L
    private var pipelineLayout: Long = 0L
    private var descriptorSetLayout: Long = 0L
    private var descriptorPool: Long = 0L

    private lateinit var fontTexture: Texture
    var fontDescriptorSet: Long = 0L
        private set
    lateinit var font: FontAtlas
        private set

    private val slots = ArrayList<UiBufferSlot>(framesInFlight)
    private val pushBytes = MemoryUtil.memAlloc(8)

    fun init() {
        font = FontAtlas.build()
        fontTexture = VulkanImage.createRGBA(ctx, font.width, font.height, font.pixels)
        font.destroy()

        createDescriptorSetLayout()
        createDescriptorPool()
        fontDescriptorSet = registerTexture(fontTexture.view, fontTexture.sampler)
        createPipeline()
        createPerFrameBuffers()
    }

    private fun createDescriptorSetLayout() = stackPush().use { st ->
        val bindings = VkDescriptorSetLayoutBinding.calloc(1, st)
        bindings.get(0).binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
        val info = VkDescriptorSetLayoutCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            .pBindings(bindings)
        val p = st.mallocLong(1)
        Vk.check(vkCreateDescriptorSetLayout(ctx.device, info, null, p))
        descriptorSetLayout = p.get(0)
    }

    private fun createDescriptorPool() = stackPush().use { st ->
        val sizes = VkDescriptorPoolSize.calloc(1, st)
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(UI_MAX_TEXTURES)
        val info = VkDescriptorPoolCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(sizes).maxSets(UI_MAX_TEXTURES)
        val p = st.mallocLong(1)
        Vk.check(vkCreateDescriptorPool(ctx.device, info, null, p))
        descriptorPool = p.get(0)
    }

    /** Allocate a descriptor set for an external texture so DebugUi can switch to it. */
    fun registerTexture(view: Long, sampler: Long): Long = stackPush().use { st ->
        val pSetLayouts = st.longs(descriptorSetLayout)
        val alloc = VkDescriptorSetAllocateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            .descriptorPool(descriptorPool)
            .pSetLayouts(pSetLayouts)
        val p = st.mallocLong(1)
        Vk.check(vkAllocateDescriptorSets(ctx.device, alloc, p))
        val set = p.get(0)
        val info = VkDescriptorImageInfo.calloc(1, st)
            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .imageView(view).sampler(sampler)
        val writes = VkWriteDescriptorSet.calloc(1, st)
        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(set).dstBinding(0).dstArrayElement(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).pImageInfo(info)
        vkUpdateDescriptorSets(ctx.device, writes, null)
        set
    }

    private fun createPipeline() = stackPush().use { st ->
        val vertSrc = ShaderCompiler.loadResource("shaders/ui.vert")
        val fragSrc = ShaderCompiler.loadResource("shaders/ui.frag")
        val vertSpv = ShaderCompiler.compile(vertSrc, shaderc_glsl_vertex_shader, "ui.vert")
        val fragSpv = ShaderCompiler.compile(fragSrc, shaderc_glsl_fragment_shader, "ui.frag")
        try {
            vertModule = ShaderCompiler.createShaderModule(ctx.device, vertSpv)
            fragModule = ShaderCompiler.createShaderModule(ctx.device, fragSpv)
        } finally {
            MemoryUtil.memFree(vertSpv); MemoryUtil.memFree(fragSpv)
        }

        val stages = VkPipelineShaderStageCreateInfo.calloc(2, st)
        stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(st.UTF8("main"))
        stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(st.UTF8("main"))

        val bindings = VkVertexInputBindingDescription.calloc(1, st)
        bindings.get(0).binding(0).stride(UI_VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val attrs = VkVertexInputAttributeDescription.calloc(3, st)
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(8)
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16)

        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            .pVertexBindingDescriptions(bindings)
            .pVertexAttributeDescriptions(attrs)

        val ia = VkPipelineInputAssemblyStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false)

        val viewport = VkViewport.calloc(1, st)
            .x(0f).y(0f)
            .width(swapchain.extentWidth.toFloat()).height(swapchain.extentHeight.toFloat())
            .minDepth(0f).maxDepth(1f)
        val scissor = VkRect2D.calloc(1, st)
        scissor.offset().set(0, 0)
        scissor.extent().set(swapchain.extentWidth, swapchain.extentHeight)
        val vp = VkPipelineViewportStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
            .pViewports(viewport).pScissors(scissor)

        val rast = VkPipelineRasterizationStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
            .depthClampEnable(false).rasterizerDiscardEnable(false)
            .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f)
            .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            .depthBiasEnable(false)

        val ms = VkPipelineMultisampleStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
            .sampleShadingEnable(false)
            // UI is drawn into the (single-sample) composite pass after postfx so text stays sharp
            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)

        val ds = VkPipelineDepthStencilStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
            .depthTestEnable(false).depthWriteEnable(false)
            .stencilTestEnable(false)

        val cba = VkPipelineColorBlendAttachmentState.calloc(1, st)
            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or
                VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            .blendEnable(true)
            .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
            .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
            .colorBlendOp(VK_BLEND_OP_ADD)
            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
            .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
            .alphaBlendOp(VK_BLEND_OP_ADD)
        val cb = VkPipelineColorBlendStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            .logicOpEnable(false).pAttachments(cba)

        val dyn = VkPipelineDynamicStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
            .pDynamicStates(st.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val pushRange = VkPushConstantRange.calloc(1, st)
            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(8)

        val pSetLayouts = st.longs(descriptorSetLayout)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pSetLayouts(pSetLayouts).pPushConstantRanges(pushRange)
        val pLayout = st.mallocLong(1)
        Vk.check(vkCreatePipelineLayout(ctx.device, layoutInfo, null, pLayout))
        pipelineLayout = pLayout.get(0)

        val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, st)
            .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            .pStages(stages)
            .pVertexInputState(vertexInput)
            .pInputAssemblyState(ia).pViewportState(vp)
            .pRasterizationState(rast).pMultisampleState(ms)
            .pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dyn)
            .layout(pipelineLayout).renderPass(swapchain.compositePass).subpass(0)
        val pPipe = st.mallocLong(1)
        Vk.check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, pipelineInfo, null, pPipe))
        pipeline = pPipe.get(0)
    }

    private fun createPerFrameBuffers() {
        for (i in 0 until framesInFlight) {
            val pVB = MemoryUtil.memAllocLong(1); val pVM = MemoryUtil.memAllocLong(1)
            val pIB = MemoryUtil.memAllocLong(1); val pIM = MemoryUtil.memAllocLong(1)
            try {
                ctx.createBuffer(UI_VERTEX_BYTES.toLong(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pVB, pVM)
                ctx.createBuffer(UI_INDEX_BYTES.toLong(), VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pIB, pIM)
                stackPush().use { st ->
                    val pData = st.mallocPointer(1)
                    vkMapMemory(ctx.device, pVM.get(0), 0, UI_VERTEX_BYTES.toLong(), 0, pData)
                    val vMapped = MemoryUtil.memByteBuffer(pData.get(0), UI_VERTEX_BYTES)
                    vkMapMemory(ctx.device, pIM.get(0), 0, UI_INDEX_BYTES.toLong(), 0, pData)
                    val iMapped = MemoryUtil.memByteBuffer(pData.get(0), UI_INDEX_BYTES)
                    slots += UiBufferSlot(pVB.get(0), pVM.get(0), vMapped,
                                          pIB.get(0), pIM.get(0), iMapped)
                }
            } finally {
                MemoryUtil.memFree(pVB); MemoryUtil.memFree(pVM)
                MemoryUtil.memFree(pIB); MemoryUtil.memFree(pIM)
            }
        }
    }

    fun recreateForNewRenderPass() {
        if (pipeline != 0L) vkDestroyPipeline(ctx.device, pipeline, null)
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (vertModule != 0L) vkDestroyShaderModule(ctx.device, vertModule, null)
        if (fragModule != 0L) vkDestroyShaderModule(ctx.device, fragModule, null)
        pipeline = 0L; pipelineLayout = 0L; vertModule = 0L; fragModule = 0L
        createPipeline()
    }

    fun draw(cmd: VkCommandBuffer, frameIndex: Int) {
        val ui = DebugUi.frame
        if (ui.indexCount == 0 || ui.drawCalls.isEmpty()) return

        val slot = slots[frameIndex]
        slot.vertexMapped.position(0)
        slot.vertexMapped.asFloatBuffer().put(ui.vertices, 0, ui.vertCount * UiFrame.FLOATS_PER_VERTEX)
        slot.indexMapped.position(0)
        slot.indexMapped.asIntBuffer().put(ui.indices, 0, ui.indexCount)

        stackPush().use { st ->
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
            val pBuf = st.longs(slot.vertexBuf)
            val pOff = st.longs(0L)
            vkCmdBindVertexBuffers(cmd, 0, pBuf, pOff)
            vkCmdBindIndexBuffer(cmd, slot.indexBuf, 0, VK_INDEX_TYPE_UINT32)
            pushBytes.clear()
            pushBytes.putFloat(swapchain.extentWidth.toFloat())
            pushBytes.putFloat(swapchain.extentHeight.toFloat())
            pushBytes.flip()
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pushBytes)

            val pSet = st.mallocLong(1)
            var lastSet = 0L
            for (dc in ui.drawCalls) {
                if (dc.indexCount == 0) continue
                if (dc.descriptorSet != lastSet) {
                    pSet.put(0, dc.descriptorSet)
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout, 0, pSet, null)
                    lastSet = dc.descriptorSet
                }
                vkCmdDrawIndexed(cmd, dc.indexCount, 1, dc.firstIndex, 0, 0)
            }
        }
    }

    fun destroy() {
        if (!ctx.isReady()) return
        for (slot in slots) {
            vkUnmapMemory(ctx.device, slot.vertexMem)
            vkDestroyBuffer(ctx.device, slot.vertexBuf, null)
            vkFreeMemory(ctx.device, slot.vertexMem, null)
            vkUnmapMemory(ctx.device, slot.indexMem)
            vkDestroyBuffer(ctx.device, slot.indexBuf, null)
            vkFreeMemory(ctx.device, slot.indexMem, null)
        }
        slots.clear()
        if (pipeline != 0L) vkDestroyPipeline(ctx.device, pipeline, null)
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (descriptorPool != 0L) vkDestroyDescriptorPool(ctx.device, descriptorPool, null)
        if (descriptorSetLayout != 0L) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null)
        if (vertModule != 0L) vkDestroyShaderModule(ctx.device, vertModule, null)
        if (fragModule != 0L) vkDestroyShaderModule(ctx.device, fragModule, null)
        if (::fontTexture.isInitialized) fontTexture.destroy(ctx)
        MemoryUtil.memFree(pushBytes)
    }
}

package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer

/**
 * Customisable PostFX shader parameters. All values are compactly packed into
 * exactly 96 bytes of push-constant space (6 × vec4).
 */
class PostFxParams {
    /** Strength of horizontal-band sync wobble (0..1). */
    var vhsWobble: Float = 0f
    /** Chromatic aberration intensity (0..1). */
    var chroma: Float = 0f
    /** Scanline strength (0..1). */
    var scanlines: Float = 0f
    /** Per-pixel grain noise (0..1). */
    var noise: Float = 0f

    /** Bloom intensity (0..2 useful range). */
    var bloomStrength: Float = 0f
    /** Brightness threshold above which pixels contribute to bloom. */
    var bloomThreshold: Float = 0.85f
    /** Bloom blur radius in pixels. */
    var bloomRadius: Float = 4f

    /** Box-blur mix amount (0 = no blur, 1 = full blur). */
    var blurStrength: Float = 0f
    /** Box-blur kernel radius in pixels. */
    var blurRadius: Float = 4f

    /** Vignette intensity (0 = none). */
    var vignette: Float = 0f

    /** Per-channel multiply tint applied after scene sampling. */
    var tintR: Float = 1f
    var tintG: Float = 1f
    var tintB: Float = 1f
    /** Final exposure multiplier. */
    var exposure: Float = 1f

    fun applyOff() {
        vhsWobble = 0f; chroma = 0f; scanlines = 0f; noise = 0f
        bloomStrength = 0f; blurStrength = 0f; vignette = 0f
        tintR = 1f; tintG = 1f; tintB = 1f; exposure = 1f
    }
    fun applyVHS() {
        vhsWobble = 0.7f; chroma = 0.6f; scanlines = 0.45f; noise = 0.18f
        bloomStrength = 0.25f; bloomThreshold = 0.7f; bloomRadius = 3f
        blurStrength = 0f; vignette = 0.45f
        tintR = 1.05f; tintG = 1.0f; tintB = 0.92f; exposure = 0.95f
    }
    fun applyDreamy() {
        vhsWobble = 0f; chroma = 0.15f; scanlines = 0f; noise = 0.04f
        bloomStrength = 0.85f; bloomThreshold = 0.55f; bloomRadius = 6f
        blurStrength = 0.25f; blurRadius = 3f; vignette = 0.25f
        tintR = 1.05f; tintG = 1.05f; tintB = 1.10f; exposure = 1.05f
    }
    fun applyCRT() {
        vhsWobble = 0.15f; chroma = 0.35f; scanlines = 0.7f; noise = 0.05f
        bloomStrength = 0.4f; bloomThreshold = 0.6f; bloomRadius = 3f
        blurStrength = 0f; vignette = 0.55f
        tintR = 1.0f; tintG = 1.0f; tintB = 1.0f; exposure = 1f
    }
}

/**
 * Fullscreen-quad postprocess pass. Samples the scene texture (resolved by the scene render
 * pass) and writes the composited image to the swapchain via the composite render pass.
 */
class PostfxPipeline(val ctx: VulkanContext, val swapchain: VulkanSwapchain) {
    var pipelineLayout: Long = 0L; private set
    var pipeline: Long = 0L; private set
    var descriptorSetLayout: Long = 0L; private set
    var descriptorPool: Long = 0L; private set
    var descriptorSet: Long = 0L; private set
    private var vertModule: Long = 0L
    private var fragModule: Long = 0L

    /** Allocated in [create] and freed in [destroy] so resize cycles don't leak/UAF. */
    private var pushBytes: ByteBuffer? = null

    companion object { const val PUSH_CONSTANT_SIZE = 96 }

    fun create() = stackPush().use { st ->
        if (pushBytes == null) pushBytes = MemoryUtil.memAlloc(PUSH_CONSTANT_SIZE)
        createDescriptorSetLayout()
        createDescriptorPool()
        allocateAndUpdateDescriptorSet()

        val vertSrc = ShaderCompiler.loadResource("shaders/postfx.vert")
        val fragSrc = ShaderCompiler.loadResource("shaders/postfx.frag")
        val vertSpv = ShaderCompiler.compile(vertSrc, shaderc_glsl_vertex_shader, "postfx.vert")
        val fragSpv = ShaderCompiler.compile(fragSrc, shaderc_glsl_fragment_shader, "postfx.frag")
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

        // No vertex inputs — gl_VertexIndex generates the fullscreen triangle
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)

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
            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT) // composite pass is single-sample

        val ds = VkPipelineDepthStencilStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
            .depthTestEnable(false).depthWriteEnable(false).stencilTestEnable(false)

        val cba = VkPipelineColorBlendAttachmentState.calloc(1, st)
            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or
                VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            .blendEnable(false)
        val cb = VkPipelineColorBlendStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
            .logicOpEnable(false).pAttachments(cba)

        val dyn = VkPipelineDynamicStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
            .pDynamicStates(st.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val pushRange = VkPushConstantRange.calloc(1, st)
            .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_CONSTANT_SIZE)

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
            .pVertexInputState(vertexInput).pInputAssemblyState(ia).pViewportState(vp)
            .pRasterizationState(rast).pMultisampleState(ms)
            .pDepthStencilState(ds).pColorBlendState(cb).pDynamicState(dyn)
            .layout(pipelineLayout).renderPass(swapchain.compositePass).subpass(0)
        val pPipe = st.mallocLong(1)
        Vk.check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, pipelineInfo, null, pPipe))
        pipeline = pPipe.get(0)
    }

    private fun createDescriptorSetLayout() = stackPush().use { st ->
        val bindings = VkDescriptorSetLayoutBinding.calloc(1, st)
        bindings.get(0).binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
        val info = VkDescriptorSetLayoutCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings)
        val p = st.mallocLong(1)
        Vk.check(vkCreateDescriptorSetLayout(ctx.device, info, null, p))
        descriptorSetLayout = p.get(0)
    }

    private fun createDescriptorPool() = stackPush().use { st ->
        val sizes = VkDescriptorPoolSize.calloc(1, st)
        sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
        val info = VkDescriptorPoolCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .pPoolSizes(sizes).maxSets(1)
        val p = st.mallocLong(1)
        Vk.check(vkCreateDescriptorPool(ctx.device, info, null, p))
        descriptorPool = p.get(0)
    }

    fun allocateAndUpdateDescriptorSet() = stackPush().use { st ->
        if (descriptorSet != 0L) {
            // re-use slot, just re-write
        } else {
            val pSetLayouts = st.longs(descriptorSetLayout)
            val alloc = VkDescriptorSetAllocateInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(pSetLayouts)
            val p = st.mallocLong(1)
            Vk.check(vkAllocateDescriptorSets(ctx.device, alloc, p))
            descriptorSet = p.get(0)
        }
        val info = VkDescriptorImageInfo.calloc(1, st)
            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            .imageView(swapchain.sceneTexView)
            .sampler(swapchain.sceneTexSampler)
        val writes = VkWriteDescriptorSet.calloc(1, st)
        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(descriptorSet).dstBinding(0).dstArrayElement(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).pImageInfo(info)
        vkUpdateDescriptorSets(ctx.device, writes, null)
    }

    /** Record one fullscreen draw with the given parameters. */
    fun draw(cmd: VkCommandBuffer, params: PostFxParams, time: Float) = stackPush().use { st ->
        val pushBytes = this.pushBytes ?: return@use
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
        val pSet = st.longs(descriptorSet)
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelineLayout, 0, pSet, null)

        // Pack push constants
        pushBytes.clear()
        // viewportTime
        pushBytes.putFloat(swapchain.extentWidth.toFloat())
            .putFloat(swapchain.extentHeight.toFloat()).putFloat(time).putFloat(0f)
        // bloom
        pushBytes.putFloat(params.bloomStrength).putFloat(params.bloomThreshold)
            .putFloat(params.bloomRadius).putFloat(0f)
        // blur
        pushBytes.putFloat(params.blurStrength).putFloat(params.blurRadius)
            .putFloat(0f).putFloat(0f)
        // vhs
        pushBytes.putFloat(params.vhsWobble).putFloat(params.chroma)
            .putFloat(params.scanlines).putFloat(params.noise)
        // vignette
        pushBytes.putFloat(params.vignette).putFloat(0f).putFloat(0f).putFloat(0f)
        // colorGrade
        pushBytes.putFloat(params.tintR).putFloat(params.tintG)
            .putFloat(params.tintB).putFloat(params.exposure)
        pushBytes.position(0).limit(PUSH_CONSTANT_SIZE)
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushBytes)

        vkCmdDraw(cmd, 3, 1, 0, 0) // 3 verts → 1 fullscreen triangle
    }

    fun destroy() {
        if (!ctx.isReady()) return
        if (pipeline != 0L) vkDestroyPipeline(ctx.device, pipeline, null)
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (descriptorPool != 0L) vkDestroyDescriptorPool(ctx.device, descriptorPool, null)
        if (descriptorSetLayout != 0L) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null)
        if (vertModule != 0L) vkDestroyShaderModule(ctx.device, vertModule, null)
        if (fragModule != 0L) vkDestroyShaderModule(ctx.device, fragModule, null)
        pipeline = 0L; pipelineLayout = 0L; descriptorPool = 0L
        descriptorSetLayout = 0L; descriptorSet = 0L
        vertModule = 0L; fragModule = 0L
        pushBytes?.let { MemoryUtil.memFree(it) }
        pushBytes = null
    }
}

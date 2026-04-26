package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

/**
 * Dedicated Vulkan pipeline for the procedural-water shader. Uses the same vertex
 * input layout as the main pipeline (so we can reuse Mesh upload code) and the same
 * 96-byte push constant struct (so the renderer pushes once and both pipelines see it).
 */
class WaterPipeline(val ctx: VulkanContext, val swapchain: VulkanSwapchain) {
    var pipelineLayout: Long = 0L
        private set
    var pipeline: Long = 0L
        private set
    private var vertModule: Long = 0L
    private var fragModule: Long = 0L

    fun create() = stackPush().use { st ->
        val vertSrc = ShaderCompiler.loadResource("shaders/water.vert")
        val fragSrc = ShaderCompiler.loadResource("shaders/water.frag")
        val vertSpv = ShaderCompiler.compile(vertSrc, shaderc_glsl_vertex_shader, "water.vert")
        val fragSpv = ShaderCompiler.compile(fragSrc, shaderc_glsl_fragment_shader, "water.frag")
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

        // Single vertex binding — water has no per-instance data
        val bindings = VkVertexInputBindingDescription.calloc(1, st)
        bindings.get(0).binding(0).stride(Mesh.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

        val attrs = VkVertexInputAttributeDescription.calloc(5, st)
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12)
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32_SFLOAT).offset(24)
        attrs.get(3).binding(0).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(36)
        attrs.get(4).binding(0).location(4).format(VK_FORMAT_R32G32B32_SFLOAT).offset(44)

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
            .cullMode(VK_CULL_MODE_NONE)        // water plane visible from above and below
            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            .depthBiasEnable(false)

        val ms = VkPipelineMultisampleStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
            .sampleShadingEnable(false)
            .rasterizationSamples(swapchain.msaaSamples)

        val ds = VkPipelineDepthStencilStateCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
            .depthTestEnable(true).depthWriteEnable(true)
            .depthCompareOp(VK_COMPARE_OP_LESS)
            .depthBoundsTestEnable(false).stencilTestEnable(false)

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
            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT)
            .offset(0).size(VulkanPipeline.PUSH_CONSTANT_SIZE)

        // No descriptor sets needed — water is fully procedural
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pPushConstantRanges(pushRange)
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
            .layout(pipelineLayout).renderPass(swapchain.renderPass).subpass(0)
        val pPipe = st.mallocLong(1)
        Vk.check(vkCreateGraphicsPipelines(ctx.device, VK_NULL_HANDLE, pipelineInfo, null, pPipe))
        pipeline = pPipe.get(0)
    }

    fun destroy() {
        if (!ctx.isReady()) return
        if (pipeline != 0L) vkDestroyPipeline(ctx.device, pipeline, null)
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (vertModule != 0L) vkDestroyShaderModule(ctx.device, vertModule, null)
        if (fragModule != 0L) vkDestroyShaderModule(ctx.device, fragModule, null)
        pipeline = 0L; pipelineLayout = 0L; vertModule = 0L; fragModule = 0L
    }
}

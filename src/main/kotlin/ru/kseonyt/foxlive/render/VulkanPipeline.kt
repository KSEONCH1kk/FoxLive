package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader
import org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*

/**
 * Push constant: mat4 viewProj (64 bytes)
 * Vertex binding 0: per-vertex data (56 bytes / vertex)
 * Vertex binding 1: per-instance data — mat4 model + vec4 tint (80 bytes / instance)
 * Descriptor set 0: combined image sampler ×2 (diffuse, normal)
 */
class VulkanPipeline(val ctx: VulkanContext, val swapchain: VulkanSwapchain) {
    var pipelineLayout: Long = 0L
        private set
    var pipeline: Long = 0L
        private set
    var descriptorSetLayout: Long = 0L
        private set
    private var vertModule: Long = 0L
    private var fragModule: Long = 0L

    companion object {
        // mat4 viewProj (64) + vec4 lightDir (16) + vec4 camPosTime (16) = 96 bytes
        // Stays within the 128-byte Vulkan minimum-guaranteed push constant size.
        const val PUSH_CONSTANT_SIZE = 96
        const val INSTANCE_STRIDE = 80
    }

    fun create() = stackPush().use { st ->
        createDescriptorSetLayout()

        val vertSrc = ShaderCompiler.loadResource("shaders/basic.vert")
        val fragSrc = ShaderCompiler.loadResource("shaders/basic.frag")
        val vertSpv = ShaderCompiler.compile(vertSrc, shaderc_glsl_vertex_shader, "basic.vert")
        val fragSpv = ShaderCompiler.compile(fragSrc, shaderc_glsl_fragment_shader, "basic.frag")
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

        // Vertex bindings: 0 = vertex (PER_VERTEX), 1 = instance (PER_INSTANCE)
        val bindings = VkVertexInputBindingDescription.calloc(2, st)
        bindings.get(0).binding(0).stride(Mesh.STRIDE_BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        bindings.get(1).binding(1).stride(INSTANCE_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE)

        val attrs = VkVertexInputAttributeDescription.calloc(10, st)
        // Per-vertex
        attrs.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0)   // pos
        attrs.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12)  // normal
        attrs.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32_SFLOAT).offset(24)  // color
        attrs.get(3).binding(0).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(36)     // uv
        attrs.get(4).binding(0).location(4).format(VK_FORMAT_R32G32B32_SFLOAT).offset(44)  // tangent
        // Per-instance: mat4 model (4 vec4s) + vec4 tint
        attrs.get(5).binding(1).location(5).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(0)
        attrs.get(6).binding(1).location(6).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16)
        attrs.get(7).binding(1).location(7).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(32)
        attrs.get(8).binding(1).location(8).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(48)
        attrs.get(9).binding(1).location(9).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(64)

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
            .cullMode(VK_CULL_MODE_BACK_BIT).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
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
            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(PUSH_CONSTANT_SIZE)

        val pSetLayouts = st.longs(descriptorSetLayout)
        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pSetLayouts(pSetLayouts)
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

    private fun createDescriptorSetLayout() = stackPush().use { st ->
        val bindings = VkDescriptorSetLayoutBinding.calloc(2, st)
        bindings.get(0).binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
        bindings.get(1).binding(1)
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

    fun destroy() {
        if (!ctx.isReady()) return
        if (pipeline != 0L) vkDestroyPipeline(ctx.device, pipeline, null)
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(ctx.device, pipelineLayout, null)
        if (descriptorSetLayout != 0L) vkDestroyDescriptorSetLayout(ctx.device, descriptorSetLayout, null)
        if (vertModule != 0L) vkDestroyShaderModule(ctx.device, vertModule, null)
        if (fragModule != 0L) vkDestroyShaderModule(ctx.device, fragModule, null)
        pipeline = 0L; pipelineLayout = 0L; descriptorSetLayout = 0L
        vertModule = 0L; fragModule = 0L
    }
}

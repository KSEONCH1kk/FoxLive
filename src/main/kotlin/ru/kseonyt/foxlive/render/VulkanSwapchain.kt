package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*

/**
 * Two-render-pass setup for postprocessing:
 *
 *   scenePass     — MSAA color + depth → resolve into sceneTex (sampled, single-sample)
 *   compositePass — single-sample, samples sceneTex via fullscreen postfx, then UI on top → swapchain
 */
class VulkanSwapchain(val ctx: VulkanContext) {
    var swapchain: Long = 0L; private set
    var imageFormat: Int = 0; private set
    var depthFormat: Int = 0; private set
    var extentWidth: Int = 0; private set
    var extentHeight: Int = 0; private set

    var scenePass: Long = 0L; private set
    var compositePass: Long = 0L; private set

    var images: LongArray = LongArray(0); private set
    var imageViews: LongArray = LongArray(0); private set

    /** Single scene framebuffer (sceneTex is shared — guarded by per-frame fence). */
    var sceneFramebuffer: Long = 0L; private set
    /** One composite framebuffer per swapchain image (swapchain is the color attachment). */
    var compositeFramebuffers: LongArray = LongArray(0); private set

    var msaaSamples: Int = VK_SAMPLE_COUNT_1_BIT; private set

    // MSAA color (multi-sampled, transient — only when MSAA enabled)
    var msaaColorImage: Long = 0L; private set
    var msaaColorMemory: Long = 0L; private set
    var msaaColorView: Long = 0L; private set

    // Depth (multi-sampled)
    var depthImage: Long = 0L; private set
    var depthImageMemory: Long = 0L; private set
    var depthImageView: Long = 0L; private set

    // Scene texture — postprocess input. Single-sample, sampled.
    var sceneTexImage: Long = 0L; private set
    var sceneTexMemory: Long = 0L; private set
    var sceneTexView: Long = 0L; private set
    var sceneTexSampler: Long = 0L; private set

    /** Render-pass-compatibility view: legacy code may still query [renderPass] — this is the scene pass. */
    val renderPass: Long get() = scenePass

    fun create() {
        msaaSamples = ctx.maxMsaaSamples
        createSwapchain()
        createImageViews()
        chooseDepthFormat()
        createMsaaColorIfNeeded()
        createDepthResources()
        createSceneTexture()
        createScenePass()
        createCompositePass()
        createFramebuffers()
    }

    fun recreate() {
        while (true) {
            val sz = ctx.window.frameBufferSize()
            if (sz[0] != 0 && sz[1] != 0) break
            org.lwjgl.glfw.GLFW.glfwWaitEvents()
        }
        vkDeviceWaitIdle(ctx.device)
        cleanup()
        create()
    }

    private fun createSwapchain() = stackPush().use { st ->
        val caps = VkSurfaceCapabilitiesKHR.calloc(st)
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice, ctx.surface, caps)

        val fmtCount = st.mallocInt(1)
        vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, ctx.surface, fmtCount, null)
        val fmts = VkSurfaceFormatKHR.calloc(fmtCount[0], st)
        vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, ctx.surface, fmtCount, fmts)
        var chosenFmt = fmts.get(0)
        for (i in 0 until fmtCount[0]) {
            val f = fmts.get(i)
            if (f.format() == VK_FORMAT_B8G8R8A8_SRGB &&
                f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                chosenFmt = f; break
            }
        }
        imageFormat = chosenFmt.format()

        val pmCount = st.mallocInt(1)
        vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, ctx.surface, pmCount, null)
        val pmodes = st.mallocInt(pmCount[0])
        vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, ctx.surface, pmCount, pmodes)
        var presentMode = VK_PRESENT_MODE_FIFO_KHR
        for (i in 0 until pmCount[0]) {
            if (pmodes[i] == VK_PRESENT_MODE_MAILBOX_KHR) { presentMode = pmodes[i]; break }
        }

        val sz = ctx.window.frameBufferSize()
        if (caps.currentExtent().width() != Int.MAX_VALUE) {
            extentWidth = caps.currentExtent().width()
            extentHeight = caps.currentExtent().height()
        } else {
            extentWidth = sz[0].coerceIn(caps.minImageExtent().width(), caps.maxImageExtent().width())
            extentHeight = sz[1].coerceIn(caps.minImageExtent().height(), caps.maxImageExtent().height())
        }
        var imageCount = caps.minImageCount() + 1
        if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) imageCount = caps.maxImageCount()

        val info = VkSwapchainCreateInfoKHR.calloc(st)
            .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
            .surface(ctx.surface)
            .minImageCount(imageCount)
            .imageFormat(imageFormat)
            .imageColorSpace(chosenFmt.colorSpace())
            .imageExtent { it.width(extentWidth).height(extentHeight) }
            .imageArrayLayers(1)
            .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
            .preTransform(caps.currentTransform())
            .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            .presentMode(presentMode)
            .clipped(true)
            .oldSwapchain(VK_NULL_HANDLE)

        if (ctx.graphicsFamily != ctx.presentFamily) {
            info.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                .pQueueFamilyIndices(st.ints(ctx.graphicsFamily, ctx.presentFamily))
        } else {
            info.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
        }

        val pSc = st.mallocLong(1)
        Vk.check(vkCreateSwapchainKHR(ctx.device, info, null, pSc), "vkCreateSwapchainKHR")
        swapchain = pSc.get(0)

        val cnt = st.mallocInt(1)
        vkGetSwapchainImagesKHR(ctx.device, swapchain, cnt, null)
        val imgs = st.mallocLong(cnt[0])
        vkGetSwapchainImagesKHR(ctx.device, swapchain, cnt, imgs)
        images = LongArray(cnt[0]) { imgs.get(it) }
    }

    private fun createImageViews() {
        imageViews = LongArray(images.size) { i ->
            VulkanImage.createImageView2D(ctx, images[i], imageFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        }
    }

    private fun chooseDepthFormat() = stackPush().use { st ->
        val candidates = intArrayOf(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT)
        val props = VkFormatProperties.calloc(st)
        depthFormat = candidates.firstOrNull {
            vkGetPhysicalDeviceFormatProperties(ctx.physicalDevice, it, props)
            (props.optimalTilingFeatures() and VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0
        } ?: error("No supported depth format")
    }

    private fun createMsaaColorIfNeeded() {
        if (msaaSamples == VK_SAMPLE_COUNT_1_BIT) return
        val (img, mem) = VulkanImage.createImage2D(ctx, extentWidth, extentHeight,
            imageFormat,
            VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT or VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
            samples = msaaSamples)
        msaaColorImage = img; msaaColorMemory = mem
        msaaColorView = VulkanImage.createImageView2D(ctx, img, imageFormat, VK_IMAGE_ASPECT_COLOR_BIT)
    }

    private fun createDepthResources() {
        val (img, mem) = VulkanImage.createImage2D(ctx, extentWidth, extentHeight,
            depthFormat,
            VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
            samples = msaaSamples)
        depthImage = img; depthImageMemory = mem
        depthImageView = VulkanImage.createImageView2D(ctx, img, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT)
    }

    private fun createSceneTexture() {
        val (img, mem) = VulkanImage.createImage2D(ctx, extentWidth, extentHeight,
            imageFormat,
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT,
            samples = VK_SAMPLE_COUNT_1_BIT)
        sceneTexImage = img; sceneTexMemory = mem
        sceneTexView = VulkanImage.createImageView2D(ctx, img, imageFormat, VK_IMAGE_ASPECT_COLOR_BIT)
        sceneTexSampler = VulkanImage.createSampler(ctx)
    }

    private fun createScenePass() = stackPush().use { st ->
        val msaa = msaaSamples != VK_SAMPLE_COUNT_1_BIT
        val attachmentCount = if (msaa) 3 else 2
        val attachments = VkAttachmentDescription.calloc(attachmentCount, st)

        if (msaa) {
            attachments.get(0)
                .format(imageFormat).samples(msaaSamples)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            attachments.get(1)
                .format(depthFormat).samples(msaaSamples)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
            // Resolve target — sceneTex, will be SAMPLED next pass
            attachments.get(2)
                .format(imageFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        } else {
            // Direct render to sceneTex (single-sample), no MSAA
            attachments.get(0)
                .format(imageFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            attachments.get(1)
                .format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        }

        val colorRef = VkAttachmentReference.calloc(1, st)
            .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val depthRef = VkAttachmentReference.calloc(st)
            .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, st)
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1)
            .pColorAttachments(colorRef)
            .pDepthStencilAttachment(depthRef)
        if (msaa) {
            val resolveRef = VkAttachmentReference.calloc(1, st)
                .attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            subpass.pResolveAttachments(resolveRef)
        }
        // Make the resolve-to-shader-read transition explicit
        val deps = VkSubpassDependency.calloc(2, st)
        deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
        deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)

        val info = VkRenderPassCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(attachments).pSubpasses(subpass).pDependencies(deps)
        val p = st.mallocLong(1)
        Vk.check(vkCreateRenderPass(ctx.device, info, null, p))
        scenePass = p.get(0)
    }

    private fun createCompositePass() = stackPush().use { st ->
        val attachments = VkAttachmentDescription.calloc(1, st)
        attachments.get(0)
            .format(imageFormat).samples(VK_SAMPLE_COUNT_1_BIT)
            .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
        val colorRef = VkAttachmentReference.calloc(1, st)
            .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        val subpass = VkSubpassDescription.calloc(1, st)
            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            .colorAttachmentCount(1).pColorAttachments(colorRef)
        val dep = VkSubpassDependency.calloc(1, st)
            .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .srcAccessMask(0)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
        val info = VkRenderPassCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
            .pAttachments(attachments).pSubpasses(subpass).pDependencies(dep)
        val p = st.mallocLong(1)
        Vk.check(vkCreateRenderPass(ctx.device, info, null, p))
        compositePass = p.get(0)
    }

    private fun createFramebuffers() = stackPush().use { st ->
        // Scene framebuffer (single — sceneTex is shared across frames thanks to inFlight fence)
        val msaa = msaaSamples != VK_SAMPLE_COUNT_1_BIT
        val sceneAttach = st.mallocLong(if (msaa) 3 else 2)
        if (msaa) {
            sceneAttach.put(msaaColorView).put(depthImageView).put(sceneTexView)
        } else {
            sceneAttach.put(sceneTexView).put(depthImageView)
        }
        sceneAttach.flip()
        val sceneFbInfo = VkFramebufferCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
            .renderPass(scenePass).pAttachments(sceneAttach)
            .width(extentWidth).height(extentHeight).layers(1)
        val pSceneFb = st.mallocLong(1)
        Vk.check(vkCreateFramebuffer(ctx.device, sceneFbInfo, null, pSceneFb))
        sceneFramebuffer = pSceneFb.get(0)

        // Composite framebuffers — one per swapchain image
        compositeFramebuffers = LongArray(imageViews.size)
        val pFb = st.mallocLong(1)
        val compAttach = st.mallocLong(1)
        for (i in imageViews.indices) {
            compAttach.put(0, imageViews[i])
            val info = VkFramebufferCreateInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                .renderPass(compositePass).pAttachments(compAttach)
                .width(extentWidth).height(extentHeight).layers(1)
            Vk.check(vkCreateFramebuffer(ctx.device, info, null, pFb))
            compositeFramebuffers[i] = pFb.get(0)
        }
    }

    fun cleanup() {
        if (!ctx.isReady()) return
        if (sceneFramebuffer != 0L) vkDestroyFramebuffer(ctx.device, sceneFramebuffer, null)
        for (fb in compositeFramebuffers) if (fb != 0L) vkDestroyFramebuffer(ctx.device, fb, null)
        if (sceneTexSampler != 0L) vkDestroySampler(ctx.device, sceneTexSampler, null)
        if (sceneTexView != 0L) vkDestroyImageView(ctx.device, sceneTexView, null)
        if (sceneTexImage != 0L) vkDestroyImage(ctx.device, sceneTexImage, null)
        if (sceneTexMemory != 0L) vkFreeMemory(ctx.device, sceneTexMemory, null)
        if (depthImageView != 0L) vkDestroyImageView(ctx.device, depthImageView, null)
        if (depthImage != 0L) vkDestroyImage(ctx.device, depthImage, null)
        if (depthImageMemory != 0L) vkFreeMemory(ctx.device, depthImageMemory, null)
        if (msaaColorView != 0L) vkDestroyImageView(ctx.device, msaaColorView, null)
        if (msaaColorImage != 0L) vkDestroyImage(ctx.device, msaaColorImage, null)
        if (msaaColorMemory != 0L) vkFreeMemory(ctx.device, msaaColorMemory, null)
        for (v in imageViews) if (v != 0L) vkDestroyImageView(ctx.device, v, null)
        if (compositePass != 0L) vkDestroyRenderPass(ctx.device, compositePass, null)
        if (scenePass != 0L) vkDestroyRenderPass(ctx.device, scenePass, null)
        if (swapchain != 0L) vkDestroySwapchainKHR(ctx.device, swapchain, null)
        sceneFramebuffer = 0L; compositeFramebuffers = LongArray(0)
        imageViews = LongArray(0); images = LongArray(0)
        scenePass = 0L; compositePass = 0L; swapchain = 0L
        depthImage = 0L; depthImageMemory = 0L; depthImageView = 0L
        msaaColorImage = 0L; msaaColorMemory = 0L; msaaColorView = 0L
        sceneTexImage = 0L; sceneTexMemory = 0L; sceneTexView = 0L; sceneTexSampler = 0L
    }
}

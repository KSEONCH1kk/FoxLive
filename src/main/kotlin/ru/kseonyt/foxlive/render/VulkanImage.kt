package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import java.nio.ByteBuffer

/**
 * Single 2D image with backing memory + view + sampler.
 * Created from a raw RGBA byte buffer (4 bytes per pixel).
 */
class Texture(
    val image: Long,
    val memory: Long,
    val view: Long,
    val sampler: Long,
    val width: Int,
    val height: Int,
) {
    fun destroy(ctx: VulkanContext) {
        if (sampler != 0L) vkDestroySampler(ctx.device, sampler, null)
        if (view != 0L) vkDestroyImageView(ctx.device, view, null)
        if (image != 0L) vkDestroyImage(ctx.device, image, null)
        if (memory != 0L) vkFreeMemory(ctx.device, memory, null)
    }
}

object VulkanImage {

    fun createImage2D(
        ctx: VulkanContext,
        width: Int, height: Int,
        format: Int, usage: Int,
        tiling: Int = VK_IMAGE_TILING_OPTIMAL,
        memProps: Int = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        samples: Int = VK_SAMPLE_COUNT_1_BIT,
    ): Pair<Long, Long> = stackPush().use { st ->
        val info = VkImageCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
            .imageType(VK_IMAGE_TYPE_2D)
            .format(format)
            .mipLevels(1).arrayLayers(1)
            .samples(samples)
            .tiling(tiling)
            .usage(usage)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
        info.extent().width(width).height(height).depth(1)
        val pImg = st.mallocLong(1)
        Vk.check(vkCreateImage(ctx.device, info, null, pImg))
        val req = VkMemoryRequirements.calloc(st)
        vkGetImageMemoryRequirements(ctx.device, pImg.get(0), req)
        val alloc = VkMemoryAllocateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(req.size())
            .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), memProps))
        val pMem = st.mallocLong(1)
        Vk.check(vkAllocateMemory(ctx.device, alloc, null, pMem))
        vkBindImageMemory(ctx.device, pImg.get(0), pMem.get(0), 0)
        Pair(pImg.get(0), pMem.get(0))
    }

    fun createImageView2D(ctx: VulkanContext, image: Long, format: Int, aspect: Int): Long = stackPush().use { st ->
        val info = VkImageViewCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(image)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(format)
        info.subresourceRange().aspectMask(aspect)
            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
        val p = st.mallocLong(1)
        Vk.check(vkCreateImageView(ctx.device, info, null, p))
        p.get(0)
    }

    fun createSampler(ctx: VulkanContext): Long = stackPush().use { st ->
        val info = VkSamplerCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            .addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
            .anisotropyEnable(false).maxAnisotropy(1f)
            .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
            .unnormalizedCoordinates(false)
            .compareEnable(false).compareOp(VK_COMPARE_OP_ALWAYS)
            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
            .minLod(0f).maxLod(0f).mipLodBias(0f)
        val p = st.mallocLong(1)
        Vk.check(vkCreateSampler(ctx.device, info, null, p))
        p.get(0)
    }

    fun transitionLayout(ctx: VulkanContext, image: Long, oldLayout: Int, newLayout: Int) {
        val cmd = ctx.beginSingleTime()
        stackPush().use { st ->
            val barrier = VkImageMemoryBarrier.calloc(1, st)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)

            val srcStage: Int; val dstStage: Int
            when {
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                    barrier.srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                    dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                }
                oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    srcStage = VK_PIPELINE_STAGE_TRANSFER_BIT
                    dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                }
                else -> error("Unsupported layout transition $oldLayout -> $newLayout")
            }
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier)
        }
        ctx.endSingleTime(cmd)
    }

    fun copyBufferToImage(ctx: VulkanContext, buffer: Long, image: Long, width: Int, height: Int) {
        val cmd = ctx.beginSingleTime()
        stackPush().use { st ->
            val region = VkBufferImageCopy.calloc(1, st)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0)
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1)
            region.imageOffset().set(0, 0, 0)
            region.imageExtent().set(width, height, 1)
            vkCmdCopyBufferToImage(cmd, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region)
        }
        ctx.endSingleTime(cmd)
    }

    /**
     * Create a sampled 2D RGBA texture from raw RGBA8 bytes (4 bytes/pixel, row-major).
     */
    fun createRGBA(ctx: VulkanContext, width: Int, height: Int, pixels: ByteBuffer): Texture =
        stackPush().use { st ->
            val size = (width.toLong() * height.toLong() * 4L)
            val pStaging = MemoryUtil.memAllocLong(1)
            val pStagingMem = MemoryUtil.memAllocLong(1)
            try {
                ctx.createBuffer(size,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pStaging, pStagingMem)
                val pData = st.mallocPointer(1)
                vkMapMemory(ctx.device, pStagingMem.get(0), 0, size, 0, pData)
                MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), pData.get(0), size)
                vkUnmapMemory(ctx.device, pStagingMem.get(0))

                val (img, mem) = createImage2D(ctx, width, height,
                    VK_FORMAT_R8G8B8A8_UNORM,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
                transitionLayout(ctx, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                copyBufferToImage(ctx, pStaging.get(0), img, width, height)
                transitionLayout(ctx, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)

                vkDestroyBuffer(ctx.device, pStaging.get(0), null)
                vkFreeMemory(ctx.device, pStagingMem.get(0), null)

                val view = createImageView2D(ctx, img, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_ASPECT_COLOR_BIT)
                val sampler = createSampler(ctx)
                Texture(img, mem, view, sampler, width, height)
            } finally {
                MemoryUtil.memFree(pStaging); MemoryUtil.memFree(pStagingMem)
            }
        }

    fun defaultWhite(ctx: VulkanContext): Texture {
        val px = MemoryUtil.memAlloc(4)
        try {
            px.put(255.toByte()).put(255.toByte()).put(255.toByte()).put(255.toByte()).flip()
            return createRGBA(ctx, 1, 1, px)
        } finally { MemoryUtil.memFree(px) }
    }

    fun defaultFlatNormal(ctx: VulkanContext): Texture {
        // Tangent-space (0, 0, 1) → encoded as (0.5, 0.5, 1.0) → RGB (128, 128, 255)
        val px = MemoryUtil.memAlloc(4)
        try {
            px.put(128.toByte()).put(128.toByte()).put(255.toByte()).put(255.toByte()).flip()
            return createRGBA(ctx, 1, 1, px)
        } finally { MemoryUtil.memFree(px) }
    }

    fun checkerboard(ctx: VulkanContext, size: Int = 64, c1: Int = 0xFFE0E0E0.toInt(), c2: Int = 0xFF505050.toInt()): Texture {
        val bytes = MemoryUtil.memAlloc(size * size * 4)
        try {
            for (y in 0 until size) for (x in 0 until size) {
                val c = if (((x / 8) + (y / 8)) % 2 == 0) c1 else c2
                bytes.put(((c shr 16) and 0xFF).toByte())
                    .put(((c shr 8) and 0xFF).toByte())
                    .put((c and 0xFF).toByte())
                    .put(((c shr 24) and 0xFF).toByte())
            }
            bytes.flip()
            return createRGBA(ctx, size, size, bytes)
        } finally { MemoryUtil.memFree(bytes) }
    }
}

/**
 * Material binds two textures (diffuse + normal) into a descriptor set.
 * Pass `null` to use the renderer's default texture for that slot.
 */
class Material(
    val diffuse: Texture? = null,
    val normal: Texture? = null,
) : ru.kseonyt.foxlive.ecs.Component {
    var descriptorSet: Long = 0L
    var initialized: Boolean = false
}

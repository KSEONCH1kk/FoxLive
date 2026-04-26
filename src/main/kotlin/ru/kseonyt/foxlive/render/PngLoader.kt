package ru.kseonyt.foxlive.render

import org.lwjgl.stb.STBImage
import org.lwjgl.stb.STBImage.stbi_failure_reason
import org.lwjgl.stb.STBImage.stbi_image_free
import org.lwjgl.stb.STBImage.stbi_load
import org.lwjgl.stb.STBImage.stbi_set_flip_vertically_on_load
import org.lwjgl.system.MemoryStack
import ru.kseonyt.foxlive.ui.UiRenderer

/**
 * Generic loader for raster textures from disk.
 * Caller controls the file path — engine doesn't decide what gets loaded.
 */
data class LoadedSprite(val texture: Texture, val descriptorSet: Long, val width: Int, val height: Int) {
    fun destroy(ctx: VulkanContext) = texture.destroy(ctx)
}

object PngLoader {
    init { stbi_set_flip_vertically_on_load(false) }

    /** Load a PNG/JPG/etc from an absolute or working-dir-relative path into a sampled Vulkan texture. */
    fun load(ctx: VulkanContext, path: String): Texture = MemoryStack.stackPush().use { st ->
        val w = st.mallocInt(1); val h = st.mallocInt(1); val ch = st.mallocInt(1)
        val pixels = stbi_load(path, w, h, ch, 4)
            ?: error("Failed to load image '$path': ${stbi_failure_reason() ?: "unknown error"}")
        try {
            VulkanImage.createRGBA(ctx, w[0], h[0], pixels)
        } finally {
            stbi_image_free(pixels)
        }
    }

    /** Convenience: load + register with the UI renderer in one step so DebugUi can switch to it. */
    fun loadForUi(ctx: VulkanContext, ui: UiRenderer, path: String): LoadedSprite {
        val tex = load(ctx, path)
        val set = ui.registerTexture(tex.view, tex.sampler)
        return LoadedSprite(tex, set, tex.width, tex.height)
    }
}

package ru.kseonyt.foxlive.render

import ru.kseonyt.foxlive.ui.UiRenderer

/**
 * A grid-based sprite atlas backed by a single texture. Caller specifies how many
 * cell columns/rows the texture has; [uvOf] returns the (u0,v0,u1,v1) UV rect for a cell.
 */
class GridAtlas(
    val texture: Texture,
    val descriptorSet: Long,
    val width: Int,
    val height: Int,
    val cols: Int,
    val rows: Int,
) {
    val cellW: Int = width / cols
    val cellH: Int = height / rows

    fun uvOf(col: Int, row: Int): FloatArray {
        val u0 = (col * cellW).toFloat() / width
        val v0 = (row * cellH).toFloat() / height
        val u1 = u0 + cellW.toFloat() / width
        val v1 = v0 + cellH.toFloat() / height
        return floatArrayOf(u0, v0, u1, v1)
    }

    fun uvOfIndex(idx: Int): FloatArray = uvOf(idx % cols, idx / cols)

    fun destroy(ctx: VulkanContext) = texture.destroy(ctx)

    companion object {
        /** Load any PNG/JPG and treat it as a [cols]x[rows] grid of equally sized cells. */
        fun fromPng(
            ctx: VulkanContext, ui: UiRenderer,
            path: String, cols: Int, rows: Int,
        ): GridAtlas {
            val tex = PngLoader.load(ctx, path)
            val set = ui.registerTexture(tex.view, tex.sampler)
            return GridAtlas(tex, set, tex.width, tex.height, cols, rows)
        }
    }
}

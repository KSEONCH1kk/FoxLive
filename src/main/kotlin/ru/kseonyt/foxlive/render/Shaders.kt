package ru.kseonyt.foxlive.render

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.util.shaderc.Shaderc.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import java.nio.ByteBuffer

object ShaderCompiler {
    fun compile(source: String, kind: Int, name: String): ByteBuffer {
        val compiler = shaderc_compiler_initialize()
        if (compiler == 0L) error("shaderc init failed")
        try {
            val opts = shaderc_compile_options_initialize()
            shaderc_compile_options_set_optimization_level(opts, shaderc_optimization_level_performance)
            val result = shaderc_compile_into_spv(compiler, source, kind, name, "main", opts)
            try {
                val status = shaderc_result_get_compilation_status(result)
                if (status != shaderc_compilation_status_success) {
                    val err = shaderc_result_get_error_message(result)
                    error("Shader $name compile failed: $err")
                }
                val bytes = shaderc_result_get_bytes(result) ?: error("No SPIR-V bytes for $name")
                // Copy to a heap-owned buffer because the result is freed below
                val copy = org.lwjgl.system.MemoryUtil.memAlloc(bytes.remaining())
                copy.put(bytes); copy.flip()
                return copy
            } finally {
                shaderc_result_release(result)
                shaderc_compile_options_release(opts)
            }
        } finally {
            shaderc_compiler_release(compiler)
        }
    }

    fun loadResource(path: String): String {
        val s = ShaderCompiler::class.java.classLoader.getResourceAsStream(path)
            ?: error("Resource not found: $path")
        return s.bufferedReader().use { it.readText() }
    }

    fun createShaderModule(device: VkDevice, spirv: ByteBuffer): Long = stackPush().use { st ->
        val info = VkShaderModuleCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
            .pCode(spirv)
        val p = st.mallocLong(1)
        Vk.check(vkCreateShaderModule(device, info, null, p))
        p.get(0)
    }
}

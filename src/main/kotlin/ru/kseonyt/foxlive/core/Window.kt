package ru.kseonyt.foxlive.core

import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported
import org.lwjgl.system.MemoryStack

class Window(val title: String, var width: Int, var height: Int) {
    var handle: Long = 0L
        private set
    var resized = false
        private set
    val input = Input()

    fun init() {
        if (!glfwInit()) error("GLFW init failed")
        if (!glfwVulkanSupported()) error("Vulkan not supported on this system")
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        handle = glfwCreateWindow(width, height, title, 0L, 0L)
        if (handle == 0L) error("Failed to create GLFW window")

        glfwSetFramebufferSizeCallback(handle) { _, w, h ->
            width = w; height = h; resized = true
        }
        glfwSetKeyCallback(handle) { _, key, _, action, _ ->
            input.onKey(key, action)
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) close()
        }
        glfwSetCursorPosCallback(handle) { _, x, y -> input.onMouse(x, y) }
        glfwSetMouseButtonCallback(handle) { _, button, action, _ -> input.onMouseButton(button, action) }

        val vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor())
        if (vidMode != null) {
            glfwSetWindowPos(handle, (vidMode.width() - width) / 2, (vidMode.height() - height) / 2)
        }
    }

    fun frameBufferSize(): IntArray = MemoryStack.stackPush().use { st ->
        val w = st.mallocInt(1); val h = st.mallocInt(1)
        glfwGetFramebufferSize(handle, w, h)
        intArrayOf(w[0], h[0])
    }

    fun show() = glfwShowWindow(handle)
    fun pollEvents() { resized = false; input.beginFrame(); glfwPollEvents() }
    fun shouldClose(): Boolean = glfwWindowShouldClose(handle)
    fun close() = glfwSetWindowShouldClose(handle, true)

    fun destroy() {
        glfwFreeCallbacks(handle)
        glfwDestroyWindow(handle)
        glfwTerminate()
    }
}

package ru.kseonyt.foxlive.render

import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import ru.kseonyt.foxlive.core.Window
import java.nio.LongBuffer

object Vk {
    fun check(result: Int, msg: String = "Vulkan call failed") {
        if (result != VK_SUCCESS) error("$msg (VkResult=$result)")
    }
}

class VulkanContext(val window: Window, val enableValidation: Boolean = false) {
    lateinit var instance: VkInstance
        private set
    private var debugMessenger: Long = 0L
    var surface: Long = 0L
        private set
    lateinit var physicalDevice: VkPhysicalDevice
        private set
    lateinit var device: VkDevice
        private set
    lateinit var graphicsQueue: VkQueue
        private set
    lateinit var presentQueue: VkQueue
        private set
    var graphicsFamily: Int = 0
        private set
    var presentFamily: Int = 0
        private set
    var commandPool: Long = 0L
        private set
    var maxMsaaSamples: Int = VK_SAMPLE_COUNT_1_BIT
        private set
    private val memProps = VkPhysicalDeviceMemoryProperties.calloc()

    fun isReady(): Boolean = ::device.isInitialized

    fun init() {
        createInstance()
        if (enableValidation) setupDebugMessenger()
        createSurface()
        pickPhysicalDevice()
        createLogicalDevice()
        createCommandPool()
    }

    private fun createInstance() = stackPush().use { st ->
        val appInfo = VkApplicationInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(st.UTF8("FoxLive"))
            .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
            .pEngineName(st.UTF8("FoxLive Engine"))
            .engineVersion(VK_MAKE_VERSION(1, 0, 0))
            // Use 1.0 for maximum compatibility — drivers report INCOMPATIBLE_DRIVER otherwise
            .apiVersion(VK_API_VERSION_1_0)

        val glfwExt = glfwGetRequiredInstanceExtensions()
            ?: error("GLFW failed to find required Vulkan extensions — driver/loader missing?")
        val extCount = if (enableValidation) glfwExt.remaining() + 1 else glfwExt.remaining()
        val extensions = st.mallocPointer(extCount)
        extensions.put(glfwExt)
        if (enableValidation) extensions.put(st.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
        extensions.flip()

        val createInfo = VkInstanceCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .pApplicationInfo(appInfo)
            .ppEnabledExtensionNames(extensions)

        if (enableValidation) {
            val layers = st.mallocPointer(1).put(st.UTF8("VK_LAYER_KHRONOS_validation")).flip()
            createInfo.ppEnabledLayerNames(layers)
        }

        val pInstance = st.mallocPointer(1)
        Vk.check(vkCreateInstance(createInfo, null, pInstance), "vkCreateInstance")
        instance = VkInstance(pInstance.get(0), createInfo)
    }

    private fun setupDebugMessenger() = stackPush().use { st ->
        val info = VkDebugUtilsMessengerCreateInfoEXT.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
            .messageSeverity(
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
            )
            .messageType(
                VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
            )
            .pfnUserCallback { _, _, pData, _ ->
                val d = VkDebugUtilsMessengerCallbackDataEXT.create(pData)
                System.err.println("[Vulkan] " + d.pMessageString())
                VK_FALSE
            }
        val p = st.mallocLong(1)
        Vk.check(vkCreateDebugUtilsMessengerEXT(instance, info, null, p))
        debugMessenger = p.get(0)
    }

    private fun createSurface() = stackPush().use { st ->
        val p = st.mallocLong(1)
        Vk.check(glfwCreateWindowSurface(instance, window.handle, null, p), "glfwCreateWindowSurface")
        surface = p.get(0)
    }

    private fun pickPhysicalDevice() = stackPush().use { st ->
        val count = st.mallocInt(1)
        Vk.check(vkEnumeratePhysicalDevices(instance, count, null))
        if (count[0] == 0) error("No Vulkan-capable GPUs found")
        val devices = st.mallocPointer(count[0])
        Vk.check(vkEnumeratePhysicalDevices(instance, count, devices))

        var chosen: VkPhysicalDevice? = null
        var bestScore = -1
        for (i in 0 until count[0]) {
            val pd = VkPhysicalDevice(devices.get(i), instance)
            val (g, p, ok) = findQueueFamilies(pd, st)
            if (!ok) continue
            if (!checkDeviceExtensions(pd, st)) continue
            if (!swapchainAdequate(pd, st)) continue
            val props = VkPhysicalDeviceProperties.calloc(st)
            vkGetPhysicalDeviceProperties(pd, props)
            val score = if (props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) 1000 else 100
            if (score > bestScore) {
                bestScore = score
                chosen = pd
                graphicsFamily = g
                presentFamily = p
            }
        }
        physicalDevice = chosen ?: error("No suitable Vulkan physical device")
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps)

        // Determine max usable MSAA sample count (color & depth)
        val deviceProps = VkPhysicalDeviceProperties.calloc(st)
        vkGetPhysicalDeviceProperties(physicalDevice, deviceProps)
        val counts = deviceProps.limits().framebufferColorSampleCounts() and
                     deviceProps.limits().framebufferDepthSampleCounts()
        maxMsaaSamples = when {
            (counts and VK_SAMPLE_COUNT_8_BIT) != 0 -> VK_SAMPLE_COUNT_4_BIT // cap at 4x for perf
            (counts and VK_SAMPLE_COUNT_4_BIT) != 0 -> VK_SAMPLE_COUNT_4_BIT
            (counts and VK_SAMPLE_COUNT_2_BIT) != 0 -> VK_SAMPLE_COUNT_2_BIT
            else -> VK_SAMPLE_COUNT_1_BIT
        }
    }

    private data class QF(val graphics: Int, val present: Int, val ok: Boolean)

    private fun findQueueFamilies(pd: VkPhysicalDevice, st: MemoryStack): QF {
        val count = st.mallocInt(1)
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, null)
        if (count[0] == 0) return QF(0, 0, false)
        val props = VkQueueFamilyProperties.calloc(count[0], st)
        vkGetPhysicalDeviceQueueFamilyProperties(pd, count, props)
        var g = -1; var p = -1
        val pSupport = st.mallocInt(1)
        for (i in 0 until count[0]) {
            if (g == -1 && (props.get(i).queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0) g = i
            vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface, pSupport)
            if (p == -1 && pSupport[0] == VK_TRUE) p = i
            if (g != -1 && p != -1) break
        }
        return QF(g, p, g != -1 && p != -1)
    }

    private fun checkDeviceExtensions(pd: VkPhysicalDevice, st: MemoryStack): Boolean {
        val count = st.mallocInt(1)
        vkEnumerateDeviceExtensionProperties(pd, null as String?, count, null)
        if (count[0] == 0) return false
        val props = VkExtensionProperties.calloc(count[0], st)
        vkEnumerateDeviceExtensionProperties(pd, null as String?, count, props)
        for (i in 0 until count[0]) {
            if (props.get(i).extensionNameString() == VK_KHR_SWAPCHAIN_EXTENSION_NAME) return true
        }
        return false
    }

    private fun swapchainAdequate(pd: VkPhysicalDevice, st: MemoryStack): Boolean {
        val fmtCount = st.mallocInt(1)
        vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, fmtCount, null)
        val pmCount = st.mallocInt(1)
        vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, pmCount, null)
        return fmtCount[0] > 0 && pmCount[0] > 0
    }

    private fun createLogicalDevice() = stackPush().use { st ->
        val unique = if (graphicsFamily == presentFamily) intArrayOf(graphicsFamily)
                     else intArrayOf(graphicsFamily, presentFamily)
        val queueInfos = VkDeviceQueueCreateInfo.calloc(unique.size, st)
        val priorities = st.floats(1.0f)
        for ((i, fam) in unique.withIndex()) {
            queueInfos.get(i)
                .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(fam)
                .pQueuePriorities(priorities)
        }
        val features = VkPhysicalDeviceFeatures.calloc(st)
            .samplerAnisotropy(true)

        val extPtr = st.mallocPointer(1).put(st.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)).flip()

        val info = VkDeviceCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pQueueCreateInfos(queueInfos)
            .pEnabledFeatures(features)
            .ppEnabledExtensionNames(extPtr)

        val pDevice = st.mallocPointer(1)
        Vk.check(vkCreateDevice(physicalDevice, info, null, pDevice), "vkCreateDevice")
        device = VkDevice(pDevice.get(0), physicalDevice, info)

        val pq = st.mallocPointer(1)
        vkGetDeviceQueue(device, graphicsFamily, 0, pq)
        graphicsQueue = VkQueue(pq.get(0), device)
        vkGetDeviceQueue(device, presentFamily, 0, pq)
        presentQueue = VkQueue(pq.get(0), device)
    }

    private fun createCommandPool() = stackPush().use { st ->
        val info = VkCommandPoolCreateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(graphicsFamily)
        val p = st.mallocLong(1)
        Vk.check(vkCreateCommandPool(device, info, null, p))
        commandPool = p.get(0)
    }

    fun findMemoryType(typeFilter: Int, properties: Int): Int {
        for (i in 0 until memProps.memoryTypeCount()) {
            if ((typeFilter and (1 shl i)) != 0 &&
                (memProps.memoryTypes(i).propertyFlags() and properties) == properties) return i
        }
        error("Failed to find suitable Vulkan memory type")
    }

    fun createBuffer(size: Long, usage: Int, properties: Int, outBuf: LongBuffer, outMem: LongBuffer) =
        stackPush().use { st ->
            val info = VkBufferCreateInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            Vk.check(vkCreateBuffer(device, info, null, outBuf))
            val req = VkMemoryRequirements.calloc(st)
            vkGetBufferMemoryRequirements(device, outBuf.get(0), req)
            val alloc = VkMemoryAllocateInfo.calloc(st)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(findMemoryType(req.memoryTypeBits(), properties))
            Vk.check(vkAllocateMemory(device, alloc, null, outMem))
            vkBindBufferMemory(device, outBuf.get(0), outMem.get(0), 0)
        }

    fun beginSingleTime(): VkCommandBuffer = stackPush().use { st ->
        val alloc = VkCommandBufferAllocateInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        val pCmd = st.mallocPointer(1)
        vkAllocateCommandBuffers(device, alloc, pCmd)
        val cmd = VkCommandBuffer(pCmd.get(0), device)
        val begin = VkCommandBufferBeginInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
        vkBeginCommandBuffer(cmd, begin)
        cmd
    }

    fun endSingleTime(cmd: VkCommandBuffer) = stackPush().use { st ->
        vkEndCommandBuffer(cmd)
        val pCmd = st.pointers(cmd.address())
        val submit = VkSubmitInfo.calloc(st)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(pCmd)
        vkQueueSubmit(graphicsQueue, submit, VK_NULL_HANDLE)
        vkQueueWaitIdle(graphicsQueue)
        vkFreeCommandBuffers(device, commandPool, pCmd)
    }

    fun copyBuffer(src: Long, dst: Long, size: Long) {
        val cmd = beginSingleTime()
        stackPush().use { st ->
            val copy = VkBufferCopy.calloc(1, st).size(size)
            vkCmdCopyBuffer(cmd, src, dst, copy)
        }
        endSingleTime(cmd)
    }

    fun destroy() {
        if (::device.isInitialized) {
            if (commandPool != 0L) {
                vkDestroyCommandPool(device, commandPool, null)
                commandPool = 0L
            }
            vkDestroyDevice(device, null)
        }
        if (::instance.isInitialized) {
            if (debugMessenger != 0L) {
                vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null)
                debugMessenger = 0L
            }
            if (surface != 0L) {
                vkDestroySurfaceKHR(instance, surface, null)
                surface = 0L
            }
            vkDestroyInstance(instance, null)
        }
        memProps.free()
    }
}

package andy

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR
import org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR
import org.lwjgl.vulkan.VK10.*
import java.nio.LongBuffer

class HelloTriangleApplication {

    private var window: Long = 0
    private lateinit var instance: VkInstance
    private var debugMessenger: Long = 0
    private var surface: Long = 0
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice
    private lateinit var graphicsQueue: VkQueue
    private lateinit var presentQueue: VkQueue

    fun run() {
        initWindow()
        initVulkan()
        mainLoop()
        cleanup()
    }

    private fun initWindow() {
        if (!glfwInit()) {
            throw RuntimeException("Cannot initialize GLFW")
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE)

        val title = javaClass.simpleName

        window = glfwCreateWindow(WIDTH, HEIGHT, title, NULL, NULL)

        if (window == NULL) {
            throw RuntimeException("Cannot create window")
        }
    }

    private fun initVulkan() {
        createInstance()
        setupDebugMessenger()
        createSurface()
        pickPhysicalDevice()
        createLogicDevice()
    }

    private fun mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
        }
    }

    private fun pickPhysicalDevice() {
        MemoryStack.stackPush().use { stack ->
            val deviceCount = stack.ints(0)
            vkEnumeratePhysicalDevices(instance, deviceCount, null)
            if (deviceCount[0] == 0) {
                throw RuntimeException("Failed find GPUs with Vulkan support")
            }
            val ppPhysicalDevices = stack.mallocPointer(deviceCount[0])
            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices)
            physicalDevice = ppPhysicalDevices.asIterable()
                    .map { VkPhysicalDevice(it, instance) }
                    .firstOrNull { isDeviceSuitable(it) }
                    ?: throw RuntimeException("Failed to find a suitable GPU")

        }
    }

    private fun createSurface() {
        MemoryStack.stackPush().use { stack ->
            val pSurface = stack.longs(VK_NULL_HANDLE)
            if (glfwCreateWindowSurface(instance, window, null, pSurface) != VK_SUCCESS) {
                throw RuntimeException("Failed to create window surface")
            }
            surface = pSurface[0]
        }
    }

    private fun isDeviceSuitable(device: VkPhysicalDevice): Boolean {
        return findQueueFamilies(device).isComplete()
    }

    private fun findQueueFamilies(device: VkPhysicalDevice): QueueFamilyIndices {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyCount = stack.ints(0)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)
            val queueFamilies = VkQueueFamilyProperties.mallocStack(queueFamilyCount[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)

            val indices = QueueFamilyIndices()
            for (i in 0 until queueFamilies.capacity()) {
                if ((queueFamilies[i].queueFlags() and VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i
                    break
                }
            }
            val presentSupport = stack.ints(0)
            for (i in 0 until queueFamilies.capacity()) {
                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport)
                if (presentSupport[0] == VK_TRUE) {
                    indices.presentFamily = i
                    break
                }
            }
            return indices
        }
    }

    private fun createLogicDevice() {
        MemoryStack.stackPush().use { stack ->
            val indices = findQueueFamilies(physicalDevice)
            val uniqueQueueFamilies = indices.unique()

            val queueCreateInfos = VkDeviceQueueCreateInfo.callocStack(uniqueQueueFamilies.size, stack)

            for (i in uniqueQueueFamilies.indices) {
                val queueCreateInfo = queueCreateInfos[i]
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                queueCreateInfo.queueFamilyIndex(indices.graphicsFamily!!)
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f))
            }

            val deviceFeatures = VkPhysicalDeviceFeatures.callocStack(stack)
            val createInfo = VkDeviceCreateInfo.callocStack(stack)

            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            createInfo.pQueueCreateInfos(queueCreateInfos)
            createInfo.pEnabledFeatures(deviceFeatures)

            if (ENABLE_VALIDATION_LAYERS) {
                createInfo.ppEnabledLayerNames(validationLayerAsPointBuffer())
            }

            val pDevice = stack.pointers(VK_NULL_HANDLE)

            if (vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw RuntimeException("Failed to create logic device")
            }

            device = VkDevice(pDevice[0], physicalDevice, createInfo)
            val pQueue = stack.pointers(VK_NULL_HANDLE)
            vkGetDeviceQueue(device, indices.graphicsFamily!!, 0, pQueue)
            graphicsQueue = VkQueue(pQueue[0], device)
            vkGetDeviceQueue(device, indices.presentFamily!!, 0, pQueue)
            presentQueue = VkQueue(pQueue[0], device)
        }
    }

    private fun cleanup() {

        vkDestroyDevice(device, null)

        if (ENABLE_VALIDATION_LAYERS) {
            destroyDebugUtilsMessengerEXT(instance, debugMessenger, null)
        }

        vkDestroySurfaceKHR(instance, surface, null)
        vkDestroyInstance(instance, null)
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun createInstance() {
        if (ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
            throw RuntimeException("Validation layers requested, but not available!")
        }

        MemoryStack.stackPush().use { stack ->
            val appInfo = VkApplicationInfo.callocStack(stack).apply {
                sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                pApplicationName(stack.UTF8Safe("Hello Triangle."))
                applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                pEngineName(stack.UTF8Safe("No Engine"))
                engineVersion(VK_MAKE_VERSION(1, 0, 0))
                apiVersion(VK_API_VERSION_1_0)
            }

            val createInfo = VkInstanceCreateInfo.callocStack(stack).apply {
                sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                pApplicationInfo(appInfo)
                ppEnabledExtensionNames(getRequiredExtensions())
                if (ENABLE_VALIDATION_LAYERS) {
                    ppEnabledLayerNames(validationLayerAsPointBuffer())
                    val debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack)
                    populateDebugMessengerCreateInfo(debugCreateInfo)
                    pNext(debugCreateInfo.address())
                } else {
                    ppEnabledLayerNames(null)
                }
            }

            val instancePtr = stack.mallocPointer(1)
            if (vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create instance")
            }

            instance = VkInstance(instancePtr[0], createInfo)
        }
    }

    private fun checkValidationLayerSupport(): Boolean {
        MemoryStack.stackPush().use { stack ->
            val layerCount = stack.ints(1)
            vkEnumerateInstanceLayerProperties(layerCount, null)
            val availableLayers = VkLayerProperties.mallocStack(layerCount[0], stack)
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers)

            return availableLayers
                    .map { it.layerNameString() }
                    .containsAll(VALIDATION_LAYERS)
        }
    }

    private fun getRequiredExtensions(): PointerBuffer {
        val glfwExtensions = glfwGetRequiredInstanceExtensions() ?: PointerBuffer.allocateDirect(0)
        if (ENABLE_VALIDATION_LAYERS) {
            val stack = MemoryStack.stackGet()
            val requiredExtensions = stack.mallocPointer(glfwExtensions.capacity() + 1)
            requiredExtensions.put(glfwExtensions)
            requiredExtensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME))
            return requiredExtensions
        } else {
            return glfwExtensions
        }
    }

    private fun populateDebugMessengerCreateInfo(debugCreateInfo: VkDebugUtilsMessengerCreateInfoEXT) {
        debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
        debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
        debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
        debugCreateInfo.pfnUserCallback(Companion::debugCallback)
    }

    private fun setupDebugMessenger() {
        if (!ENABLE_VALIDATION_LAYERS) {
            return
        }

        MemoryStack.stackPush().use { stack ->
            val createInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack)
            populateDebugMessengerCreateInfo(createInfo)
            val pDebugMessenger = stack.longs(VK_NULL_HANDLE)
            if (createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) != VK_SUCCESS) {
                throw RuntimeException("Failed to set up debug messenger")
            }
            debugMessenger = pDebugMessenger[0]
        }
    }

    companion object {
        private const val WIDTH = 800
        private const val HEIGHT = 600

        private val ENABLE_VALIDATION_LAYERS = false //DEBUG.get(true)
        private val VALIDATION_LAYERS = HashSet<String>().apply {
            if (ENABLE_VALIDATION_LAYERS) {
                add("VK_LAYER_KHRONOS_validation")
//                add("VK_LAYER_LUNARG_standard_validation")
            }
        }

        private fun validationLayerAsPointBuffer(): PointerBuffer {
            val stack = MemoryStack.stackGet()
            val buffer = stack.mallocPointer(VALIDATION_LAYERS.size)
            VALIDATION_LAYERS
                    .map { stack.UTF8(it) }
                    .forEach { buffer.put(it) }
            return buffer.rewind()
        }

        private fun debugCallback(messageSeverity: Int, messageType: Int, pCallbackData: Long, pUserData: Long): Int {
            val callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)
            System.err.println("Validation layer: ${callbackData.pMessageString()}")
            return VK_FALSE
        }

        private fun createDebugUtilsMessengerEXT(instance: VkInstance, createInfo: VkDebugUtilsMessengerCreateInfoEXT,
                                                 allocationCallbacks: VkAllocationCallbacks?, pDebugMessenger: LongBuffer): Int {
            if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
                return vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger)
            }
            return VK_ERROR_EXTENSION_NOT_PRESENT
        }

        private fun destroyDebugUtilsMessengerEXT(instance: VkInstance, debugMessenger: Long, allocationCallbacks: VkAllocationCallbacks?) {
            if (vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
                vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks)
            }
        }
    }

    inner class QueueFamilyIndices {
        var graphicsFamily: Int? = null
        var presentFamily: Int? = null

        private fun allFamilies(): List<Int?> {
            return listOf(graphicsFamily, presentFamily)
        }

        fun isComplete(): Boolean {
            return allFamilies().all { it != null }
        }

        fun unique(): IntArray {
            return allFamilies()
                    .map { it!! }
                    .distinct()
                    .toIntArray()
        }
    }
}

internal fun PointerBuffer.asIterable(): Iterable<Long> {
    return object : Iterable<Long> {
        override fun iterator() = object : Iterator<Long> {
            private var index = 0
            override fun hasNext() = index < capacity()
            override fun next(): Long {
                if (index < capacity()) {
                    val result = get(index)
                    index++
                    return result
                } else {
                    throw NoSuchElementException(index.toString())
                }
            }
        }
    }
}

fun main() {
    HelloTriangleApplication().run()
}
package andy

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.Configuration.DEBUG
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.VK10.*
import java.nio.LongBuffer

class HelloTriangleApplication {

    private var window: Long = 0
    private lateinit var instance: VkInstance
    private var debugMessenger: Long = 0
    private lateinit var physicalDevice: VkPhysicalDevice

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
        pickPhysicalDevice()
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

    private fun isDeviceSuitable(device: VkPhysicalDevice): Boolean {
        return findQueueFamilies(device).isComplete()
    }

    private fun findQueueFamilies(device: VkPhysicalDevice): QueueFamilyIndices {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyCount = stack.ints(0)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null)
            val queueFamilies = VkQueueFamilyProperties.mallocStack(queueFamilyCount[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies)
            return QueueFamilyIndices().apply {
                graphicsFamily = queueFamilies
                        .map { it.queueFlags() }
                        .firstOrNull { (it and VK_QUEUE_GRAPHICS_BIT) != 0 }
            }
        }
    }

    private fun cleanup() {
        if (ENABLE_VALIDATION_LAYERS) {
            destroyDebugUtilsMessengerEXT(instance, debugMessenger, null)
        }

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

        fun isComplete() = graphicsFamily != null
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
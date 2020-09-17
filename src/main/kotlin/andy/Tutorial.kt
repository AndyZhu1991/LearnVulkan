package andy

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.*
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import java.lang.Integer.min
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.max

class HelloTriangleApplication {

    private var window: Long = 0
    private lateinit var instance: VkInstance
    private var debugMessenger: Long = 0
    private var surface: Long = 0
    private lateinit var physicalDevice: VkPhysicalDevice
    private lateinit var device: VkDevice

    private lateinit var graphicsQueue: VkQueue
    private lateinit var presentQueue: VkQueue

    private var swapChain: Long = 0
    private var swapChainImages: List<Long> = emptyList()
    private var swapChainImageViews: List<Long> = emptyList()
    private var swapChainImageFormat: Int = 0
    private lateinit var swapChainExtent: VkExtent2D

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
        createSwapChain()
        createImageViews()
        createGraphicsPipeline()
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
        val indices = findQueueFamilies(device)
        val extensionsSupported = checkDeviceExtensionSupport(device)
        var swapChainAdequate = false
        if (extensionsSupported) {
            MemoryStack.stackPush().use { stack ->
                val swapChainSupport = querySwapChainSupport(device, stack)
                swapChainAdequate = swapChainSupport.formats.hasRemaining() && swapChainSupport.presentModes.hasRemaining()
            }
        }

        return indices.isComplete() && extensionsSupported && swapChainAdequate
    }

    private fun checkDeviceExtensionSupport(device: VkPhysicalDevice): Boolean {
        MemoryStack.stackPush().use { stack ->
            val extensionCount = stack.ints(0)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, null)
            val availableExtensions = VkExtensionProperties.mallocStack(extensionCount[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)
            return availableExtensions
                    .map { it.extensionNameString() }
                    .containsAll(DEVICE_EXTENSIONS)
        }
    }

    private fun querySwapChainSupport(device: VkPhysicalDevice, stack: MemoryStack): SwapChainSupportDetails {
        val details = SwapChainSupportDetails()

        details.capabilities = VkSurfaceCapabilitiesKHR.mallocStack(stack)
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities)

        val count = stack.ints(0)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null)
        if (count[0] != 0) {
            details.formats = VkSurfaceFormatKHR.mallocStack(count[0], stack)
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats)
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null)
        if (count[0] != 0) {
            details.presentModes = stack.mallocInt(count[0])
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes)
        }

        return details
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
            val createInfo = VkDeviceCreateInfo.callocStack(stack).apply {
                sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                pQueueCreateInfos(queueCreateInfos)
                pEnabledFeatures(deviceFeatures)
                ppEnabledExtensionNames(asPointBuffer(DEVICE_EXTENSIONS))
                if (ENABLE_VALIDATION_LAYERS) {
                    ppEnabledLayerNames(asPointBuffer(VALIDATION_LAYERS))
                }
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

        swapChainImageViews.forEach { vkDestroyImageView(device, it, null) }
        vkDestroySwapchainKHR(device, swapChain, null)
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
                    ppEnabledLayerNames(asPointBuffer(VALIDATION_LAYERS))
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

    private fun createGraphicsPipeline() {
        MemoryStack.stackPush().use { stack ->
            val vertShaderSPIRV = compileShaderFile("shader/09_shader_base.vert", ShakerKind.VERTEX_SHADER)
            val fragShaderSPIRV = compileShaderFile("shader/09_shader_base.frag", ShakerKind.FRAGMENT_SHADER)

            if (vertShaderSPIRV == null || fragShaderSPIRV == null) {
                throw RuntimeException("Failed to compile shader.")
            }

            val vertShaderModule = createShaderModule(vertShaderSPIRV.bytecode)
            val fragShaderModule = createShaderModule(fragShaderSPIRV.bytecode)

            val entryPoint = stack.UTF8("main")

            val shaderStages = VkPipelineShaderStageCreateInfo.callocStack(2, stack)

            val vertShaderStageInfo = shaderStages[0].apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                stage(VK_SHADER_STAGE_VERTEX_BIT)
                module(vertShaderModule)
                pName(entryPoint)
            }

            val fragShaderStageInfo = shaderStages[1].apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                module(fragShaderModule)
                pName(entryPoint)
            }

            vkDestroyShaderModule(device, vertShaderModule, null)
            vkDestroyShaderModule(device, fragShaderModule, null)

            vertShaderSPIRV.free()
            fragShaderSPIRV.free()
        }
    }

    private fun createShaderModule(spirvCode: ByteBuffer): Long {
        MemoryStack.stackPush().use { stack ->
            val createInfo = VkShaderModuleCreateInfo.callocStack(stack).apply {
                sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                pCode(spirvCode)
            }

            val pShaderModule = stack.mallocLong(1)

            if (vkCreateShaderModule(device, createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw RuntimeException("Failed to create shader module.")
            }

            return pShaderModule[0]
        }
    }

    private fun createImageViews() {
        MemoryStack.stackPush().use { stack ->
            val pImageView = stack.mallocLong(1)
            swapChainImageViews = swapChainImages.map { swapChainImage ->
                val createInfo = VkImageViewCreateInfo.callocStack(stack).apply {
                    sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    image(swapChainImage)
                    viewType(VK_IMAGE_VIEW_TYPE_2D)
                    format(swapChainImageFormat)

                    components().r(VK_COMPONENT_SWIZZLE_IDENTITY)
                    components().g(VK_COMPONENT_SWIZZLE_IDENTITY)
                    components().b(VK_COMPONENT_SWIZZLE_IDENTITY)
                    components().a(VK_COMPONENT_SWIZZLE_IDENTITY)

                    subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    subresourceRange().baseMipLevel(0)
                    subresourceRange().levelCount(1)
                    subresourceRange().baseArrayLayer(0)
                    subresourceRange().layerCount(1)
                }

                if (vkCreateImageView(device, createInfo, null, pImageView) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create image views.")
                }

                pImageView[0]
            }
        }
    }

    private fun createSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val swapChainSupportDetails = querySwapChainSupport(physicalDevice, stack)

            val surfaceFormat = chooseSwapSurfaceFormat(swapChainSupportDetails.formats)
            val presentMode = chooseSwapPresentMode(swapChainSupportDetails.presentModes)
            val extent = chooseSwapExtent(swapChainSupportDetails.capabilities)

            val imageCount = stack.ints(swapChainSupportDetails.capabilities.minImageCount() + 1)

            if (swapChainSupportDetails.capabilities.maxImageCount() > 0
                    && swapChainSupportDetails.capabilities.maxImageCount() < imageCount[0]) {
                imageCount.put(0, swapChainSupportDetails.capabilities.maxImageCount())
            }

            val createInfo = VkSwapchainCreateInfoKHR.callocStack(stack).apply {
                sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                surface(surface)

                // Image settings
                minImageCount(imageCount[0])
                imageFormat(surfaceFormat.format())
                imageColorSpace(surfaceFormat.colorSpace())
                imageExtent(extent)
                imageArrayLayers(1)
                imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)

                val indices = findQueueFamilies(physicalDevice)
                if (indices.graphicsFamily != (indices.presentFamily)) {
                    imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                    pQueueFamilyIndices(stack.ints(indices.graphicsFamily!!, indices.presentFamily!!))
                } else {
                    imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                }

                preTransform(swapChainSupportDetails.capabilities.currentTransform())
                compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                presentMode(presentMode)
                clipped(true)

                oldSwapchain(VK_NULL_HANDLE)
            }

            val pSwapChain = stack.longs(VK_NULL_HANDLE)

            if (vkCreateSwapchainKHR(device, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw RuntimeException("Failed to create swap chain")
            }

            val swapChain = pSwapChain[0]
            vkGetSwapchainImagesKHR(device, swapChain, imageCount, null)
            val pSwapChainImages = stack.mallocLong(imageCount[0])
            vkGetSwapchainImagesKHR(device, swapChain, imageCount, pSwapChainImages)
            swapChainImages = ArrayList<Long>(imageCount[0]).apply {
                for (i in 0 until pSwapChainImages.capacity()) {
                    add(pSwapChainImages[i])
                }
            }
            swapChainImageFormat = surfaceFormat.format()
            swapChainExtent = VkExtent2D.create().set(extent)
        }
    }

    private fun chooseSwapSurfaceFormat(availableFormats: VkSurfaceFormatKHR.Buffer): VkSurfaceFormatKHR {
        return availableFormats
                .filter { it.format() == VK_FORMAT_B8G8R8_UNORM }
                .firstOrNull { it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
                ?: availableFormats[0]
    }

    private fun chooseSwapPresentMode(availablePresentModes: IntBuffer): Int {
        for (i in 0 until availablePresentModes.capacity()) {
            if (availablePresentModes[i] == VK_PRESENT_MODE_MAILBOX_KHR) {
                return VK_PRESENT_MODE_MAILBOX_KHR
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR
    }

    private fun chooseSwapExtent(capabilities: VkSurfaceCapabilitiesKHR): VkExtent2D {
        if (capabilities.currentExtent().width() != UINT_MAX) {
            return capabilities.currentExtent()
        }

        val actualExtent = VkExtent2D.mallocStack().set(WIDTH, HEIGHT)

        val minExtent = capabilities.minImageExtent()
        val maxExtent = capabilities.maxImageExtent()

        actualExtent.width(clamp(minExtent.width(), maxExtent.width(), actualExtent.width()))
        actualExtent.height(clamp(minExtent.height(), maxExtent.height(), actualExtent.height()))

        return actualExtent
    }

    private fun clamp(min: Int, max: Int, value: Int): Int {
        return max(min, min(max, value))
    }

    companion object {

        private const val UINT_MAX = (0xFFFFFFFF).toInt()

        private const val WIDTH = 800
        private const val HEIGHT = 600

        private val ENABLE_VALIDATION_LAYERS = false //DEBUG.get(true)
        private val VALIDATION_LAYERS = HashSet<String>().apply {
            if (ENABLE_VALIDATION_LAYERS) {
                add("VK_LAYER_KHRONOS_validation")
//                add("VK_LAYER_LUNARG_standard_validation")
            }
        }

        private val DEVICE_EXTENSIONS = setOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)

        private fun asPointBuffer(strings: Collection<String>): PointerBuffer {
            val stack = MemoryStack.stackGet()
            val buffer = stack.mallocPointer(strings.size)
            strings.forEach {
                buffer.put(stack.UTF8(it))
            }
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

    internal class QueueFamilyIndices {
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

        fun array(): IntArray {
            return allFamilies()
                    .map { it!! }
                    .toIntArray()
        }
    }

    internal class SwapChainSupportDetails {
        lateinit var capabilities: VkSurfaceCapabilitiesKHR
        lateinit var formats: VkSurfaceFormatKHR.Buffer
        lateinit var presentModes: IntBuffer
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
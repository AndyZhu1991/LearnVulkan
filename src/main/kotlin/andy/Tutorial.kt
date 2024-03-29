package andy

import org.joml.Vector2f
import org.joml.Vector3f
import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.Pointer
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
    private var swapChainFrameBuffers: List<Long> = emptyList()
    private var swapChainImageFormat: Int = 0
    private lateinit var swapChainExtent: VkExtent2D

    private var renderPass: Long = 0
    private var descriptorPool: Long = 0
    private var descriptorSetLayout: Long = 0
    private var descriptorSets: List<Long> = emptyList()
    private var pipelineLayout: Long = 0
    private var graphicsPipeline: Long = 0

    private var vertexBuffer: Long = 0
    private var vertexBufferMemory: Long = 0

    private var indexBuffer: Long = 0
    private var indexBufferMemory: Long = 0

    private var uniformBuffers: List<Long> = emptyList()
    private var uniformBufferMemory: List<Long> = emptyList()

    private var commandPool: Long = 0
    private var commandBuffers: List<VkCommandBuffer> = emptyList()

    private var inFlightFrames: List<Frame> = emptyList()
    private val imagesInFlight: MutableMap<Int, Frame> = HashMap()
    private var currentFrame: Int = 0

    private var frameBufferResize: Boolean = false

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

        val title = javaClass.simpleName

        window = glfwCreateWindow(WIDTH, HEIGHT, title, NULL, NULL)

        if (window == NULL) {
            throw RuntimeException("Cannot create window")
        }

        // In Java, we don't really need a user pointer here, because
        // we can simply pass an instance method reference to glfwSetFramebufferSizeCallback
        // However, I will show you how can you pass a user pointer to glfw in Java just for learning purposes:
        // long userPointer = JNINativeInterface.NewGlobalRef(this);
        // glfwSetWindowUserPointer(window, userPointer);
        // Please notice that the reference must be freed manually with JNINativeInterface.nDeleteGlobalRef
        glfwSetFramebufferSizeCallback(window, this::frameBufferResizeCallback)
    }

    private fun frameBufferResizeCallback(window: Long, width: Int, height: Int) {
        // HelloTriangleApplication app = MemoryUtil.memGlobalRefToObject(glfwGetWindowUserPointer(window));
        // app.framebufferResize = true;
        frameBufferResize = true
    }

    private fun initVulkan() {
        createInstance()
        setupDebugMessenger()
        createSurface()
        pickPhysicalDevice()
        createLogicDevice()
        createCommandPool()
        createVertexBuffer()
        createIndexBuffer()
        createDescriptorSetLayout()
        createSwapChainObjects()
        createSyncObjects()
    }

    private fun mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
            drawFrame()
        }

        vkDeviceWaitIdle(device)
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
            val availableExtensions = VkExtensionProperties.malloc(extensionCount[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, extensionCount, availableExtensions)
            return availableExtensions
                    .map { it.extensionNameString() }
                    .containsAll(DEVICE_EXTENSIONS)
        }
    }

    private fun querySwapChainSupport(device: VkPhysicalDevice, stack: MemoryStack): SwapChainSupportDetails {
        val details = SwapChainSupportDetails()

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack)
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities)

        val count = stack.ints(0)
        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null)
        if (count[0] != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count[0], stack)
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
            val queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount[0], stack)
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

            val queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.size, stack)

            for (i in uniqueQueueFamilies.indices) {
                val queueCreateInfo = queueCreateInfos[i]
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i])
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f))
            }

            val deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack)
            val createInfo = VkDeviceCreateInfo.calloc(stack).apply {
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

        cleanupSwapChain()

        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null)

        vkDestroyBuffer(device, indexBuffer, null)
        vkFreeMemory(device, indexBufferMemory, null)

        vkDestroyBuffer(device, vertexBuffer, null)
        vkFreeMemory(device, vertexBufferMemory, null)

        inFlightFrames.forEach { frame ->
            vkDestroySemaphore(device, frame.renderFinishedSemaphore, null)
            vkDestroySemaphore(device, frame.imageAvailableSemaphore, null)
            vkDestroyFence(device, frame.fence, null)
        }
        imagesInFlight.clear()

        vkDestroyCommandPool(device, commandPool, null)

        vkDestroyDevice(device, null)

        if (ENABLE_VALIDATION_LAYERS) {
            destroyDebugUtilsMessengerEXT(instance, debugMessenger, null)
        }

        vkDestroySurfaceKHR(instance, surface, null)
        vkDestroyInstance(instance, null)
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun cleanupSwapChain() {
        uniformBuffers.forEach { vkDestroyBuffer(device, it, null) }
        uniformBufferMemory.forEach { vkFreeMemory(device, it, null) }
        vkDestroyDescriptorPool(device, descriptorPool, null)
        swapChainFrameBuffers.forEach { vkDestroyFramebuffer(device, it, null) }
        vkFreeCommandBuffers(device, commandPool, commandBuffers.asPointerBuffer())
        vkDestroyPipeline(device, graphicsPipeline, null)
        vkDestroyPipelineLayout(device, pipelineLayout, null)
        vkDestroyRenderPass(device, renderPass, null)
        swapChainImageViews.forEach { vkDestroyImageView(device, it, null) }
        vkDestroySwapchainKHR(device, swapChain, null)
    }

    private fun createInstance() {
        if (ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
            throw RuntimeException("Validation layers requested, but not available!")
        }

        MemoryStack.stackPush().use { stack ->
            val appInfo = VkApplicationInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                pApplicationName(stack.UTF8Safe("Hello Triangle."))
                applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                pEngineName(stack.UTF8Safe("No Engine"))
                engineVersion(VK_MAKE_VERSION(1, 0, 0))
                apiVersion(VK_API_VERSION_1_0)
            }

            val createInfo = VkInstanceCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                pApplicationInfo(appInfo)
                ppEnabledExtensionNames(getRequiredExtensions())
                if (ENABLE_VALIDATION_LAYERS) {
                    ppEnabledLayerNames(asPointBuffer(VALIDATION_LAYERS))
                    val debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
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
            val availableLayers = VkLayerProperties.malloc(layerCount[0], stack)
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
            return requiredExtensions.rewind()
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
            val createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
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
            val vertShaderSPIRV = compileShaderFile("shaders/21_shader_ubo.vert", ShakerKind.VERTEX_SHADER)
            val fragShaderSPIRV = compileShaderFile("shaders/21_shader_ubo.frag", ShakerKind.FRAGMENT_SHADER)

            if (vertShaderSPIRV == null || fragShaderSPIRV == null) {
                throw RuntimeException("Failed to compile shader.")
            }

            val vertShaderModule = createShaderModule(vertShaderSPIRV.bytecode)
            val fragShaderModule = createShaderModule(fragShaderSPIRV.bytecode)

            val entryPoint = stack.UTF8("main")

            val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack)

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

            // VERTEX STAGE
            val vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
            vertexInputInfo.pVertexBindingDescriptions(Vertex.getBindingDescription())
            vertexInputInfo.pVertexAttributeDescriptions(Vertex.getAttributeDescriptions())

            // ASSEMBLE STAGE
            val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                primitiveRestartEnable(false)
            }

            // VIEWPORT & SCISSOR
            val viewport = VkViewport.calloc(1, stack)
            viewport.x(0f)
            viewport.y(0f)
            viewport.width(swapChainExtent.width().toFloat())
            viewport.height(swapChainExtent.height().toFloat())
            viewport.minDepth(0f)
            viewport.maxDepth(1f)

            val scissor = VkRect2D.calloc(1, stack)
            scissor.offset(VkOffset2D.calloc(stack).set(0, 0))
            scissor.extent(swapChainExtent)

            val viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                pViewports(viewport)
                pScissors(scissor)
            }

            // RASTERIZATION STAGE
            val rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                depthClampEnable(false)
                rasterizerDiscardEnable(false)
                polygonMode(VK_POLYGON_MODE_FILL)
                lineWidth(1f)
                cullMode(VK_CULL_MODE_BACK_BIT)
                frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                depthBiasEnable(false)
            }

            // MULTISAMPLING
            val multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                sampleShadingEnable(false)
                rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            }

            // COLOR BLENDING
            val colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
            colorBlendAttachment.colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT
                    or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
            colorBlendAttachment.blendEnable(false)

            val colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                logicOpEnable(false)
                logicOp(VK_LOGIC_OP_COPY)
                pAttachments(colorBlendAttachment)
                blendConstants(stack.floats(0f, 0f, 0f, 0f))
            }

            // PIPELINE LAYOUT CREATION
            val pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            pipelineLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout))

            val pPipelineLayout = stack.longs(VK_NULL_HANDLE)

            if (vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create pipeline layout.")
            }

            pipelineLayout = pPipelineLayout[0]

            val pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
            pipelineInfo.pStages(shaderStages)
            pipelineInfo.pVertexInputState(vertexInputInfo)
            pipelineInfo.pVertexInputState(vertexInputInfo)
            pipelineInfo.pInputAssemblyState(inputAssembly)
            pipelineInfo.pViewportState(viewportState)
            pipelineInfo.pRasterizationState(rasterizer)
            pipelineInfo.pMultisampleState(multisampling)
            pipelineInfo.pColorBlendState(colorBlending)
            pipelineInfo.layout(pipelineLayout)
            pipelineInfo.renderPass(renderPass)
            pipelineInfo.subpass(0)
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE)
            pipelineInfo.basePipelineIndex(-1)

            val pGraphicsPipeline = stack.mallocLong(1)
            if (vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw RuntimeException("Failed to create graphics pipeline.")
            }

            graphicsPipeline = pGraphicsPipeline[0]

            vkDestroyShaderModule(device, vertShaderModule, null)
            vkDestroyShaderModule(device, fragShaderModule, null)

            vertShaderSPIRV.free()
            fragShaderSPIRV.free()
        }
    }

    private fun createFrameBuffers() {
        MemoryStack.stackPush().use { stack ->
            val attachments = stack.mallocLong(1)
            val pFrameBuffer = stack.mallocLong(1)

            val frameBufferInfo = VkFramebufferCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                renderPass(renderPass)
                width(swapChainExtent.width())
                height(swapChainExtent.height())
                layers(1)
            }

            swapChainFrameBuffers = swapChainImageViews.map { imageView ->
                attachments.put(0, imageView)
                frameBufferInfo.pAttachments(attachments)
                if (vkCreateFramebuffer(device, frameBufferInfo, null, pFrameBuffer) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create framebuffer.")
                }
                pFrameBuffer[0]
            }
        }
    }

    private fun createCommandPool() {
        MemoryStack.stackPush().use { stack ->
            val queueFamilyIndices = findQueueFamilies(physicalDevice)

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                queueFamilyIndex(queueFamilyIndices.graphicsFamily!!)
            }

            val pCommandPool = stack.mallocLong(1)

            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create command pool.")
            }

            commandPool = pCommandPool[0]
        }
    }

    private fun createCommandBuffers() {
        val commandBuffersCount = swapChainFrameBuffers.size

        MemoryStack.stackPush().use { stack ->
            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                commandPool(commandPool)
                level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                commandBufferCount(commandBuffersCount)
            }

            val pCommandBuffers = stack.mallocPointer(commandBuffersCount)

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate command buffers")
            }

            commandBuffers = pCommandBuffers.asIterable().map { VkCommandBuffer(it, device) }

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            }

            val renderPassInfo = VkRenderPassBeginInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                renderPass(renderPass)
                renderArea(VkRect2D.calloc(stack).apply {
                    offset(VkOffset2D.calloc(stack).set(0, 0))
                    extent(swapChainExtent)
                })
                val clearValues = VkClearValue.calloc(1, stack)
                clearValues.color().float32(stack.floats(0f, 0f, 0f, 1f))
                pClearValues(clearValues)
            }

            for (i in 0 until commandBuffersCount) {
                val commandBuffer = commandBuffers[i]
                val swapChainFrameBuffer = swapChainFrameBuffers[i]

                if (vkBeginCommandBuffer(commandBuffer, beginInfo) != VK_SUCCESS) {
                    throw RuntimeException("Failed to begin recording command buffer.")
                }
                renderPassInfo.framebuffer(swapChainFrameBuffer)

                // ======== BEGIN ===========
                vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE)

                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, graphicsPipeline)
                val vertexBuffers = stack.longs(vertexBuffer)
                val offsets = stack.longs(0)
                vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets)
                vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT16)
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipelineLayout, 0, stack.longs(descriptorSets[i]), null)
                vkCmdDrawIndexed(commandBuffer, INDICES.size, 1, 0, 0, 0)

                vkCmdEndRenderPass(commandBuffer)
                // ======== END ===========

                if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
                    throw RuntimeException("Failed to record command buffer.")
                }
            }
        }
    }

    private fun createDescriptorPool() {
        MemoryStack.stackPush().use { stack ->
            val poolSize = VkDescriptorPoolSize.calloc(1, stack)
            poolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            poolSize.descriptorCount(swapChainImages.size)

            val poolInfo = VkDescriptorPoolCreateInfo.calloc().apply {
                sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                pPoolSizes(poolSize)
                maxSets(swapChainImages.size)
            }

            val pDescriptorPool = stack.mallocLong(1)

            if (vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor info.")
            }

            descriptorPool = pDescriptorPool[0]
        }
    }

    private fun createDescriptorSets() {
        MemoryStack.stackPush().use { stack ->
            val layouts = stack.mallocLong(swapChainImages.size)
            for (i in 0 until layouts.capacity()) {
                layouts.put(i, descriptorSetLayout)
            }

            val allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                descriptorPool(descriptorPool)
                pSetLayouts(layouts)
            }

            val pDescriptorSets = stack.mallocLong(swapChainImages.size)

            if (vkAllocateDescriptorSets(device, allocInfo, pDescriptorSets) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate descriptor sets.")
            }

            val bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
            bufferInfo.offset(0)
            bufferInfo.range(UniformBufferObject.SIZEOF)

            val descriptorWrite = VkWriteDescriptorSet.calloc(1, stack)
            descriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            descriptorWrite.dstBinding(0)
            descriptorWrite.dstArrayElement(0)
            descriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            descriptorWrite.descriptorCount(1)
            descriptorWrite.pBufferInfo(bufferInfo)

            descriptorSets = pDescriptorSets.asIterable().mapIndexed { index, descriptorSet ->
                bufferInfo.buffer(uniformBuffers[index])
                descriptorWrite.dstSet(descriptorSet)
                vkUpdateDescriptorSets(device, descriptorWrite, null)
                descriptorSet
            }
        }
    }

    private fun createBuffer(size: Long, usage: Int, properties: Int, pBuffer: LongBuffer, pBufferMemory: LongBuffer) {
        MemoryStack.stackPush().use { stack ->
            val bufferInfo = VkBufferCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                size(size)
                usage(usage)
                sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            }

            if (vkCreateBuffer(device, bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw RuntimeException("Failed to create buffer")
            }

            val memRequirements = VkMemoryRequirements.malloc(stack)
            vkGetBufferMemoryRequirements(device, pBuffer[0], memRequirements)

            val allocInfo = VkMemoryAllocateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                allocationSize(memRequirements.size())
                memoryTypeIndex(findMemoryType(memRequirements.memoryTypeBits(), properties))
            }

            if (vkAllocateMemory(device, allocInfo, null, pBufferMemory) != VK_SUCCESS) {
                throw RuntimeException("Failed to allocate buffer memory")
            }

            vkBindBufferMemory(device, pBuffer[0], pBufferMemory[0], 0)
        }
    }

    private fun createVertexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = (Vertex.SIZEOF * VERTICES.size).toLong()
            val pBuffer = stack.mallocLong(1)
            val pBufferMemory = stack.mallocLong(1)
            createBuffer(
                    bufferSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer,
                    pBufferMemory
            )

            val stagingBuffer = pBuffer[0]
            val stagingBufferMemory = pBufferMemory[0]

            val data = stack.mallocPointer(1)

            vkMapMemory(device, stagingBufferMemory, 0, bufferSize, 0, data)
            memcpy(data.getByteBuffer(0, bufferSize.toInt()), VERTICES)
            vkUnmapMemory(device, stagingBufferMemory)

            createBuffer(
                    bufferSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT,
                    pBuffer,
                    pBufferMemory
            )

            vertexBuffer = pBuffer[0]
            vertexBufferMemory = pBufferMemory[0]

            copyBuffer(stagingBuffer, vertexBuffer, bufferSize)

            vkDestroyBuffer(device, stagingBuffer, null)
            vkFreeMemory(device, stagingBufferMemory, null)
        }
    }

    private fun createIndexBuffer() {
        MemoryStack.stackPush().use { stack ->
            val bufferSize = (2 * INDICES.size).toLong()
            val pBuffer = stack.mallocLong(1)
            val pBufferMemory = stack.mallocLong(1)
            createBuffer(
                    bufferSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer,
                    pBufferMemory
            )

            val stagingBuffer = pBuffer[0]
            val stagingBufferMemory = pBufferMemory[0]

            val data = stack.mallocPointer(1)

            vkMapMemory(device, stagingBufferMemory, 0, bufferSize, 0, data)
            memcpy(data.getByteBuffer(0, bufferSize.toInt()), INDICES)
            vkUnmapMemory(device, stagingBufferMemory)

            createBuffer(
                    bufferSize,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT or VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT,
                    pBuffer,
                    pBufferMemory
            )

            indexBuffer = pBuffer[0]
            indexBufferMemory = pBufferMemory[0]

            copyBuffer(stagingBuffer, indexBuffer, bufferSize)

            vkDestroyBuffer(device, stagingBuffer, null)
            vkFreeMemory(device, stagingBufferMemory, null)
        }
    }

    private fun createUniformBuffers() {
        MemoryStack.stackPush().use { stack ->
            val uniformBuffers = mutableListOf<Long>()
            val uniformBufferMemory = mutableListOf<Long>()
            val pBuffer = stack.mallocLong(1)
            val pBufferMemory = stack.mallocLong(1)

            repeat(swapChainImages.size) {
                createBuffer(
                        UniformBufferObject.SIZEOF,
                        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        pBuffer,
                        pBufferMemory
                )
                uniformBuffers.add(pBuffer[0])
                uniformBufferMemory.add(pBufferMemory[0])
            }

            this.uniformBuffers = uniformBuffers
            this.uniformBufferMemory = uniformBufferMemory
        }
    }

    private fun copyBuffer(srcBuffer: Long, dstBuffer: Long, size: Long) {
        MemoryStack.stackPush().use { stack ->

            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                commandPool(commandPool)
                commandBufferCount(1)
            }

            val pCommandBuffer = stack.mallocPointer(1)
            vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer)
            val commandBuffer = VkCommandBuffer(pCommandBuffer[0], device)

            val beginInfo = VkCommandBufferBeginInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            }

            vkBeginCommandBuffer(commandBuffer, beginInfo)
            val copyRegion = VkBufferCopy.calloc(1, stack)
            copyRegion.size(size)
            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion)
            vkEndCommandBuffer(commandBuffer)

            val submitInfo = VkSubmitInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                pCommandBuffers(pCommandBuffer)
            }

            if (vkQueueSubmit(graphicsQueue, submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
                throw RuntimeException("Failed to submit copy command buffer")
            }

            vkQueueWaitIdle(graphicsQueue)
            vkFreeCommandBuffers(device, commandPool, pCommandBuffer)
        }
    }

    private fun memcpy(buffer: ByteBuffer, vertices: Array<Vertex>) {
        vertices.forEach {
            buffer.putFloat(it.pos.x())
            buffer.putFloat(it.pos.y())

            buffer.putFloat(it.color.x())
            buffer.putFloat(it.color.y())
            buffer.putFloat(it.color.z())
        }
    }

    private fun memcpy(buffer: ByteBuffer, shorts: ShortArray) {
        shorts.forEach {
            buffer.putShort(it)
        }
        buffer.rewind()
    }

    private fun memcpy(buffer: ByteBuffer, ubo: UniformBufferObject) {
        val mat4size = 16 * 4
        ubo.model.get(0, buffer)
        ubo.view.get(alignas(mat4size, alignof(ubo.view)), buffer)
        ubo.proj.get(alignas(mat4size * 2, alignof(ubo.view)), buffer)
    }

    private fun findMemoryType(typeFilter: Int, properties: Int): Int {
        val memProperties = VkPhysicalDeviceMemoryProperties.malloc()
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties)

        for (i in 0 until memProperties.memoryTypeCount()) {
            if ((typeFilter and 1 shl i) != 0 &&
                    (memProperties.memoryTypes(i).propertyFlags() and properties) == properties) {
                return i
            }
        }

        throw RuntimeException("Failed to find suitable memory type.")
    }

    private fun createSyncObjects() {
        MemoryStack.stackPush().use { stack ->
            val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)

            val fenceInfo = VkFenceCreateInfo.calloc(stack)
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT)

            val pImageAvailableSemaphore = stack.mallocLong(1)
            val pRenderFinishedSemaphore = stack.mallocLong(1)
            val pFence = stack.mallocLong(1)

            inFlightFrames = (0 until MAX_FRAMES_IN_FLIGHT).map { index ->
                if (vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {
                    throw RuntimeException("Failed to create synchronization objects for the frame $index")
                }
                Frame(pImageAvailableSemaphore[0], pRenderFinishedSemaphore[0], pFence[0])
            }
        }
    }

    private fun updateUniformBuffer(currentImage: Int) {
        MemoryStack.stackPush().use { stack ->
            val ubo = UniformBufferObject().apply {
                model.rotate((glfwGetTime() * Math.toRadians(90.0)).toFloat(), 0f, 0f, 1f)
                view.lookAt(2f, 2f, 2f, 0f, 0f, 0f, 0f, 0f, 1f)
                proj.perspective(
                        Math.toRadians(45.0).toFloat(),
                        swapChainExtent.width().toFloat() / swapChainExtent.height(),
                        0.1f,
                        10f)
                proj.m11(proj.m11() * -1)
            }

            val data = stack.mallocPointer(1)
            vkMapMemory(device, uniformBufferMemory[currentImage], 0, UniformBufferObject.SIZEOF, 0, data)
            memcpy(data.getByteBuffer(0, UniformBufferObject.SIZEOF.toInt()), ubo)
            vkUnmapMemory(device, uniformBufferMemory[currentImage])
        }
    }

    private fun drawFrame() {
        MemoryStack.stackPush().use { stack ->
            val thisFrame = inFlightFrames[currentFrame]

            vkWaitForFences(device, thisFrame.pFence(), true, UINT64_MAX)

            val pImageIndex = stack.mallocInt(1)

            val acquireImageResult = vkAcquireNextImageKHR(device, swapChain, UINT64_MAX,
                    thisFrame.imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex)
            if (acquireImageResult == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapChain()
                return
            } else if (acquireImageResult != VK_SUCCESS) {
                throw RuntimeException("Cannot get image")
            }

            val imageIndex = pImageIndex[0]

            updateUniformBuffer(imageIndex)

            if (imagesInFlight.containsKey(imageIndex)) {
                vkWaitForFences(device, imagesInFlight[imageIndex]!!.fence, true, UINT64_MAX)
            }

            imagesInFlight[imageIndex] = thisFrame

            val submitInfo = VkSubmitInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)

                waitSemaphoreCount(1)
                pWaitSemaphores(thisFrame.pImageAvailableSemaphore())
                pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))

                pSignalSemaphores(thisFrame.pRenderFinishedSemaphore())

                pCommandBuffers(stack.pointers(commandBuffers[imageIndex]))
            }

            vkResetFences(device, thisFrame.pFence())

            if (vkQueueSubmit(graphicsQueue, submitInfo, thisFrame.fence) != VK_SUCCESS) {
                vkResetFences(device, thisFrame.pFence())
                throw RuntimeException("Failed to submit draw command buffer")
            }

            val presentInfo = VkPresentInfoKHR.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)

                pWaitSemaphores(thisFrame.pRenderFinishedSemaphore())

                swapchainCount(1)
                pSwapchains(stack.longs(swapChain))

                pImageIndices(pImageIndex)
            }

            val queuePresentResult = vkQueuePresentKHR(presentQueue, presentInfo)
            if (queuePresentResult == VK_ERROR_OUT_OF_DATE_KHR || queuePresentResult == VK_SUBOPTIMAL_KHR || frameBufferResize) {
                frameBufferResize = false
                recreateSwapChain()
            } else if (queuePresentResult != VK_SUCCESS) {
                throw RuntimeException("Failed to present swap chain image.")
            }

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT
        }
    }

    private fun createRenderPass() {
        MemoryStack.stackPush().use { stack ->
            val colorAttachment = VkAttachmentDescription.calloc(1, stack)
            colorAttachment.format(swapChainImageFormat)
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT)
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)

            val colorAttachmentRef = VkAttachmentReference.calloc(1, stack)
            colorAttachmentRef.attachment(0)
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)

            val subpass = VkSubpassDescription.calloc(1, stack)
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
            subpass.colorAttachmentCount(1)
            subpass.pColorAttachments(colorAttachmentRef)

            val dependency = VkSubpassDependency.calloc(1, stack)
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL)
            dependency.dstSubpass(0)
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            dependency.srcAccessMask(0)
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT or VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)

            val renderPassInfo = VkRenderPassCreateInfo.calloc(stack).apply {
                sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                pAttachments(colorAttachment)
                pSubpasses(subpass)
                pDependencies(dependency)
            }

            val renderPasses = stack.mallocLong(1)
            if (vkCreateRenderPass(device, renderPassInfo, null, renderPasses) != VK_SUCCESS) {
                throw RuntimeException("Failed to create render pass.")
            }

            renderPass = renderPasses[0]
        }
    }

    private fun createDescriptorSetLayout() {
        MemoryStack.stackPush().use { stack ->
            val uboLayoutBinding = VkDescriptorSetLayoutBinding.calloc(1, stack)
            uboLayoutBinding.binding(0)
            uboLayoutBinding.descriptorCount(1)
            uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            uboLayoutBinding.pImmutableSamplers(null)
            uboLayoutBinding.stageFlags(VK_SHADER_STAGE_VERTEX_BIT)

            val layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            layoutInfo.pBindings(uboLayoutBinding)

            val pDescriptorSetLayout = stack.mallocLong(1)

            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw RuntimeException("Failed to create descriptor set layout.")
            }
            descriptorSetLayout = pDescriptorSetLayout[0]
        }
    }

    private fun createShaderModule(spirvCode: ByteBuffer): Long {
        MemoryStack.stackPush().use { stack ->
            val createInfo = VkShaderModuleCreateInfo.calloc(stack).apply {
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
                val createInfo = VkImageViewCreateInfo.calloc(stack).apply {
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

            val createInfo = VkSwapchainCreateInfoKHR.calloc(stack).apply {
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

            swapChain = pSwapChain[0]
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

    private fun recreateSwapChain() {
        MemoryStack.stackPush().use { stack ->
            val width = stack.ints(0)
            val height = stack.ints(0)

            while (width[0] == 0 && height[0] == 0) {
                glfwGetFramebufferSize(window, width, height)
                glfwWaitEvents()
            }
        }

        vkDeviceWaitIdle(device)
        cleanupSwapChain()
        createSwapChainObjects()
    }

    private fun createSwapChainObjects() {
        createSwapChain()
        createImageViews()
        createRenderPass()
        createGraphicsPipeline()
        createFrameBuffers()
        createUniformBuffers()
        createDescriptorPool()
        createDescriptorSets()
        createCommandBuffers()
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

        val stack = MemoryStack.stackGet()
        val width = stack.ints(0)
        val height = stack.ints(0)
        glfwGetFramebufferSize(window, width, height)
        val actualExtent = VkExtent2D.malloc().set(width[0], height[0])

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
        private const val UINT64_MAX = -0x1L

        private const val WIDTH = 800
        private const val HEIGHT = 600

        private const val MAX_FRAMES_IN_FLIGHT = 2

        private val ENABLE_VALIDATION_LAYERS = true //DEBUG.get(true)
        private val VALIDATION_LAYERS = HashSet<String>().apply {
            if (ENABLE_VALIDATION_LAYERS) {
                add("VK_LAYER_KHRONOS_validation")
//                add("VK_LAYER_LUNARG_standard_validation")
            }
        }

        private val DEVICE_EXTENSIONS = setOf(VK_KHR_SWAPCHAIN_EXTENSION_NAME)

        private val VERTICES = arrayOf(
                Vertex(Vector2f(-0.5f, -0.5f), Vector3f(1.0f, 0.0f, 0.0f)),
                Vertex(Vector2f( 0.5f, -0.5f), Vector3f(0.0f, 1.0f, 0.0f)),
                Vertex(Vector2f( 0.5f,  0.5f), Vector3f(0.0f, 0.0f, 1.0f)),
                Vertex(Vector2f(-0.5f,  0.5f), Vector3f(1.0f, 1.0f, 1.0f))
        )

        private val INDICES = shortArrayOf(
                0, 1, 2, 2, 3, 0
        )

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

internal fun LongBuffer.asIterable(): Iterable<Long> {
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

internal fun List<Pointer>.asPointerBuffer(): PointerBuffer {
    val stack = MemoryStack.stackGet()
    val buffer = stack.mallocPointer(size)
    forEach { buffer.put(it) }
    return buffer.rewind()
}

fun main() {
    HelloTriangleApplication().run()
}
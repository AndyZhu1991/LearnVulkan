package andy

import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkInstanceCreateInfo

class HelloTriangleApplication {

    private var window: Long = 0
    private lateinit var instance: VkInstance

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
    }

    private fun mainLoop() {
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents()
        }
    }

    private fun cleanup() {
        vkDestroyInstance(instance, null)
        glfwDestroyWindow(window)
        glfwTerminate()
    }

    private fun createInstance() {
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
                ppEnabledExtensionNames(glfwGetRequiredInstanceExtensions())
                ppEnabledLayerNames(null)
            }

            val instancePtr = stack.mallocPointer(1)
            if (vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw RuntimeException("Failed to create instance")
            }

            instance = VkInstance(instancePtr[0], createInfo)
        }
    }

    companion object {
        private const val WIDTH = 800;
        private const val HEIGHT = 600;
    }
}

fun main(args: Array<String>) {
    HelloTriangleApplication().run()
}
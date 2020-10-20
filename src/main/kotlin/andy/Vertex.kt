package andy

import org.joml.Vector2f
import org.joml.Vector2fc
import org.joml.Vector3f
import org.joml.Vector3fc
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription

class Vertex(private val pos: Vector2fc, private val color: Vector3fc) {

    companion object {

        private const val SIZEOF_FLOAT = 4
        private const val SIZEOF = (2 + 3) * SIZEOF_FLOAT
        private const val OFFSETOF_POS = 0
        private const val OFFSETOF_COLOR = 2 * SIZEOF_FLOAT

        fun getBindingDescription(): VkVertexInputBindingDescription.Buffer {
            val bindingDescription = VkVertexInputBindingDescription.callocStack(1)
            bindingDescription.binding(0)
            bindingDescription.stride(SIZEOF)
            bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            return bindingDescription
        }

        fun getAttributeDescriptions(): VkVertexInputAttributeDescription.Buffer {
            val attributeDescriptions = VkVertexInputAttributeDescription.callocStack(2)

            // Position
            attributeDescriptions[0].apply {
                binding(0)
                location(0)
                format(VK_FORMAT_R32G32_SFLOAT)
                offset(OFFSETOF_POS)
            }

            // Color
            attributeDescriptions[1].apply {
                binding(0)
                location(1)
                format(VK_FORMAT_R32G32B32_SFLOAT)
                offset(OFFSETOF_COLOR)
            }

            return attributeDescriptions
        }

        private val VERTICES = arrayOf(
                Vertex(Vector2f(0.0f, -0.5f), Vector3f(1.0f, 0.0f, 0.0f)),
                Vertex(Vector2f(0.5f,  0.5f), Vector3f(0.0f, 1.0f, 0.0f)),
                Vertex(Vector2f(-0.5f, 0.5f), Vector3f(0.0f, 0.0f, 1.0f))
        )
    }
}
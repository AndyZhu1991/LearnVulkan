package andy

import org.joml.Matrix4f

class UniformBufferObject {

    val model = Matrix4f()
    val view = Matrix4f()
    val proj = Matrix4f()

    companion object {
        const val SIZEOF = (3 * 4 * 4 * 4).toLong()
    }
}
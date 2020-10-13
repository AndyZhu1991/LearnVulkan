package andy

import org.lwjgl.system.MemoryStack.stackGet

class Frame(
        val imageAvailableSemaphore: Long,
        val renderFinishedSemaphore: Long,
        val fence: Long
) {

    fun pImageAvailableSemaphore() = stackGet().longs(imageAvailableSemaphore)

    fun pRenderFinishedSemaphore() = stackGet().longs(renderFinishedSemaphore)

    fun pFence() = stackGet().longs(fence)
}
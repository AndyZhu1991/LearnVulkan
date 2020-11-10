package andy

import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector3f
import org.joml.Vector4f

internal fun alignas(offset: Int, alignment: Int): Int {
    return if (offset % alignment == 0) offset else ((offset - 1) or (alignment - 1)) + 1
}

internal fun alignof(obj: Any): Int {
    return SIZEOF_CACHE.getOrDefault(obj::class.java, Int.SIZE_BYTES)
}

internal fun sizeof(obj: Any): Int {
    return SIZEOF_CACHE.getOrDefault(obj::class.java, 0)
}

private val SIZEOF_CACHE: Map<Class<*>, Int> = mutableMapOf<Class<*>, Int>().apply {
    put(Byte::class.java, Byte.SIZE_BYTES)
    put(Char::class.java, Char.SIZE_BYTES)
    put(Short::class.java, Short.SIZE_BYTES)
    put(Int::class.java, Int.SIZE_BYTES)
    put(Float::class.java, 4)
    put(Long::class.java, Long.SIZE_BYTES)
    put(Double::class.java, 8)

    put(Vector2f::class.java, 2 * 4)
    put(Vector3f::class.java, 3 * 4)
    put(Vector4f::class.java, 4 * 4)

    put(Matrix4f::class.java, get(Vector4f::class.java)!! * 4)
}
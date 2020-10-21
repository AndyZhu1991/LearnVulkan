package andy

import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.NativeResource
import org.lwjgl.util.shaderc.Shaderc.*
import java.lang.ClassLoader.getSystemClassLoader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths


enum class ShakerKind(val kind: Int) {
    VERTEX_SHADER(shaderc_glsl_vertex_shader),
    GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
    FRAGMENT_SHADER(shaderc_glsl_fragment_shader)
}

class SPIRV(
        private val handle: Long,
        val bytecode: ByteBuffer
) : NativeResource {

    override fun free() {
        shaderc_result_release(handle)
//        bytecode = null
    }
}

fun compileShaderFile(shaderFile: String, shaderKind: ShakerKind): SPIRV? {
    val absolutePath = getSystemClassLoader().getResource(shaderFile)?.toExternalForm()
    return if (absolutePath != null) {
        compileShaderAbsoluteFile(absolutePath, shaderKind)
    } else {
        null
    }
}

fun compileShaderAbsoluteFile(shaderFile: String, shaderKind: ShakerKind): SPIRV? {
    return try {
        val source = String(Files.readAllBytes(Paths.get(URI(shaderFile))))
        compileShader(shaderFile, source, shaderKind)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun compileShader(fileName: String, source: String, shaderKind: ShakerKind): SPIRV {
    val compiler = shaderc_compiler_initialize()

    if (compiler == NULL) {
        throw RuntimeException("Failed to create shader compiler.")
    }

    val result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, fileName, "main", NULL)

    if (result == NULL) {
        throw RuntimeException("Failed to compile shader $fileName into SPIR-V")
    }

    if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
        throw RuntimeException("Failed to compile shader " + fileName + " into SPIR-V:\n " + shaderc_result_get_error_message(result))
    }

    shaderc_compiler_release(compiler)

    return SPIRV(result, shaderc_result_get_bytes(result)!!)
}
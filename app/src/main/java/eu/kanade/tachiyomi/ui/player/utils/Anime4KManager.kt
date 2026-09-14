package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Anime4K Manager
 * Manages GLSL shaders for real-time anime upscaling
 */
class Anime4KManager(private val context: Context) {

    companion object {
        private const val SHADER_DIR = "shaders"
    }

    // Shader quality levels
    enum class Quality(val suffix: String) {
        FAST("S"),
        BALANCED("M"),
        HIGH("L"),
    }

    // Anime4K modes
    enum class Mode {
        OFF,
        A,
        B,
        C,
        A_PLUS,
        B_PLUS,
        C_PLUS,
    }

    private var shaderDir: File? = null
    private var isInitialized = false

    /**
     * Initialize: copy shaders from assets to internal storage
     * This must be called and complete successfully before using getShaderChain()
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            return true
        }

        return try {
            // Create shader directory
            shaderDir = File(context.filesDir, SHADER_DIR)
            if (!shaderDir!!.exists()) {
                val created = shaderDir!!.mkdirs()
                if (!created) {
                    return false
                }
            }

            // List and copy all shader files from assets
            val shaderFiles = context.assets.list(SHADER_DIR) ?: emptyArray()

            for (fileName in shaderFiles) {
                if (fileName.endsWith(".glsl")) {
                    copyShaderFromAssets(fileName)
                }
            }

            isInitialized = true
            true
        } catch (e: Exception) {
            isInitialized = false
            false
        }
    }

    private fun copyShaderFromAssets(fileName: String): Boolean {
        val destFile = File(shaderDir, fileName)

        // Skip if file already exists and is valid
        if (destFile.exists() && destFile.length() > 0) {
            return false
        }

        return try {
            context.assets.open("$SHADER_DIR/$fileName").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get shader chain for the specified mode and quality
     * Returns empty string if mode is OFF or initialization failed
     */
    fun getShaderChain(mode: Mode, quality: Quality): String {
        if (mode == Mode.OFF) {
            return ""
        }

        if (!isInitialized) {
            initialize() // Try to initialize if not yet done
        }

        if (shaderDir == null || !shaderDir!!.exists()) {
            return ""
        }

        val shaders = mutableListOf<String>()
        val q = quality.suffix

        // Always add Clamp_Highlights (prevent ringing)
        shaders.add(getShaderPath("Anime4K_Clamp_Highlights.glsl"))

        // Add shaders based on mode
        when (mode) {
            Mode.A -> {
                // Mode A: Restore -> Upscale -> Upscale
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.B -> {
                // Mode B: Restore_Soft -> Upscale -> Upscale
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.C -> {
                // Mode C: Upscale_Denoise -> Upscale
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.A_PLUS -> {
                // Mode A+A: Restore -> Upscale -> Restore -> Upscale
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.B_PLUS -> {
                // Mode B+B: Restore_Soft -> Upscale -> Restore_Soft -> Upscale
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_Soft_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.C_PLUS -> {
                // Mode C+A: Upscale_Denoise -> Restore -> Upscale
                shaders.add(getShaderPath("Anime4K_Upscale_Denoise_CNN_x2_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_AutoDownscalePre_x2.glsl"))
                shaders.add(getShaderPath("Anime4K_Restore_CNN_$q.glsl"))
                shaders.add(getShaderPath("Anime4K_Upscale_CNN_x2_$q.glsl"))
            }
            Mode.OFF -> {}
        }

        // Validate that all shader files exist
        val missingShaders = shaders.filter { path ->
            !File(path).exists()
        }

        if (missingShaders.isNotEmpty()) {
            return ""
        }

        // Join with colon separator
        return shaders.joinToString(":")
    }

    private fun getShaderPath(fileName: String): String {
        return File(shaderDir, fileName).absolutePath
    }
}

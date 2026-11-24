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

        // Parametros de AniZen Lite por nivel:
        // WIDE, STEEP, DARK, MAXD, BLACK, THICK, AA, AAAMT, FLAT, SAT, CON
        private fun liteParams(q: Quality): Array<String> = when (q) {
            Quality.FAST -> arrayOf("0", "0.50", "0.60", "0.22", "0.00", "0.00", "0", "0.00", "0.40", "0.10", "0.05")
            Quality.BALANCED -> arrayOf("1", "0.80", "0.90", "0.22", "0.00", "0.00", "0", "0.00", "0.60", "0.16", "0.08")
            Quality.HIGH -> arrayOf("1", "1.00", "1.70", "0.32", "0.35", "0.70", "1", "0.85", "0.80", "0.22", "0.12")
        }

        private fun liteFileName(q: Quality) = "AniZen_Lite_${q.suffix}.glsl"

        // Shader de un solo paso (hook OUTPUT). Se escribe en filesDir/shaders al iniciar.
        private const val LITE_TEMPLATE = """
//!DESC AniZen Lite
//!HOOK OUTPUT
//!BIND HOOKED

// AniZen Lite: un solo paso, sin redes neuronales.
// 1) Aplana el ruido/bloques dentro de las zonas de color (respeta los bordes).
// 2) Afila los bordes: empuja cada pixel hacia el lado oscuro o claro de su entorno
//    (sin pasarse del maximo/minimo local, asi que no hay halos).
// 3) Suaviza los dientes de sierra a lo largo de los bordes y engrosa un poco las lineas.
// 4) Oscurece los contornos y aviva el color con una curva suave.

#define AZ_WIDE @WIDE@
#define AZ_STEEP @STEEP@
#define AZ_DARK_GAIN @DARK@
#define AZ_MAX_DARK @MAXD@
#define AZ_BLACK @BLACK@
#define AZ_THICK @THICK@
#define AZ_AA @AA@
#define AZ_AA_AMOUNT @AAAMT@
#define AZ_FLATTEN @FLAT@
#define AZ_VIBRANCE @SAT@
#define AZ_CONTRAST @CON@

float az_luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

vec4 hook() {
    vec4 c0 = HOOKED_tex(HOOKED_pos);
    vec3 cn = HOOKED_texOff(vec2(0.0, -1.0)).rgb;
    vec3 cs = HOOKED_texOff(vec2(0.0, 1.0)).rgb;
    vec3 ce = HOOKED_texOff(vec2(1.0, 0.0)).rgb;
    vec3 cw = HOOKED_texOff(vec2(-1.0, 0.0)).rgb;

    float lc = az_luma(c0.rgb);
    float ln = az_luma(cn);
    float ls = az_luma(cs);
    float le = az_luma(ce);
    float lw = az_luma(cw);

    // 1) Suavizado que respeta bordes: solo mezcla vecinos parecidos.
    float wn = 1.0 - smoothstep(0.015, 0.07, abs(ln - lc));
    float ws = 1.0 - smoothstep(0.015, 0.07, abs(ls - lc));
    float we = 1.0 - smoothstep(0.015, 0.07, abs(le - lc));
    float ww = 1.0 - smoothstep(0.015, 0.07, abs(lw - lc));
    vec3 smoothc = (c0.rgb + wn * cn + ws * cs + we * ce + ww * cw) / (1.0 + wn + ws + we + ww);
    vec3 rgb = mix(c0.rgb, smoothc, AZ_FLATTEN);

#if AZ_AA
    // Antidientes de sierra: promedia a lo largo del borde (nunca a traves de el).
    vec2 grad = vec2(le - lw, ls - ln);
    float glen = length(grad);
    vec2 tdir = vec2(-grad.y, grad.x) / max(glen, 0.001);
    float edgeAmt = smoothstep(0.03, 0.12, glen);
    vec3 ta = HOOKED_texOff(tdir * 1.5).rgb;
    vec3 tb = HOOKED_texOff(tdir * -1.5).rgb;
    vec3 along = (2.0 * rgb + ta + tb) * 0.25;
    rgb = mix(rgb, along, AZ_AA_AMOUNT * edgeAmt);
#endif

    float lf = az_luma(rgb);

    // Entorno: minimo, maximo y media.
    float mn = min(min(min(ln, ls), min(le, lw)), lf);
    float mx = max(max(max(ln, ls), max(le, lw)), lf);
    float avg = (ln + ls + le + lw) * 0.25;
#if AZ_WIDE
    float l2n = az_luma(HOOKED_texOff(vec2(0.0, -2.0)).rgb);
    float l2s = az_luma(HOOKED_texOff(vec2(0.0, 2.0)).rgb);
    float l2e = az_luma(HOOKED_texOff(vec2(2.0, 0.0)).rgb);
    float l2w = az_luma(HOOKED_texOff(vec2(-2.0, 0.0)).rgb);
    mn = min(mn, min(min(l2n, l2s), min(l2e, l2w)));
    mx = max(mx, max(max(l2n, l2s), max(l2e, l2w)));
    // En lineas gruesas el entorno cercano tambien es oscuro: usa el mas claro.
    avg = max(avg, (l2n + l2s + l2e + l2w) * 0.25);
#endif
    float range = mx - mn;

    // Grosor: un pixel pegado a una linea oscura se acerca a ella (la linea se ensancha).
    float nearMin = min(min(ln, ls), min(le, lw));
    float lt = lf - AZ_THICK * smoothstep(0.10, 0.30, lf - nearMin) * max(lf - nearMin, 0.0);

    // 2) Afilado acotado: curva S sobre la posicion del pixel dentro de [mn, mx].
    float s = (lt - mn) / max(range, 0.001);
    float gate = smoothstep(0.05, 0.18, range);
    float steep = smoothstep(0.22, 0.78, s);
    float target = mn + mix(s, steep, AZ_STEEP * gate) * range;

    // 3) Contornos mas oscuros: los pixeles mas oscuros que su entorno bajan un poco mas.
    float d = min(target - avg, 0.0);
    d *= smoothstep(0.012, 0.045, abs(d));
    target = max(target + max(d * AZ_DARK_GAIN, -AZ_MAX_DARK), 0.0);

    // Lineas bien negras: lo que queda claramente mas oscuro que su entorno baja hacia el negro.
    target *= 1.0 - AZ_BLACK * smoothstep(0.10, 0.30, avg - target);

    if (target < lf) {
        rgb = rgb * (target / max(lf, 0.0005));
    } else {
        rgb = rgb + (target - lf);
    }

    // Vibrance: sube mas la saturacion de los colores apagados.
    float lum = az_luma(rgb);
    float sat = max(rgb.r, max(rgb.g, rgb.b)) - min(rgb.r, min(rgb.g, rgb.b));
    rgb = mix(vec3(lum), rgb, 1.0 + AZ_VIBRANCE * (1.0 - sat));
    rgb = clamp(rgb, 0.0, 1.0);

    // Curva S suave para el contraste.
    vec3 curve = rgb * rgb * (3.0 - 2.0 * rgb);
    rgb = mix(rgb, curve, AZ_CONTRAST);

    return vec4(rgb, c0.a);
}
"""
    }

    // Shader quality levels
    enum class Quality(val suffix: String) {
        FAST("S"),
        BALANCED("M"),
        HIGH("L")
    }

    // Anime4K modes
    enum class Mode {
        OFF,
        LITE, // AniZen Lite: un solo paso, muy ligero
        A,
        B,
        C,
        A_PLUS,
        B_PLUS,
        C_PLUS
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

            writeLiteShaders()

            isInitialized = true
            true
        } catch (e: Exception) {
            isInitialized = false
            false
        }
    }

    /**
     * Genera los 3 niveles de AniZen Lite. Siempre se reescriben, asi cualquier ajuste
     * del shader se aplica al actualizar la app (los de Anime4K si se saltan si ya existen).
     */
    private fun writeLiteShaders() {
        for (q in Quality.entries) {
            try {
                var text = LITE_TEMPLATE.trimStart('\n')
                val values = liteParams(q)
                val names = arrayOf(
                    "@WIDE@", "@STEEP@", "@DARK@", "@MAXD@", "@BLACK@", "@THICK@",
                    "@AA@", "@AAAMT@", "@FLAT@", "@SAT@", "@CON@",
                )
                for (i in names.indices) {
                    text = text.replace(names[i], values[i])
                }
                File(shaderDir, liteFileName(q)).writeText(text)
            } catch (e: Exception) {
                // Si falla, el modo Lite simplemente no se activa.
            }
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

        // AniZen Lite: un solo archivo, sin Clamp_Highlights ni redes neuronales
        if (mode == Mode.LITE) {
            val lite = File(shaderDir, liteFileName(quality))
            return if (lite.exists() && lite.length() > 0) lite.absolutePath else ""
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
            Mode.OFF, Mode.LITE -> {}
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

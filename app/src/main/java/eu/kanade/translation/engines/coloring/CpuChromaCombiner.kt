package eu.kanade.translation.engines.coloring

import kotlin.math.abs
import kotlin.math.pow

/**
 * CPU twin of AgslChromaCombiner's Variant C combine (the user's stage3_merge_experiment formula):
 * Rec.601 gray -> smoothstep(0.12, 0.45) ink protection -> strength-scaled L mix; a/b from the work
 * layer; output clamped to [0,1]. Lab conversion uses kornia-pinned helpers, which
 * are formula-equivalent to the AGSL helpers, so both paths compute the same math on different
 * hardware — this is not a rendered-bitmap parity claim.
 */
internal object CpuChromaCombiner {

    fun merge(origRgb01: FloatArray, coloredRgb01: FloatArray, shadingStrength: Float): FloatArray {
        require(origRgb01.size == coloredRgb01.size) { "orig/work buffer size mismatch" }
        require(shadingStrength in 0f..1f) { "shading strength must be in [0,1]" }
        val origLab = rgbToLab(origRgb01)
        val coloredLab = rgbToLab(coloredRgb01)
        val merged = FloatArray(origRgb01.size)
        for (p in 0 until origRgb01.size / 3) {
            val offset = p * 3
            val gray = 0.299f * origRgb01[offset] +
                0.587f * origRgb01[offset + 1] +
                0.114f * origRgb01[offset + 2]
            val weight = shadingStrength * smoothstep(INK_EDGE_LOW, INK_EDGE_HIGH, gray)
            merged[offset] = origLab[offset] * (1f - weight) + coloredLab[offset] * weight
            merged[offset + 1] = coloredLab[offset + 1]
            merged[offset + 2] = coloredLab[offset + 2]
        }
        return labToRgb(merged)
    }

    /** kornia rgb_to_lab: sRGB D65 linearization -> XYZ -> CIELAB. */
    internal fun rgbToLab(rgb: FloatArray): FloatArray {
        val lab = FloatArray(rgb.size)
        for (p in 0 until rgb.size / 3) {
            val x = srgbToXyz(rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2])
            lab[p * 3] = 116f * fRatio(x[1] / WHITE_Y) - 16f
            lab[p * 3 + 1] = 500f * (fRatio(x[0] / WHITE_X) - fRatio(x[1] / WHITE_Y))
            lab[p * 3 + 2] = 200f * (fRatio(x[1] / WHITE_Y) - fRatio(x[2] / WHITE_Z))
        }
        return lab
    }

    internal fun labToRgb(lab: FloatArray): FloatArray {
        val rgb = FloatArray(lab.size)
        for (p in 0 until lab.size / 3) {
            val fy = (lab[p * 3] + 16f) / 116f
            val fx = fy + lab[p * 3 + 1] / 500f
            val fz = fy - lab[p * 3 + 2] / 200f
            val x = WHITE_X * fInvRatio(fx)
            val y = WHITE_Y * fInvRatio(fy)
            val z = WHITE_Z * fInvRatio(fz)
            rgb[p * 3] = xyzToSrgbComponent(XYZ_TO_SRGB_R * x + XYZ_TO_SRGB_G2 * y + XYZ_TO_SRGB_B2 * z)
            rgb[p * 3 + 1] = xyzToSrgbComponent(XYZ_TO_SRGB_R2 * x + XYZ_TO_SRGB_G * y + XYZ_TO_SRGB_B * z)
            rgb[p * 3 + 2] = xyzToSrgbComponent(XYZ_TO_SRGB_R3 * x + XYZ_TO_SRGB_G3 * y + XYZ_TO_SRGB_B3 * z)
        }
        return rgb
    }

    private fun fRatio(value: Float): Float {
        return if (value > LAB_EPSILON) value.pow(1f / 3f) else (LAB_KAPPA * value + 16f) / 116f
    }

    private fun fInvRatio(value: Float): Float {
        val cube = value * value * value
        return if (cube > LAB_EPSILON_CUBE) cube else (value * 116f - 16f) / LAB_KAPPA
    }

    private fun srgbToXyz(r: Float, g: Float, b: Float): FloatArray {
        val lr = srgbLinearize(r)
        val lg = srgbLinearize(g)
        val lb = srgbLinearize(b)
        return floatArrayOf(
            SRGB_TO_XYZ_X * lr + SRGB_TO_XYZ_X2 * lg + SRGB_TO_XYZ_X3 * lb,
            SRGB_TO_XYZ_Y * lr + SRGB_TO_XYZ_Y2 * lg + SRGB_TO_XYZ_Y3 * lb,
            SRGB_TO_XYZ_Z * lr + SRGB_TO_XYZ_Z2 * lg + SRGB_TO_XYZ_Z3 * lb,
        )
    }

    private fun srgbLinearize(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c > SRGB_THRESHOLD) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f
    }

    private fun xyzToSrgbComponent(linear: Float): Float {
        val c = linear.coerceIn(0f, 1f)
        val encoded = if (c > SRGB_LINEAR_THRESHOLD) 1.055f * c.pow(1f / 2.4f) - 0.055f else 12.92f * c
        return encoded.coerceIn(0f, 1f)
    }

    /** Mirrors AGSL/prototype smoothstep: clamped Hermite ramp between the ink-protection edges. */
    private fun smoothstep(edgeLow: Float, edgeHigh: Float, value: Float): Float {
        val t = ((value - edgeLow) / abs(edgeHigh - edgeLow)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val INK_EDGE_LOW = 0.12f
    private const val INK_EDGE_HIGH = 0.45f

    // D65 reference white and the sRGB<->XYZ matrices (IEC 61966-2-1).
    private const val WHITE_X = 0.95047f
    private const val WHITE_Y = 1f
    private const val WHITE_Z = 1.08883f
    private const val LAB_KAPPA = 903.3f
    private const val LAB_EPSILON = 0.008856f
    private const val LAB_EPSILON_CUBE = 0.008856f
    private const val SRGB_THRESHOLD = 0.04045f
    private const val SRGB_LINEAR_THRESHOLD = 0.0031308f

    private const val SRGB_TO_XYZ_X = 0.412453f
    private const val SRGB_TO_XYZ_X2 = 0.357580f
    private const val SRGB_TO_XYZ_X3 = 0.180423f
    private const val SRGB_TO_XYZ_Y = 0.212671f
    private const val SRGB_TO_XYZ_Y2 = 0.715160f
    private const val SRGB_TO_XYZ_Y3 = 0.072169f
    private const val SRGB_TO_XYZ_Z = 0.019334f
    private const val SRGB_TO_XYZ_Z2 = 0.119193f
    private const val SRGB_TO_XYZ_Z3 = 0.950227f

    private const val XYZ_TO_SRGB_R = 3.2404542f
    private const val XYZ_TO_SRGB_G2 = -1.5371385f
    private const val XYZ_TO_SRGB_B2 = -0.4985314f
    private const val XYZ_TO_SRGB_R2 = -0.9692660f
    private const val XYZ_TO_SRGB_G = 1.8760108f
    private const val XYZ_TO_SRGB_B = 0.0415560f
    private const val XYZ_TO_SRGB_R3 = 0.0556434f
    private const val XYZ_TO_SRGB_G3 = -0.2040259f
    private const val XYZ_TO_SRGB_B3 = 1.0572252f
}

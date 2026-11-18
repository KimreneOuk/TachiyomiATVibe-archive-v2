package eu.kanade.translation.colorreference

import kotlin.math.pow

/** Standard CIE L*a*b* D65 color space conversion primitives. */
internal object ColorSpaceConverter {
    private const val XN = 0.95047f
    private const val YN = 1.00000f
    private const val ZN = 1.08883f
    private const val EPSILON = 0.008856f
    private const val KAPPA = 903.3f

    fun rgbToLab(r255: Int, g255: Int, b255: Int, outLab: FloatArray) {
        val r = invGamma(r255 / 255f)
        val g = invGamma(g255 / 255f)
        val b = invGamma(b255 / 255f)

        val x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / XN
        val y = (0.2126729f * r + 0.7151522f * g + 0.0721750f * b) / YN
        val z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / ZN

        val fx = fLab(x)
        val fy = fLab(y)
        val fz = fLab(z)

        outLab[0] = (116f * fy) - 16f
        outLab[1] = 500f * (fx - fy)
        outLab[2] = 200f * (fy - fz)
    }

    fun labToRgb(l: Float, a: Float, b: Float): Int {
        val fy = (l + 16f) / 116f
        val fx = (a / 500f) + fy
        val fz = fy - (b / 200f)

        val x = fInvLab(fx) * XN
        val y = fInvLab(fy) * YN
        val z = fInvLab(fz) * ZN

        val rLinear = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        val gLinear = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        val bLinear = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z

        val r = (forwardGamma(rLinear).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g = (forwardGamma(gLinear).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bOut = (forwardGamma(bLinear).coerceIn(0f, 1f) * 255f + 0.5f).toInt()

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bOut
    }

    private fun fLab(t: Float): Float =
        if (t > EPSILON) t.pow(1f / 3f) else (KAPPA * t + 16f) / 116f

    private fun fInvLab(t: Float): Float {
        val t3 = t * t * t
        return if (t3 > EPSILON) t3 else (116f * t - 16f) / KAPPA
    }

    private fun invGamma(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun forwardGamma(c: Float): Float =
        if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
}

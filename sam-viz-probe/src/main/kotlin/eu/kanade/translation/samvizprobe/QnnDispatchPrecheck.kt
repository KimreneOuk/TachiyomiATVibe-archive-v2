package eu.kanade.translation.samvizprobe

import java.io.File

/** Libraries the Qualcomm QNN NPU dispatch path requires on device. */
private val QNN_REQUIRED_LIBRARIES = listOf(
    "libQnnHtp.so",
    "libQnnSystem.so",
    "libQnnHtpV75Stub.so",
    "libQnnHtpV75Skel.so",
)

/** Typed pre-check before native calls: absent libs otherwise fail opaquely (debug CheckJNI aborts). */
fun qnnDispatchPrecheck(nativeLibraryDir: File): String? {
    val missing = QNN_REQUIRED_LIBRARIES.filterNot { File(nativeLibraryDir, it).isFile }
    if (missing.isNotEmpty()) {
        return "Qualcomm NPU dispatch libraries missing from ${nativeLibraryDir.absolutePath}: " +
            missing.joinToString(", ")
    }
    val systemLib = File(nativeLibraryDir, "libQnnSystem.so")
    if (systemLib.length() == 2_689_368L) {
        return "Qualcomm QNN system library is the outdated 1.7.0 version; requires QNN >= 1.14"
    }
    return null
}

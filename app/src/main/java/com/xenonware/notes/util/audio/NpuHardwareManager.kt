package com.xenonware.notes.util.audio

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Hardware acceleration preference for LiteRT on-device speech inference.
 */
enum class HardwareAccelerator(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val description: String,
) {
    AUTO(
        id = "auto",
        displayName = "Auto (NPU / GPU / CPU)",
        shortLabel = "Auto",
        description = "Automatically selects Qualcomm Hexagon NPU, MediaTek NeuroPilot, Tensor TPU, Exynos NPU, or OpenCL GPU with XNNPACK fallback",
    ),
    NPU(
        id = "npu",
        displayName = "NPU / AI Accelerator",
        shortLabel = "NPU",
        description = "Prioritizes Qualcomm Hexagon HTP/QNN, MediaTek NeuroPilot APU, Google Tensor TPU, Exynos ENN, Kirin HiAI & NNAPI",
    ),
    GPU(
        id = "gpu",
        displayName = "GPU (OpenCL / GLES)",
        shortLabel = "GPU",
        description = "Accelerates LiteRT inference on Adreno / Mali / Immortalis GPU with CPU fallback",
    ),
    CPU(
        id = "cpu",
        displayName = "CPU (XNNPACK)",
        shortLabel = "CPU",
        description = "Multi-threaded ARM NEON / XNNPACK execution; lowest memory footprint and maximum stability",
    );

    companion object {
        fun fromId(id: String?): HardwareAccelerator {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
        }
    }
}

enum class NpuVendor(val displayName: String, val runtimeName: String) {
    QUALCOMM_HEXAGON("Qualcomm Hexagon NPU", "QNN HTP / FastRPC CDSP"),
    MEDIATEK_NEUROPILOT("MediaTek NeuroPilot APU", "Neuron / APU MDLA"),
    GOOGLE_TENSOR("Google Tensor TPU", "EdgeTPU / DarwiNN"),
    SAMSUNG_EXYNOS("Samsung Exynos NPU", "ENN / EDEN Runtime"),
    HISILICON_KIRIN("HiSilicon Kirin NPU", "HiAI DaVinci"),
    ROCKCHIP_RKNN("Rockchip NPU", "RKNN Runtime"),
    GENERIC_NNAPI("Android NNAPI Accelerator", "NNAPI Hardware HAL"),
    NONE("No Dedicated NPU", "GPU / CPU Fallback"),
}

data class NpuHardwareProfile(
    val vendor: NpuVendor,
    val socModel: String,
    val npuArchitecture: String,
    val hasHardwareNpu: Boolean,
    val hasFastRpcSupport: Boolean,
    val hasNnapiSupport: Boolean,
    val dispatchLibraryDir: String?,
    val dspLibraryPath: String?,
    val discoveredLibraries: List<String>,
    val recommendedCpuThreads: Int,
) {
    val summaryLabel: String
        get() = when (vendor) {
            NpuVendor.QUALCOMM_HEXAGON -> "Qualcomm Hexagon ($npuArchitecture • ${vendor.runtimeName})"
            NpuVendor.MEDIATEK_NEUROPILOT -> "MediaTek NeuroPilot ($npuArchitecture)"
            NpuVendor.GOOGLE_TENSOR -> "Google Tensor TPU ($npuArchitecture)"
            NpuVendor.SAMSUNG_EXYNOS -> "Samsung Exynos NPU ($npuArchitecture)"
            NpuVendor.HISILICON_KIRIN -> "HiSilicon Kirin NPU ($npuArchitecture)"
            NpuVendor.ROCKCHIP_RKNN -> "Rockchip NPU ($npuArchitecture)"
            NpuVendor.GENERIC_NNAPI -> "NNAPI NPU ($npuArchitecture)"
            NpuVendor.NONE -> "Multi-Core CPU / OpenCL GPU"
        }
}

/**
 * Hardware NPU & Accelerator discovery and native LiteRT runtime configurator.
 *
 * Detects Qualcomm Hexagon NPUs, MediaTek NeuroPilot APUs, Google Tensor TPUs,
 * Samsung Exynos NPUs, HiSilicon Kirin NPUs, Rockchip RKNN, and Android NNAPI,
 * configuring LiteRT dispatch/compiler paths and optimal big.LITTLE thread counts.
 */
object NpuHardwareManager {

    private const val TAG = "NpuHardwareManager"

    private val VENDOR_LIB_DIRS = listOf(
        "/vendor/lib64",
        "/system/vendor/lib64",
        "/odm/lib64",
        "/vendor/lib64/egl",
        "/system/lib64",
    )

    private val HEXAGON_DSP_DIRS = listOf(
        "/vendor/lib/rfsa/adsp",
        "/system/vendor/lib/rfsa/adsp",
        "/vendor/dsp/cdsp",
        "/vendor/dsp/adsp",
        "/odm/lib/rfsa/adsp",
        "/vendor/lib64",
        "/system/vendor/lib64",
    )

    private val QUALCOMM_NPU_LIBS = listOf(
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpPrepare.so",
        "libQnnDsp.so",
        "libLiteRtDispatch_Qualcomm.so",
        "libLiteRtQualcommAccelerator.so",
        "libQnnHtpV79Stub.so",
        "libQnnHtpV75Stub.so",
        "libQnnHtpV73Stub.so",
        "libQnnHtpV69Stub.so",
        "libQnnHtpV68Stub.so",
        "libSNPE.so",
        "libhta.so",
        "libhexagon_nn_skel.so",
        "libcdsprpc.so",
        "libadsprpc.so",
    )

    private val MEDIATEK_NPU_LIBS = listOf(
        "libneuron_adapter.so",
        "libneuronusdk_adapter.mtk.so",
        "libneuron_runtime.so",
        "libapusys.so",
        "libnir_neon_driver_ndk.mtk.so",
        "libLiteRtNeuroPilotAccelerator.so",
    )

    private val GOOGLE_TENSOR_LIBS = listOf(
        "libedgetpu_client.so",
        "libedgetpu_util.so",
        "libdarwinn.so",
        "libLiteRtGoogleTensorAccelerator.so",
    )

    private val SAMSUNG_EXYNOS_LIBS = listOf(
        "libenn_user.so",
        "libenn_Wrapper.so",
        "libeden_rt.so",
        "libnpu.so",
    )

    private val HISILICON_KIRIN_LIBS = listOf(
        "libhiai.so",
        "libhiai_ir.so",
        "libai_client.so",
        "libnpu_drv.so",
    )

    private val ROCKCHIP_NPU_LIBS = listOf(
        "librknnrt.so",
        "librknn_api.so",
    )

    private val QUALCOMM_SOC_REGEX = Regex(
        "\\b(sm[4678]\\d{3}|sdm\\d{3}|qcs\\d{3,4}|lahaina|taro|kalama|pineapple|sun|crow|cliff|pakala|canoe)\\b"
    )

    @Volatile
    private var cachedProfile: NpuHardwareProfile? = null

    /**
     * Zero-I/O hardware summary suitable for immediate UI initialization while
     * [detectHardwareProfile] completes the vendor shared-library scan off the main thread.
     */
    fun cachedHardwareSummaryOrQuick(): String {
        cachedProfile?.let { return it.summaryLabel }
        val soc = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOfNotNull(
                    Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
                    Build.HARDWARE?.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
                ).distinct().joinToString(" ")
            } else {
                Build.HARDWARE ?: ""
            }
        }.getOrDefault("")
        val (vendor, arch) = classifySocAndVendor(socSignature = soc)
        return "$arch (${vendor.displayName})"
    }

    fun detectHardwareProfile(): NpuHardwareProfile {
        cachedProfile?.let { return it }

        val socRaw = buildSocSignature()
        val discoveredLibs = mutableListOf<String>()
        var dispatchDir: String? = null

        fun scanLibs(candidates: List<String>): List<String> {
            val found = mutableListOf<String>()
            for (dir in VENDOR_LIB_DIRS) {
                for (lib in candidates) {
                    val file = File(dir, lib)
                    if (file.exists()) {
                        found.add(file.absolutePath)
                        if (dispatchDir == null && dir != "/system/lib64") {
                            dispatchDir = dir
                        }
                    }
                }
            }
            return found
        }

        val qcomFound = scanLibs(QUALCOMM_NPU_LIBS)
        val mtkFound = scanLibs(MEDIATEK_NPU_LIBS)
        val tensorFound = scanLibs(GOOGLE_TENSOR_LIBS)
        val exynosFound = scanLibs(SAMSUNG_EXYNOS_LIBS)
        val kirinFound = scanLibs(HISILICON_KIRIN_LIBS)
        val rockchipFound = scanLibs(ROCKCHIP_NPU_LIBS)

        discoveredLibs.addAll(qcomFound)
        discoveredLibs.addAll(mtkFound)
        discoveredLibs.addAll(tensorFound)
        discoveredLibs.addAll(exynosFound)
        discoveredLibs.addAll(kirinFound)
        discoveredLibs.addAll(rockchipFound)

        val hasNnapi = File("/system/lib64/libneuralnetworks.so").exists() ||
            File("/apex/com.android.neuralnetworks/lib64/libneuralnetworks.so").exists() ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val hasFastRpc = qcomFound.any { it.endsWith("libcdsprpc.so") || it.endsWith("libadsprpc.so") } ||
            File("/dev/adsprpc-smd").exists() ||
            File("/dev/cdsprpc-smd").exists()

        val existingDspDirs = HEXAGON_DSP_DIRS.filter { File(it).isDirectory }
        val dspPath = if (existingDspDirs.isNotEmpty()) {
            existingDspDirs.joinToString(";")
        } else {
            "/vendor/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/lib64"
        }

        val classified = classifySocAndVendor(
            socSignature = socRaw,
            hasQualcommLibs = qcomFound.isNotEmpty() || hasFastRpc,
            hasMediaTekLibs = mtkFound.isNotEmpty(),
            hasTensorLibs = tensorFound.isNotEmpty(),
            hasExynosLibs = exynosFound.isNotEmpty(),
            hasKirinLibs = kirinFound.isNotEmpty(),
            hasRockchipLibs = rockchipFound.isNotEmpty(),
            hasNnapi = hasNnapi,
        )

        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val recommendedThreads = computeOptimalCpuThreads(totalCores)

        val profile = NpuHardwareProfile(
            vendor = classified.first,
            socModel = socRaw.ifBlank { Build.HARDWARE ?: "Unknown SoC" },
            npuArchitecture = classified.second,
            hasHardwareNpu = classified.first != NpuVendor.NONE,
            hasFastRpcSupport = hasFastRpc || classified.first == NpuVendor.QUALCOMM_HEXAGON,
            hasNnapiSupport = hasNnapi,
            dispatchLibraryDir = dispatchDir ?: existingDspDirs.firstOrNull() ?: "/vendor/lib64",
            dspLibraryPath = dspPath,
            discoveredLibraries = discoveredLibs.distinct(),
            recommendedCpuThreads = recommendedThreads,
        )
        cachedProfile = profile
        return profile
    }

    /**
     * Pure SoC classification function (unit-testable without Android device filesystem).
     */
    internal fun classifySocAndVendor(
        socSignature: String,
        hasQualcommLibs: Boolean = false,
        hasMediaTekLibs: Boolean = false,
        hasTensorLibs: Boolean = false,
        hasExynosLibs: Boolean = false,
        hasKirinLibs: Boolean = false,
        hasRockchipLibs: Boolean = false,
        hasNnapi: Boolean = true,
        isArm64Override: Boolean? = null,
    ): Pair<NpuVendor, String> {
        val norm = socSignature.lowercase(Locale.ROOT)

        // 1. Qualcomm Snapdragon / Hexagon NPU (HTP / CDSP / QNN)
        val isQualcommSoc = norm.contains("qcom") ||
            norm.contains("qualcomm") ||
            norm.contains("snapdragon") ||
            QUALCOMM_SOC_REGEX.containsMatchIn(norm)

        if (isQualcommSoc || hasQualcommLibs) {
            return NpuVendor.QUALCOMM_HEXAGON to resolveQualcommHexagonArch(norm)
        }

        // 2. MediaTek Dimensity / Helio NeuroPilot APU
        val isMediaTekSoc = norm.contains("mt68") ||
            norm.contains("mt69") ||
            norm.contains("mt67") ||
            norm.contains("mt81") ||
            norm.contains("dimensity") ||
            norm.contains("mediatek") ||
            norm.contains("helio") ||
            norm.contains("kompanio")

        if (isMediaTekSoc || hasMediaTekLibs) {
            val arch = when {
                norm.contains("mt6991") || norm.contains("9400") -> "APU 890 (Gen 8)"
                norm.contains("mt6989") || norm.contains("9300") -> "APU 790 (Gen 7)"
                norm.contains("mt6985") || norm.contains("9200") -> "APU 690 (Gen 6)"
                norm.contains("mt6983") || norm.contains("9000") -> "APU 590 (Gen 5)"
                norm.contains("mt6897") || norm.contains("8300") -> "APU 780"
                norm.contains("mt6895") || norm.contains("8200") || norm.contains("8100") -> "APU 580"
                norm.contains("mt68") -> "Dimensity APU MDLA"
                else -> "NeuroPilot APU"
            }
            return NpuVendor.MEDIATEK_NEUROPILOT to arch
        }

        // 3. Google Tensor TPU (EdgeTPU / DarwiNN)
        val isGoogleTensor = norm.contains("tensor") ||
            norm.contains("gs101") ||
            norm.contains("gs201") ||
            norm.contains("zuma") ||
            norm.contains("laguna")

        if (isGoogleTensor || hasTensorLibs) {
            val arch = when {
                norm.contains("laguna") || norm.contains("g5") -> "Tensor G5 TPU"
                norm.contains("zumapro") || norm.contains("g4") -> "Tensor G4 Rio TPU"
                norm.contains("zuma") || norm.contains("g3") -> "Tensor G3 Rio TPU"
                norm.contains("gs201") || norm.contains("g2") -> "Tensor G2 Janeiro TPU"
                norm.contains("gs101") || norm.contains("g1") -> "Tensor G1 Whitechapel TPU"
                else -> "EdgeTPU / DarwiNN"
            }
            return NpuVendor.GOOGLE_TENSOR to arch
        }

        // 4. Samsung Exynos NPU (ENN / EDEN)
        val isExynos = norm.contains("exynos") ||
            norm.contains("s5e99") ||
            norm.contains("s5e88") ||
            norm.contains("s5e98") ||
            norm.contains("erd")

        if (isExynos || hasExynosLibs) {
            val arch = when {
                norm.contains("9955") || norm.contains("2500") -> "Exynos 2500 NPU"
                norm.contains("9945") || norm.contains("2400") -> "Exynos 2400 Dual-NPU"
                norm.contains("9925") || norm.contains("2200") -> "Exynos 2200 Dual-NPU"
                norm.contains("9840") || norm.contains("2100") -> "Exynos 2100 Triple-NPU"
                else -> "Exynos ENN NPU"
            }
            return NpuVendor.SAMSUNG_EXYNOS to arch
        }

        // 5. HiSilicon Kirin NPU
        val isKirin = norm.contains("kirin") ||
            norm.contains("hi36") ||
            norm.contains("hi62") ||
            norm.contains("hisilicon")

        if (isKirin || hasKirinLibs) {
            return NpuVendor.HISILICON_KIRIN to "DaVinci NPU"
        }

        // 6. Rockchip RKNN NPU
        val isRockchip = norm.contains("rk3588") || norm.contains("rk356") || norm.contains("rockchip")
        if (isRockchip || hasRockchipLibs) {
            return NpuVendor.ROCKCHIP_RKNN to "RKNN NPU"
        }

        // 7. Generic NNAPI on modern arm64 devices
        val isArm64 = isArm64Override
            ?: (Build.SUPPORTED_ABIS?.any { it.contains("arm64") } == true)
        if (hasNnapi && isArm64) {
            return NpuVendor.GENERIC_NNAPI to "ARM64 NNAPI HAL"
        }

        return NpuVendor.NONE to "CPU XNNPACK"
    }

    internal fun resolveQualcommHexagonArch(normSoc: String): String {
        return when {
            normSoc.contains("sm8750") || normSoc.contains("sun") || normSoc.contains("8 elite") ->
                "Hexagon V79 HTP"
            normSoc.contains("sm8650") || normSoc.contains("pineapple") || normSoc.contains("8 gen 3") ->
                "Hexagon V75 HTP"
            normSoc.contains("sm8635") || normSoc.contains("cliff") || normSoc.contains("8s gen 3") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm8550") || normSoc.contains("kalama") || normSoc.contains("8 gen 2") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm7675") || normSoc.contains("7+ gen 3") || normSoc.contains("sm7550") ->
                "Hexagon V73 HTP"
            normSoc.contains("sm8475") || normSoc.contains("sm8450") || normSoc.contains("taro") || normSoc.contains("8 gen 1") ->
                "Hexagon V69 HTP"
            normSoc.contains("sm7475") || normSoc.contains("7+ gen 2") ->
                "Hexagon V69 HTP"
            normSoc.contains("sm8350") || normSoc.contains("lahaina") || normSoc.contains("888") ->
                "Hexagon V68 HTP"
            normSoc.contains("sm8250") || normSoc.contains("865") || normSoc.contains("870") ->
                "Hexagon V66 HTP"
            normSoc.contains("sm7") || normSoc.contains("sm6") ->
                "Hexagon CDSP HTP"
            else -> "Hexagon HTP / QNN"
        }
    }

    internal fun computeOptimalCpuThreads(totalCores: Int): Int {
        // Avoid scheduling XNNPACK work onto little efficiency cores on heterogeneous mobile SoCs.
        return when {
            totalCores >= 6 -> 4
            totalCores >= 4 -> 3
            else -> 2
        }
    }

    /**
     * Applies native environment variables consumed by `libLiteRt.so`, `libVozAndroid.so`,
     * Qualcomm QNN/FastRPC, MediaTek Neuron, Google Tensor, and Samsung Exynos runtimes.
     *
     * Returns a user-facing description of the active hardware delegate.
     */
    fun configureNativeRuntime(
        context: Context,
        requested: HardwareAccelerator,
        lowMemoryPressure: Boolean = false,
    ): String {
        val profile = detectHardwareProfile()
        val npuCacheDir = File(context.cacheDir, "litert_npu_cache").apply { mkdirs() }
        val threads = if (lowMemoryPressure) {
            profile.recommendedCpuThreads.coerceAtMost(3)
        } else {
            profile.recommendedCpuThreads
        }

        val effectiveMode = when {
            lowMemoryPressure && requested == HardwareAccelerator.AUTO && !profile.hasHardwareNpu ->
                HardwareAccelerator.CPU
            requested == HardwareAccelerator.AUTO ->
                if (profile.hasHardwareNpu) HardwareAccelerator.NPU else HardwareAccelerator.GPU
            requested == HardwareAccelerator.NPU && !profile.hasHardwareNpu && !profile.hasNnapiSupport ->
                HardwareAccelerator.GPU
            else -> requested
        }

        try {
            Os.setenv("DAL_LITERT_THREADS", threads.toString(), true)
            Os.setenv("DAL_RUN_DEPTH", "1", true)
            Os.setenv("LITERT_NUM_THREADS", threads.toString(), true)
            Os.setenv("LITERT_NPU_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("LITERT_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("TFLITE_CACHE_DIR", npuCacheDir.absolutePath, true)
            Os.setenv("OPENCL_CACHE_DIR", npuCacheDir.absolutePath, true)

            when (effectiveMode) {
                HardwareAccelerator.NPU -> {
                    Os.setenv("LITERT_USE_NPU", "1", true)
                    Os.setenv("LITERT_USE_NNAPI", "1", true)
                    Os.setenv("LITERT_USE_GPU", "1", true)
                    Os.setenv("DAL_ACCELERATOR", "npu", true)
                    Os.setenv("DAL_DEVICE_ID", "npu", true)
                    Os.setenv("NNAPI_ALLOW_FP16", "1", true)

                    val appNativeLibDir = context.applicationInfo?.nativeLibraryDir?.takeIf { it.isNotBlank() }
                    val dispatchLibDir = appNativeLibDir ?: profile.dispatchLibraryDir
                    dispatchLibDir?.let { dir ->
                        Os.setenv("LITERT_DISPATCH_LIBRARY_DIR", dir, true)
                        Os.setenv("LITERT_DISPATCH_LIB_PATH", dir, true)
                        Os.setenv("LITERT_COMPILER_PLUGIN_LIBRARY_DIR", dir, true)
                        Os.setenv("LITERT_COMPILER_PLUGIN_PATH", dir, true)
                    }
                    val qnnSearchPath = listOfNotNull(appNativeLibDir, profile.dispatchLibraryDir)
                        .distinct()
                        .joinToString(":")
                        .takeIf { it.isNotBlank() }
                    if (qnnSearchPath != null) {
                        Os.setenv("QNN_LIBRARY_PATH", qnnSearchPath, true)
                    }

                    if (profile.vendor == NpuVendor.QUALCOMM_HEXAGON || profile.hasFastRpcSupport) {
                        val dspPath = profile.dspLibraryPath
                            ?: "/vendor/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp"
                        Os.setenv("ADSP_LIBRARY_PATH", dspPath, true)
                        Os.setenv("CDSP_LIBRARY_PATH", dspPath, true)
                        Os.setenv(
                            "QNN_HTP_PERF_MODE",
                            if (lowMemoryPressure) "balanced" else "sustained_high_performance",
                            true,
                        )
                        Os.setenv("QNN_HTP_ENABLE_PDR", "1", true)
                        Os.setenv("FASTRPC_PERF_KERNEL", "1", true)
                        Os.setenv("HEXAGON_NPU_ENABLE", "1", true)
                    }

                    if (profile.vendor == NpuVendor.MEDIATEK_NEUROPILOT) {
                        Os.setenv("NEURON_RT_PERFORMANCE_MODE", if (lowMemoryPressure) "0" else "1", true)
                        Os.setenv("NEURON_RT_ALLOW_FP16", "1", true)
                        Os.setenv("MTK_NEUROPILOT_ENABLE", "1", true)
                    }

                    if (profile.vendor == NpuVendor.GOOGLE_TENSOR) {
                        Os.setenv("LITERT_GOOGLE_TENSOR_ENABLE", "1", true)
                    }

                    if (profile.vendor == NpuVendor.SAMSUNG_EXYNOS) {
                        Os.setenv("ENN_PREFER_FP16", "1", true)
                    }
                }

                HardwareAccelerator.GPU -> {
                    Os.setenv("LITERT_USE_NPU", "0", true)
                    Os.setenv("LITERT_USE_NNAPI", "0", true)
                    Os.setenv("LITERT_USE_GPU", "1", true)
                    Os.setenv("DAL_ACCELERATOR", "gpu", true)
                    Os.setenv("DAL_DEVICE_ID", "gpu", true)
                }

                HardwareAccelerator.CPU -> {
                    Os.setenv("LITERT_USE_NPU", "0", true)
                    Os.setenv("LITERT_USE_NNAPI", "0", true)
                    Os.setenv("LITERT_USE_GPU", "0", true)
                    Os.setenv("DAL_ACCELERATOR", "cpu", true)
                    Os.setenv("DAL_DEVICE_ID", "cpu", true)
                }

                HardwareAccelerator.AUTO -> Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply native runtime env vars: ${t.message}")
        }

        val activeLabel = when (effectiveMode) {
            HardwareAccelerator.NPU -> {
                val prefix = if (requested == HardwareAccelerator.AUTO) "Auto -> " else ""
                "$prefix${profile.summaryLabel}"
            }
            HardwareAccelerator.GPU -> {
                val prefix = if (requested == HardwareAccelerator.AUTO) "Auto -> " else ""
                "${prefix}GPU OpenCL/GLES + $threads-thread XNNPACK"
            }
            HardwareAccelerator.CPU -> {
                val suffix = if (lowMemoryPressure && requested != HardwareAccelerator.CPU) " • Low-RAM Safe" else ""
                "CPU ($threads-thread XNNPACK$suffix)"
            }
            HardwareAccelerator.AUTO -> "Auto (${profile.summaryLabel})"
        }

        Log.i(
            TAG,
            "Configured accelerator: requested=${requested.id}, effective=${effectiveMode.id}, " +
                "vendor=${profile.vendor.name}, arch=${profile.npuArchitecture}, threads=$threads",
        )

        return activeLabel
    }

    private fun buildSocSignature(): String {
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val soc = Build.SOC_MODEL
                if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) parts.add(soc)
                val mf = Build.SOC_MANUFACTURER
                if (!mf.isNullOrBlank() && mf != Build.UNKNOWN) parts.add(mf)
            }
        }
        runCatching {
            val hw = Build.HARDWARE
            if (!hw.isNullOrBlank() && hw != Build.UNKNOWN) parts.add(hw)
            val board = Build.BOARD
            if (!board.isNullOrBlank() && board != Build.UNKNOWN) parts.add(board)
        }
        for (prop in listOf("ro.soc.model", "ro.board.platform", "ro.hardware.chipname", "ro.ChipName")) {
            val value = readSystemProperty(prop)
            if (!value.isNullOrBlank()) {
                parts.add(value)
            }
        }
        return parts.distinct().joinToString(" ")
    }

    private fun readSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            val v = getMethod.invoke(null, key, "") as? String
            v?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }
}

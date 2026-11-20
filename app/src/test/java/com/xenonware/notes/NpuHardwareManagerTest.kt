package com.xenonware.notes

import com.xenonware.notes.util.audio.HardwareAccelerator
import com.xenonware.notes.util.audio.NpuHardwareManager
import com.xenonware.notes.util.audio.NpuVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuHardwareManagerTest {

    @Test
    fun `classifies Qualcomm Snapdragon SoCs and Hexagon HTP versions`() {
        val elite = NpuHardwareManager.classifySocAndVendor("Qualcomm Technologies, Inc SM8750 sun")
        assertEquals(NpuVendor.QUALCOMM_HEXAGON, elite.first)
        assertEquals("Hexagon V79 HTP", elite.second)

        val gen3 = NpuHardwareManager.classifySocAndVendor("sm8650 pineapple")
        assertEquals(NpuVendor.QUALCOMM_HEXAGON, gen3.first)
        assertEquals("Hexagon V75 HTP", gen3.second)

        val gen2 = NpuHardwareManager.classifySocAndVendor("sm8550 kalama")
        assertEquals(NpuVendor.QUALCOMM_HEXAGON, gen2.first)
        assertEquals("Hexagon V73 HTP", gen2.second)

        val gen1 = NpuHardwareManager.classifySocAndVendor("sm8450 taro")
        assertEquals(NpuVendor.QUALCOMM_HEXAGON, gen1.first)
        assertEquals("Hexagon V69 HTP", gen1.second)

        val sd888 = NpuHardwareManager.classifySocAndVendor("sm8350 lahaina")
        assertEquals(NpuVendor.QUALCOMM_HEXAGON, sd888.first)
        assertEquals("Hexagon V68 HTP", sd888.second)
    }

    @Test
    fun `classifies MediaTek, Google Tensor, Exynos, Kirin, Rockchip, NNAPI, and CPU fallback`() {
        val d9400 = NpuHardwareManager.classifySocAndVendor("MT6991 Dimensity 9400")
        assertEquals(NpuVendor.MEDIATEK_NEUROPILOT, d9400.first)
        assertEquals("APU 890 (Gen 8)", d9400.second)

        val d9300 = NpuHardwareManager.classifySocAndVendor("mt6989")
        assertEquals(NpuVendor.MEDIATEK_NEUROPILOT, d9300.first)
        assertEquals("APU 790 (Gen 7)", d9300.second)

        val g5 = NpuHardwareManager.classifySocAndVendor("Google Tensor laguna")
        assertEquals(NpuVendor.GOOGLE_TENSOR, g5.first)
        assertEquals("Tensor G5 TPU", g5.second)

        val g4 = NpuHardwareManager.classifySocAndVendor("Tensor G4 zumapro")
        assertEquals(NpuVendor.GOOGLE_TENSOR, g4.first)
        assertEquals("Tensor G4 Rio TPU", g4.second)

        val g3 = NpuHardwareManager.classifySocAndVendor("zuma")
        assertEquals(NpuVendor.GOOGLE_TENSOR, g3.first)
        assertEquals("Tensor G3 Rio TPU", g3.second)

        val exynos = NpuHardwareManager.classifySocAndVendor("Samsung s5e9945 Exynos")
        assertEquals(NpuVendor.SAMSUNG_EXYNOS, exynos.first)
        assertEquals("Exynos 2400 Dual-NPU", exynos.second)

        val kirin = NpuHardwareManager.classifySocAndVendor("HiSilicon Kirin 9000")
        assertEquals(NpuVendor.HISILICON_KIRIN, kirin.first)

        val rk = NpuHardwareManager.classifySocAndVendor("rockchip rk3588")
        assertEquals(NpuVendor.ROCKCHIP_RKNN, rk.first)

        val nnapi = NpuHardwareManager.classifySocAndVendor("Unknown ARM", hasNnapi = true, isArm64Override = true)
        assertEquals(NpuVendor.GENERIC_NNAPI, nnapi.first)

        val cpu = NpuHardwareManager.classifySocAndVendor("Generic", hasNnapi = false, isArm64Override = false)
        assertEquals(NpuVendor.NONE, cpu.first)
        assertTrue(cpu.second.contains("XNNPACK"))
    }

    @Test
    fun `computeOptimalCpuThreads caps bigLITTLE straggler overhead`() {
        assertEquals(2, NpuHardwareManager.computeOptimalCpuThreads(2))
        assertEquals(3, NpuHardwareManager.computeOptimalCpuThreads(4))
        assertEquals(4, NpuHardwareManager.computeOptimalCpuThreads(6))
        assertEquals(4, NpuHardwareManager.computeOptimalCpuThreads(8))
        assertEquals(4, NpuHardwareManager.computeOptimalCpuThreads(12))
    }

    @Test
    fun `HardwareAccelerator fromId resolves known values and defaults to AUTO`() {
        assertEquals(HardwareAccelerator.AUTO, HardwareAccelerator.fromId(null))
        assertEquals(HardwareAccelerator.AUTO, HardwareAccelerator.fromId("unknown"))
        assertEquals(HardwareAccelerator.NPU, HardwareAccelerator.fromId("npu"))
        assertEquals(HardwareAccelerator.GPU, HardwareAccelerator.fromId("GPU"))
        assertEquals(HardwareAccelerator.CPU, HardwareAccelerator.fromId("cpu"))
    }
}

package com.figago.shared.bms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AntBmsParserTest {

    @Test
    fun testParse8SCellConfiguration() {
        val parser = AntBmsParser()
        // 8 cells, 4 temperature sensors
        // Header: 0..33 (34 bytes)
        // Cells: 8 * 2 = 16 bytes (34..49)
        // Temps: 4 * 2 = 8 bytes (50..57)
        // Base = 34 + 16 + 8 = 58
        // Fixed telemetry: 58..110 (52 bytes)
        // Total frame = 114 bytes (6 header + dataLen 104 + 4 checksum/end)
        
        val frame = ByteArray(114)
        frame[0] = 0x7E.toByte()
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11.toByte() // function
        frame[3] = 0x00.toByte()
        frame[4] = 0x00.toByte()
        frame[5] = 104.toByte() // data length

        frame[8] = 4.toByte() // 4 temp sensors
        frame[9] = 8.toByte() // 8 cells

        // 8 cells starting at byte 34: 3.479V = 3479 mV = 0x0D97
        for (i in 0 until 8) {
            val cellMv = 3470 + i * 2 // 3.470V .. 3.484V
            frame[34 + i * 2] = (cellMv and 0xFF).toByte()
            frame[34 + i * 2 + 1] = ((cellMv shr 8) and 0xFF).toByte()
        }

        // 4 external temperatures starting at 50 (34 + 16): 22, 23, 24, 25 C
        for (i in 0 until 4) {
            val t = 22 + i
            frame[50 + i * 2] = (t and 0xFF).toByte()
            frame[50 + i * 2 + 1] = ((t shr 8) and 0xFF).toByte()
        }

        val base = 58
        // base + 0: Mosfet temp = 29 C
        frame[base + 0] = 29.toByte()
        frame[base + 1] = 0.toByte()

        // base + 2: Balancer temp = 27 C
        frame[base + 2] = 27.toByte()
        frame[base + 3] = 0.toByte()

        // base + 4: Total voltage 27.83V = 2783 = 0x0ADF
        frame[base + 4] = 0xDF.toByte()
        frame[base + 5] = 0x0A.toByte()

        // base + 6: Current 5.0A = 50 = 0x0032
        frame[base + 6] = 50.toByte()
        frame[base + 7] = 0.toByte()

        // base + 8: SOC 100%
        frame[base + 8] = 100.toByte()
        frame[base + 9] = 0.toByte()

        // base + 12: Charge MOS = ON (1)
        frame[base + 12] = 1.toByte()

        // base + 13: Discharge MOS = ON (1)
        frame[base + 13] = 1.toByte()

        // base + 14: Balancer = 4 (Auto equalizing)
        frame[base + 14] = 4.toByte()

        // base + 16: Total capacity = 60Ah = 60,000,000 uAh = 0x03938700
        frame[base + 16] = 0x00.toByte()
        frame[base + 17] = 0x87.toByte()
        frame[base + 18] = 0x93.toByte()
        frame[base + 19] = 0x03.toByte()

        // base + 20: Capacity remaining = 20Ah = 20,000,000 uAh = 0x01312D00
        frame[base + 20] = 0x00.toByte()
        frame[base + 21] = 0x2D.toByte()
        frame[base + 22] = 0x31.toByte()
        frame[base + 23] = 0x01.toByte()

        // base + 24: Total cycle capacity = 3100.069 Ah = 3,100,069 = 0x002F4DA5
        frame[base + 24] = 0xA5.toByte()
        frame[base + 25] = 0x4D.toByte()
        frame[base + 26] = 0x2F.toByte()
        frame[base + 27] = 0x00.toByte()

        // End of packet
        frame[112] = 0xAA.toByte()
        frame[113] = 0x55.toByte()

        val status = parser.assemble(frame)

        assertNotNull("Status should not be null", status)
        assertEquals(8, status?.cellVoltages?.size)
        assertEquals(3.470, status?.cellVoltages?.get(0) ?: 0.0, 0.001)
        assertEquals(3.484, status?.cellVoltages?.get(7) ?: 0.0, 0.001)

        // 4 external + 2 internal = 6 temperatures
        assertEquals(6, status?.temperatures?.size)
        assertEquals(22, status?.temperatures?.get(0))
        assertEquals(29, status?.temperatures?.get(4)) // MOS
        assertEquals(27, status?.temperatures?.get(5)) // Balancer
        assertEquals(22, status?.temperature) // Main temp

        assertEquals(27.83, status?.voltage ?: 0.0, 0.001)
        assertEquals(5.0, status?.current ?: 0.0, 0.001)
        assertEquals(100, status?.soc)
        assertEquals(true, status?.isChargeMosOpen)
        assertEquals(true, status?.isDischargeMosOpen)
        assertEquals(4, status?.balanceStatus)
        assertEquals(20.0, status?.capacityAh ?: 0.0, 0.001)
        assertEquals(60.0, status?.totalCapacityAh ?: 0.0, 0.001)
        assertEquals(20.0, status?.remainingCapacityAh ?: 0.0, 0.001)
        assertEquals(3100.069, status?.totalCycleAh ?: 0.0, 0.001)
    }

    @Test
    fun testParse16SCellConfiguration() {
        val parser = AntBmsParser()
        // 16 cells, 2 temperature sensors
        // Header: 0..33 (34 bytes)
        // Cells: 16 * 2 = 32 bytes (34..65)
        // Temps: 2 * 2 = 4 bytes (66..69)
        // Base = 34 + 32 + 4 = 70
        // DataLen = (70 + 52) - 6 = 116
        // Total frame = 126 bytes
        
        val frame = ByteArray(126)
        frame[0] = 0x7E.toByte()
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11.toByte()
        frame[5] = 116.toByte()

        frame[8] = 2.toByte() // 2 temp sensors
        frame[9] = 16.toByte() // 16 cells

        // 16 cells starting at byte 34: 3.300V = 3300 mV = 0x0CE4
        for (i in 0 until 16) {
            frame[34 + i * 2] = 0xE4.toByte()
            frame[34 + i * 2 + 1] = 0x0C.toByte()
        }

        // 2 external temps: 25 C
        frame[66] = 25.toByte()
        frame[67] = 0.toByte()
        frame[68] = 26.toByte()
        frame[69] = 0.toByte()

        val base = 70
        // base + 0: Mosfet temp = 30 C
        frame[base + 0] = 30.toByte()
        frame[base + 1] = 0.toByte()

        // base + 2: Balancer temp = 28 C
        frame[base + 2] = 28.toByte()
        frame[base + 3] = 0.toByte()

        // base + 4: Total voltage 52.80V = 5280 = 0x14A0
        frame[base + 4] = 0xA0.toByte()
        frame[base + 5] = 0x14.toByte()

        // base + 6: Current 10.0A = 100 = 0x0064
        frame[base + 6] = 100.toByte()
        frame[base + 7] = 0.toByte()

        // base + 8: SOC 80%
        frame[base + 8] = 80.toByte()
        frame[base + 9] = 0.toByte()

        frame[base + 12] = 1.toByte()
        frame[base + 13] = 1.toByte()

        frame[124] = 0xAA.toByte()
        frame[125] = 0x55.toByte()

        val status = parser.assemble(frame)

        assertNotNull("Status should not be null", status)
        assertEquals(16, status?.cellVoltages?.size)
        assertEquals(3.3, status?.cellVoltages?.get(0) ?: 0.0, 0.001)
        assertEquals(4, status?.temperatures?.size)
        assertEquals(52.80, status?.voltage ?: 0.0, 0.001)
        assertEquals(10.0, status?.current ?: 0.0, 0.001)
        assertEquals(80, status?.soc)
    }

    @Test
    fun testParse4SCellConfiguration() {
        val parser = AntBmsParser()
        // 4 cells, 1 temp sensor
        // Base = 34 + 8 + 2 = 44
        // DataLen = (44 + 52) - 6 = 90
        // Total frame = 100 bytes
        val frame = ByteArray(100)
        frame[0] = 0x7E.toByte()
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11.toByte()
        frame[5] = 90.toByte()
        frame[8] = 1.toByte()
        frame[9] = 4.toByte()

        for (i in 0 until 4) {
            frame[34 + i * 2] = 0xE4.toByte()
            frame[34 + i * 2 + 1] = 0x0C.toByte()
        }

        frame[42] = 20.toByte()
        frame[43] = 0.toByte()

        val base = 44
        frame[base + 0] = 25.toByte()
        frame[base + 2] = 22.toByte()
        // Voltage: 13.20V = 1320 = 0x0528
        frame[base + 4] = 0x28.toByte()
        frame[base + 5] = 0x05.toByte()

        frame[98] = 0xAA.toByte()
        frame[99] = 0x55.toByte()

        val status = parser.assemble(frame)
        assertNotNull(status)
        assertEquals(4, status?.cellVoltages?.size)
        assertEquals(13.20, status?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testChunkedDataArrival() {
        val parser = AntBmsParser()
        val frame = createSample8SFrame()

        // Feed data in small chunks (e.g. 15 bytes each)
        var lastStatus: AntBmsStatus? = null
        var offset = 0
        while (offset < frame.size) {
            val chunkSize = minOf(15, frame.size - offset)
            val chunk = frame.copyOfRange(offset, offset + chunkSize)
            val status = parser.assemble(chunk)
            if (status != null) {
                lastStatus = status
            }
            offset += chunkSize
        }

        assertNotNull("Should parse complete frame when delivered in chunks", lastStatus)
        assertEquals(8, lastStatus?.cellVoltages?.size)
        assertEquals(27.83, lastStatus?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testSplitHeaderAcrossChunks() {
        val parser = AntBmsParser()
        val frame = createSample8SFrame()

        // Chunk 1 has leading noise + first byte of header 0x7E
        val chunk1 = byteArrayOf(0x12, 0x34, 0x7E.toByte())
        val chunk2 = frame.copyOfRange(1, frame.size) // starts with 0xA1

        assertNull(parser.assemble(chunk1))
        val status = parser.assemble(chunk2)

        assertNotNull("Should parse frame even when 0x7E and 0xA1 are in separate chunks", status)
        assertEquals(8, status?.cellVoltages?.size)
        assertEquals(27.83, status?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testGluedPacketsInSingleChunk() {
        val parser = AntBmsParser()
        val frame1 = createSample8SFrame()
        val frame2 = createSample8SFrame()
        // Change voltage in frame2 to 28.00V = 2800 = 0x0AF0
        frame2[58 + 4] = 0xF0.toByte()
        frame2[58 + 5] = 0x0A.toByte()

        val glued = frame1 + frame2
        val status = parser.assemble(glued)

        assertNotNull("Should parse glued packets in single chunk", status)
        assertEquals("Should return latest parsed status from glued chunk", 28.00, status?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testNoiseBeforeHeader() {
        val parser = AntBmsParser()
        val noise = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val frame = createSample8SFrame()

        val combined = noise + frame
        val status = parser.assemble(combined)

        assertNotNull("Should discard noise and parse valid frame", status)
        assertEquals(8, status?.cellVoltages?.size)
    }

    @Test
    fun testNegativeCurrentAndTemperature() {
        val parser = AntBmsParser()
        val frame = createSample8SFrame()
        val base = 58

        // Temp 1 = -10 C = 0xFFF6
        val tNeg = -10
        frame[50] = (tNeg and 0xFF).toByte()
        frame[51] = ((tNeg shr 8) and 0xFF).toByte()

        // Current = -15.5A = -155 = 0xFF65
        val curNeg = -155
        frame[base + 6] = (curNeg and 0xFF).toByte()
        frame[base + 7] = ((curNeg shr 8) and 0xFF).toByte()

        val status = parser.assemble(frame)
        assertNotNull(status)
        assertEquals(-10, status?.temperatures?.get(0))
        assertEquals(-15.5, status?.current ?: 0.0, 0.001)
    }

    @Test
    fun testOversizedChunkIgnored() {
        val parser = AntBmsParser()
        val oversizedData = ByteArray(2048)
        val status = parser.assemble(oversizedData)
        assertNull("Oversized chunks exceeding buffer capacity should be safely ignored without throwing", status)
    }

    @Test
    fun testParseTotalCycleInvalidOrMissing() {
        val parser = AntBmsParser()
        val frame = createSample8SFrame()
        val base = 58
        // Fill base + 24 with 0xFFFFFFFF (uninitialized/invalid)
        frame[base + 24] = 0xFF.toByte()
        frame[base + 25] = 0xFF.toByte()
        frame[base + 26] = 0xFF.toByte()
        frame[base + 27] = 0xFF.toByte()

        val status = parser.assemble(frame)
        assertNotNull(status)
        assertNull("Invalid 0xFFFFFFFF total cycle should result in null totalCycleAh", status?.totalCycleAh)
    }

    private fun createSample8SFrame(): ByteArray {
        val frame = ByteArray(114)
        frame[0] = 0x7E.toByte()
        frame[1] = 0xA1.toByte()
        frame[2] = 0x11.toByte()
        frame[5] = 104.toByte()
        frame[8] = 4.toByte()
        frame[9] = 8.toByte()

        for (i in 0 until 8) {
            val cellMv = 3470 + i * 2
            frame[34 + i * 2] = (cellMv and 0xFF).toByte()
            frame[34 + i * 2 + 1] = ((cellMv shr 8) and 0xFF).toByte()
        }

        for (i in 0 until 4) {
            val t = 22 + i
            frame[50 + i * 2] = (t and 0xFF).toByte()
            frame[50 + i * 2 + 1] = ((t shr 8) and 0xFF).toByte()
        }

        val base = 58
        frame[base + 0] = 29.toByte()
        frame[base + 2] = 27.toByte()
        frame[base + 4] = 0xDF.toByte() // 27.83V
        frame[base + 5] = 0x0A.toByte()
        frame[base + 6] = 50.toByte() // 5.0A
        frame[base + 7] = 0.toByte()
        frame[base + 8] = 100.toByte()
        frame[base + 12] = 1.toByte()
        frame[base + 13] = 1.toByte()
        frame[base + 14] = 4.toByte()

        frame[112] = 0xAA.toByte()
        frame[113] = 0x55.toByte()
        return frame
    }
}


package com.figago.shared.bms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JbdBmsParserTest {

    // Vector 1: Real dump of Command 0x03 (Basic Information, 4S battery, 3 NTCs)
    // DD 03 00 1D 06 0B 00 00 01 ED 01 F4 00 00 2C 7C 00 00 00 00 10 00 80 63 02 04 03 0B A0 0B 9D 0B 98 FA 55 77
    private val vector1BasicInfo = byteArrayOf(
        0xDD.toByte(), 0x03, 0x00, 0x1D,
        0x06, 0x0B,             // Voltage: 0x060B = 1547 (15.47 V)
        0x00, 0x00,             // Current: 0x0000 = 0 (0.00 A)
        0x01, 0xED.toByte(),     // Residual Capacity: 0x01ED = 493 (4.93 Ah)
        0x01, 0xF4.toByte(),     // Nominal Capacity: 0x01F4 = 500 (5.00 Ah)
        0x00, 0x00,             // Cycle Life: 0
        0x2C, 0x7C,             // Production Date: 0x2C7C
        0x00, 0x00,             // Balanced State Low
        0x00, 0x00,             // Balanced State High
        0x10, 0x00,             // Protection State: 0x1000 (MOS Lock)
        0x80.toByte(),          // Software Version: 0x80
        0x63,                   // RSOC: 99%
        0x02,                   // FET: bit 1 set (discharge ON, charge OFF)
        0x04,                   // Cells: 4S
        0x03,                   // NTC count: 3
        0x0B, 0xA0.toByte(),     // NTC 1: 0x0BA0 = 2976 (24.5 °C -> 25 °C)
        0x0B, 0x9D.toByte(),     // NTC 2: 0x0B9D = 2973 (24.2 °C -> 24 °C)
        0x0B, 0x98.toByte(),     // NTC 3: 0x0B98 = 2968 (23.7 °C -> 24 °C)
        0xFA.toByte(), 0x55,     // Checksum: 0xFA55
        0x77                    // Stop Byte
    )

    // Vector 2: Real dump of Command 0x04 (Cell Voltages, 4 cells)
    // DD 04 00 08 0F 23 0F 1C 0F 12 0F 1D FF 4E 77
    private val vector2CellVoltages = byteArrayOf(
        0xDD.toByte(), 0x04, 0x00, 0x08,
        0x0F, 0x23,             // Cell 1: 0x0F23 = 3875 mV (3.875 V)
        0x0F, 0x1C,             // Cell 2: 0x0F1C = 3868 mV (3.868 V)
        0x0F, 0x12,             // Cell 3: 0x0F12 = 3858 mV (3.858 V)
        0x0F, 0x1D,             // Cell 4: 0x0F1D = 3869 mV (3.869 V)
        0xFF.toByte(), 0x4E,     // Checksum: 0xFF4E
        0x77                    // Stop Byte
    )

    @Test
    fun testParseBasicInfoCommand03() {
        val parser = JbdBmsParser()
        val status = parser.assemble(vector1BasicInfo)

        assertNotNull("Status must not be null for valid 0x03 frame", status)
        assertEquals(15.47, status?.voltage ?: 0.0, 0.001)
        assertEquals(0.00, status?.current ?: 0.0, 0.001)
        assertEquals(99, status?.soc)
        assertEquals(4.93, status?.remainingCapacityAh ?: 0.0, 0.001)
        assertEquals(4.93, status?.capacityAh ?: 0.0, 0.001)
        assertEquals(5.00, status?.totalCapacityAh ?: 0.0, 0.001)

        assertFalse(status?.isChargeMosOpen ?: true)
        assertTrue(status?.isDischargeMosOpen ?: false)

        assertEquals(3, status?.temperatures?.size)
        assertEquals(25, status?.temperatures?.get(0))
        assertEquals(24, status?.temperatures?.get(1))
        assertEquals(24, status?.temperatures?.get(2))
        assertEquals(25, status?.temperature)

        // Diagnostic fields
        assertEquals(0x1000, parser.lastProtectionState)
        assertEquals(0, parser.lastCycleCount)
        assertEquals(0.0, status?.totalCycleAh ?: 0.0, 0.001)
        assertEquals(0x80, parser.lastSoftwareVersion)
        assertEquals(0x2C7C, parser.lastProductionDate)
    }

    @Test
    fun testParseCellVoltagesCommand04() {
        val parser = JbdBmsParser()
        val status = parser.assemble(vector2CellVoltages)

        assertNotNull("Status must not be null for valid 0x04 frame", status)
        assertEquals(4, status?.cellVoltages?.size)
        assertEquals(3.875, status?.cellVoltages?.get(0) ?: 0.0, 0.001)
        assertEquals(3.868, status?.cellVoltages?.get(1) ?: 0.0, 0.001)
        assertEquals(3.858, status?.cellVoltages?.get(2) ?: 0.0, 0.001)
        assertEquals(3.869, status?.cellVoltages?.get(3) ?: 0.0, 0.001)

        assertEquals(3.875, status?.maxCellVoltage ?: 0.0, 0.001)
        assertEquals(3.858, status?.minCellVoltage ?: 0.0, 0.001)
        // Standalone pack voltage computed as sum of cells: 3.875 + 3.868 + 3.858 + 3.869 = 15.470 V
        assertEquals(15.470, status?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testSequentialAccumulation03Then04() {
        val parser = JbdBmsParser()

        // 1. Deliver 0x03
        val statusAfter03 = parser.assemble(vector1BasicInfo)
        assertNotNull(statusAfter03)
        assertEquals(15.47, statusAfter03?.voltage ?: 0.0, 0.001)
        assertEquals(99, statusAfter03?.soc)
        assertTrue(statusAfter03?.cellVoltages?.isEmpty() == true)

        // 2. Deliver 0x04
        val statusAfter04 = parser.assemble(vector2CellVoltages)
        assertNotNull(statusAfter04)
        // Combined state: preserves 0x03 telemetry AND includes cell voltages
        assertEquals(15.47, statusAfter04?.voltage ?: 0.0, 0.001)
        assertEquals(99, statusAfter04?.soc)
        assertEquals(4, statusAfter04?.cellVoltages?.size)
        assertEquals(3.875, statusAfter04?.maxCellVoltage ?: 0.0, 0.001)
        assertEquals(3.858, statusAfter04?.minCellVoltage ?: 0.0, 0.001)
        assertEquals(25, statusAfter04?.temperature)
    }

    @Test
    fun testGluedPacketsInSingleChunk() {
        val parser = JbdBmsParser()
        val glued = vector1BasicInfo + vector2CellVoltages

        val status = parser.assemble(glued)
        assertNotNull("Glued packets must be parsed successfully", status)
        assertEquals(15.47, status?.voltage ?: 0.0, 0.001)
        assertEquals(99, status?.soc)
        assertEquals(4, status?.cellVoltages?.size)
        assertEquals(3.875, status?.maxCellVoltage ?: 0.0, 0.001)
        assertEquals(3.858, status?.minCellVoltage ?: 0.0, 0.001)
    }

    @Test
    fun testBleFragmentationSplitAcrossChunks() {
        val parser = JbdBmsParser()
        // Split 36-byte vector1 into chunk of 20 bytes and chunk of 16 bytes (typical MTU 23)
        val chunk1 = vector1BasicInfo.copyOfRange(0, 20)
        val chunk2 = vector1BasicInfo.copyOfRange(20, vector1BasicInfo.size)

        val statusChunk1 = parser.assemble(chunk1)
        assertNull("First 20 bytes should not produce complete status", statusChunk1)

        val statusChunk2 = parser.assemble(chunk2)
        assertNotNull("Second chunk should complete frame parsing", statusChunk2)
        assertEquals(15.47, statusChunk2?.voltage ?: 0.0, 0.001)
        assertEquals(99, statusChunk2?.soc)
    }

    @Test
    fun testNoiseBeforeHeader() {
        val parser = JbdBmsParser()
        val noise = byteArrayOf(0x12, 0x88.toByte(), 0xFF.toByte(), 0x00, 0x55)
        val combined = noise + vector2CellVoltages

        val status = parser.assemble(combined)
        assertNotNull("Parser must skip noise and decode valid frame", status)
        assertEquals(4, status?.cellVoltages?.size)
        assertEquals(3.875, status?.maxCellVoltage ?: 0.0, 0.001)
    }

    @Test
    fun testNegativeCurrentAndTemperature() {
        val parser = JbdBmsParser()

        // Construct 0x03 frame with negative current (-15.50 A -> raw -1550 = 0xF9F2)
        // and negative temperature (-10.5 °C -> raw 2626 = 0x0A42)
        val frameWithoutCrc = byteArrayOf(
            0xDD.toByte(), 0x03, 0x00, 0x19, // Length 25 (23 + 2 bytes for 1 NTC)
            0x05, 0xDC.toByte(),             // Voltage: 1500 -> 15.00 V
            0xF9.toByte(), 0xF2.toByte(),     // Current: -1550 -> -15.50 A
            0x01, 0xF4.toByte(),             // RemCap: 500 -> 5.00 Ah
            0x01, 0xF4.toByte(),             // NomCap: 500 -> 5.00 Ah
            0x00, 0x0A,                     // Cycles: 10
            0x2C, 0x7C,                     // ProdDate
            0x00, 0x00,                     // BalLow
            0x00, 0x00,                     // BalHigh
            0x00, 0x00,                     // Prot
            0x10,                           // SwVer
            0x50,                           // RSOC: 80%
            0x03,                           // FET: both charge and discharge ON
            0x04,                           // Cells: 4
            0x01,                           // NTC count: 1
            0x0A, 0x42                      // NTC 1: 2626 -> -10.5 °C
        )

        // Checksum covers status(byte 2) + length(byte 3) + data(bytes 4..28)
        val crcPayload = frameWithoutCrc.copyOfRange(2, frameWithoutCrc.size)
        val crc = JbdBmsParser.calculateChecksum(crcPayload)
        val frame = frameWithoutCrc + crc + byteArrayOf(0x77)

        val status = parser.assemble(frame)
        assertNotNull(status)
        assertEquals(-15.50, status?.current ?: 0.0, 0.001)
        assertEquals(15.00, status?.voltage ?: 0.0, 0.001)
        assertEquals(-10, status?.temperature)
        assertEquals(1, status?.temperatures?.size)
        assertEquals(-10, status?.temperatures?.get(0))
        assertTrue(status?.isChargeMosOpen ?: false)
        assertTrue(status?.isDischargeMosOpen ?: false)
        assertEquals(10, parser.lastCycleCount)
        assertEquals(50.0, status?.totalCycleAh ?: 0.0, 0.001) // 10 cycles * 5.00 Ah = 50.0 Ah
    }

    @Test
    fun testChecksumCalculation() {
        // Test basic info request checksum (Cmd 0x03, Len 0x00) -> 0x10000 - 3 = 0xFFFD
        val basicPayload = byteArrayOf(0x03, 0x00)
        val basicCrc = JbdBmsParser.calculateChecksum(basicPayload)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFD.toByte()), basicCrc)

        // Test cell voltages request checksum (Cmd 0x04, Len 0x00) -> 0x10000 - 4 = 0xFFFC
        val cellPayload = byteArrayOf(0x04, 0x00)
        val cellCrc = JbdBmsParser.calculateChecksum(cellPayload)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFC.toByte()), cellCrc)
    }

    @Test
    fun testRequestGenerators() {
        val basicReq = JbdBmsParser.createBasicInfoRequest()
        assertArrayEquals(
            byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00, 0xFF.toByte(), 0xFD.toByte(), 0x77),
            basicReq
        )
        assertArrayEquals(basicReq, JbdBmsParser.getStatusCommand())

        val cellReq = JbdBmsParser.createCellVoltagesRequest()
        assertArrayEquals(
            byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00, 0xFF.toByte(), 0xFC.toByte(), 0x77),
            cellReq
        )
        assertArrayEquals(cellReq, JbdBmsParser.getCellVoltagesCommand())

        val deviceNameReq = JbdBmsParser.createDeviceNameRequest()
        assertArrayEquals(
            byteArrayOf(0xDD.toByte(), 0xA5.toByte(), 0x05, 0x00, 0xFF.toByte(), 0xFB.toByte(), 0x77),
            deviceNameReq
        )
    }

    @Test
    fun testCorruptedChecksumRejection() {
        val parser = JbdBmsParser()
        val corrupted = vector1BasicInfo.clone()
        // Invalidate CRC byte
        corrupted[corrupted.size - 2] = (corrupted[corrupted.size - 2] + 1).toByte()

        val status = parser.assemble(corrupted)
        assertNull("Corrupted checksum packet must be rejected", status)
    }

    @Test
    fun testInvalidStopByteRejection() {
        val parser = JbdBmsParser()
        val corrupted = vector1BasicInfo.clone()
        // Invalidate Stop byte (0x77 -> 0x00)
        corrupted[corrupted.size - 1] = 0x00

        val status = parser.assemble(corrupted)
        assertNull("Invalid stop byte packet must be rejected", status)
    }

    @Test
    fun testResetClearsBufferAndCache() {
        val parser = JbdBmsParser()
        parser.assemble(vector1BasicInfo)
        assertEquals(0x1000, parser.lastProtectionState)

        parser.reset()
        assertNull(parser.lastProtectionState)
        assertNull(parser.lastCycleCount)

        // After reset, feeding 0x04 should not contain previous 0x03 basic info
        val status04 = parser.assemble(vector2CellVoltages)
        assertNotNull(status04)
        assertEquals(0, status04?.soc)
        assertNull(status04?.capacityAh)
    }

    @Test
    fun testOversizedChunkIgnored() {
        val parser = JbdBmsParser()
        val hugeData = ByteArray(2048)
        val status = parser.assemble(hugeData)
        assertNull("Oversized chunk should be rejected without error", status)
    }

    @Test
    fun testDeviceNameCommand05() {
        val parser = JbdBmsParser()
        val nameBytes = "JBD-SP04S020".toByteArray(Charsets.US_ASCII)
        val dataLen = nameBytes.size
        val frameWithoutCrc = byteArrayOf(0xDD.toByte(), 0x05, 0x00, dataLen.toByte()) + nameBytes
        val crcPayload = frameWithoutCrc.copyOfRange(2, frameWithoutCrc.size)
        val crc = JbdBmsParser.calculateChecksum(crcPayload)
        val frame = frameWithoutCrc + crc + byteArrayOf(0x77)

        parser.assemble(frame)
        assertEquals("JBD-SP04S020", parser.lastDeviceName)
    }

    @Test
    fun testNegativeCurrentDischarge0xFF66AndNegativeTemperatures() {
        val parser = JbdBmsParser()

        // Test -1.54 A (0xFF 0x66) and negative temperatures (< 273.1 K):
        // 0xFF66 signed 16-bit = -154 -> -1.54 A
        // NTC 1: 2681 -> -5.0 °C
        // NTC 2: 2531 -> -20.0 °C
        // NTC 3: 2730 -> -0.1 °C (rounded 0 °C)
        val frameWithoutCrc = byteArrayOf(
            0xDD.toByte(), 0x03, 0x00, 0x1D, // Length 29 (23 base + 6 bytes for 3 NTCs)
            0x05, 0xDC.toByte(),             // Voltage: 1500 -> 15.00 V
            0xFF.toByte(), 0x66.toByte(),     // Current: 0xFF66 -> -154 -> -1.54 A
            0x01, 0xF4.toByte(),             // RemCap: 500 -> 5.00 Ah
            0x01, 0xF4.toByte(),             // NomCap: 500 -> 5.00 Ah
            0x00, 0x05,                     // Cycles: 5
            0x2C, 0x7C,                     // ProdDate
            0x00, 0x00,                     // BalLow
            0x00, 0x00,                     // BalHigh
            0x00, 0x00,                     // Prot
            0x10,                           // SwVer
            0x55,                           // RSOC: 85%
            0x02,                           // FET: discharge ON, charge OFF
            0x04,                           // Cells: 4
            0x03,                           // NTC count: 3
            0x0A, 0x79.toByte(),             // NTC 1: 2681 -> -5.0 °C
            0x09, 0xE3.toByte(),             // NTC 2: 2531 -> -20.0 °C
            0x0A, 0xAA.toByte()              // NTC 3: 2730 -> -0.1 °C
        )

        val crcPayload = frameWithoutCrc.copyOfRange(2, frameWithoutCrc.size)
        val crc = JbdBmsParser.calculateChecksum(crcPayload)
        val frame = frameWithoutCrc + crc + byteArrayOf(0x77)

        // Test through parse() alias as well
        val status = parser.parse(frame)
        assertNotNull("Status must not be null", status)
        assertEquals(-1.54, status?.current ?: 0.0, 0.001)
        assertEquals(15.00, status?.voltage ?: 0.0, 0.001)
        assertEquals(85, status?.soc)

        // Temperature checks
        assertEquals(-5, status?.temperature)
        assertEquals(3, status?.temperatures?.size)
        assertEquals(-5, status?.temperatures?.get(0))
        assertEquals(-20, status?.temperatures?.get(1))
        assertEquals(0, status?.temperatures?.get(2))

        val exactTemps = parser.lastTemperaturesCelsius
        assertEquals(3, exactTemps.size)
        assertEquals(-5.0, exactTemps[0], 0.01)
        assertEquals(-20.0, exactTemps[1], 0.01)
        assertEquals(-0.1, exactTemps[2], 0.01)
    }

    @Test
    fun testBleFragmentationMultiChunkSequentialParse() {
        val parser = JbdBmsParser()

        // Split vector1 (36 bytes) into 3 MTU fragments: 20 bytes, 10 bytes, 6 bytes
        val chunk1 = vector1BasicInfo.copyOfRange(0, 20)
        val chunk2 = vector1BasicInfo.copyOfRange(20, 30)
        val chunk3 = vector1BasicInfo.copyOfRange(30, vector1BasicInfo.size)

        val res1 = parser.parse(chunk1)
        assertNull("Chunk 1 (20 bytes) is incomplete", res1)

        val res2 = parser.parse(chunk2)
        assertNull("Chunk 2 (10 bytes) is incomplete", res2)

        val res3 = parser.parse(chunk3)
        assertNotNull("Chunk 3 completes the frame", res3)
        assertEquals(15.47, res3?.voltage ?: 0.0, 0.001)
        assertEquals(99, res3?.soc)
    }

    @Test
    fun testFalseStartByteBeforeValidFrame() {
        val parser = JbdBmsParser()
        // Starts with 0xDD but invalid subsequent bytes, followed by a valid frame
        val falseStart = byteArrayOf(0xDD.toByte(), 0x12, 0x34, 0x56)
        val stream = falseStart + vector2CellVoltages

        val status = parser.parse(stream)
        assertNotNull("Parser must resync on next valid 0xDD marker", status)
        assertEquals(4, status?.cellVoltages?.size)
        assertEquals(3.875, status?.maxCellVoltage ?: 0.0, 0.001)
    }

    @Test
    fun testBmsErrorStatusIgnoredSafely() {
        val parser = JbdBmsParser()

        // BMS sends status = 0x80 (Error response)
        val errorFrame = byteArrayOf(
            0xDD.toByte(), 0x03, 0x80.toByte(), 0x00, // Status: 0x80, Length: 0
            0xFF.toByte(), 0x80.toByte(),             // CRC: 0x10000 - 0x0080 = 0xFF80
            0x77                                     // Stop Byte
        )

        val status = parser.parse(errorFrame)
        assertNull("Error response (status 0x80) should not produce AntBmsStatus", status)
    }

    @Test
    fun test16SCellVoltagesParsing() {
        val parser = JbdBmsParser()

        // 16 cells -> 32 bytes data length
        val cellCount = 16
        val cellMillivolts = (0 until cellCount).map { i -> 3200 + i * 10 } // 3200 mV to 3350 mV
        val dataBytes = ByteArray(cellCount * 2)
        for (i in 0 until cellCount) {
            val mv = cellMillivolts[i]
            dataBytes[i * 2] = ((mv shr 8) and 0xFF).toByte()
            dataBytes[i * 2 + 1] = (mv and 0xFF).toByte()
        }

        val frameWithoutCrc = byteArrayOf(
            0xDD.toByte(), 0x04, 0x00, dataBytes.size.toByte()
        ) + dataBytes

        val crcPayload = frameWithoutCrc.copyOfRange(2, frameWithoutCrc.size)
        val crc = JbdBmsParser.calculateChecksum(crcPayload)
        val frame = frameWithoutCrc + crc + byteArrayOf(0x77)

        val status = parser.parse(frame)
        assertNotNull("16S cell voltages frame should be parsed", status)
        assertEquals(16, status?.cellVoltages?.size)
        assertEquals(3.200, status?.minCellVoltage ?: 0.0, 0.001)
        assertEquals(3.350, status?.maxCellVoltage ?: 0.0, 0.001)
        val expectedPackVoltage = cellMillivolts.sum() * 0.001
        assertEquals(expectedPackVoltage, status?.voltage ?: 0.0, 0.001)
    }

    @Test
    fun testProtectionFlagsBitmask() {
        val parser = JbdBmsParser()

        // Set flags: PROT_CELL_OVERVOLTAGE (bit 0), PROT_SHORT_CIRCUIT (bit 10), PROT_MOS_LOCK (bit 12)
        val protMask = JbdBmsParser.PROT_CELL_OVERVOLTAGE or
                       JbdBmsParser.PROT_SHORT_CIRCUIT or
                       JbdBmsParser.PROT_MOS_LOCK
        val protHigh = ((protMask shr 8) and 0xFF).toByte()
        val protLow = (protMask and 0xFF).toByte()

        val frameWithoutCrc = byteArrayOf(
            0xDD.toByte(), 0x03, 0x00, 0x17, // Length 23 (no NTC)
            0x05, 0xDC.toByte(),             // Voltage: 15.00 V
            0x00, 0x00,                     // Current: 0.00 A
            0x01, 0xF4.toByte(),             // RemCap: 5.00 Ah
            0x01, 0xF4.toByte(),             // NomCap: 5.00 Ah
            0x00, 0x01,                     // Cycles: 1
            0x2C, 0x7C,                     // ProdDate
            0x00, 0x00,                     // BalLow
            0x00, 0x00,                     // BalHigh
            protHigh, protLow,               // Protection bitmask
            0x10,                           // SwVer
            0x64,                           // RSOC: 100%
            0x03,                           // FET: both ON
            0x04,                           // Cells: 4
            0x00                            // NTC count: 0
        )

        val crcPayload = frameWithoutCrc.copyOfRange(2, frameWithoutCrc.size)
        val crc = JbdBmsParser.calculateChecksum(crcPayload)
        val frame = frameWithoutCrc + crc + byteArrayOf(0x77)

        val status = parser.parse(frame)
        assertNotNull(status)
        assertEquals(protMask, parser.lastProtectionState)
        assertTrue((parser.lastProtectionState!! and JbdBmsParser.PROT_CELL_OVERVOLTAGE) != 0)
        assertTrue((parser.lastProtectionState!! and JbdBmsParser.PROT_SHORT_CIRCUIT) != 0)
        assertTrue((parser.lastProtectionState!! and JbdBmsParser.PROT_MOS_LOCK) != 0)
        assertFalse((parser.lastProtectionState!! and JbdBmsParser.PROT_CELL_UNDERVOLTAGE) != 0)
    }

    @Test
    fun testPartialBufferFollowedByReset() {
        val parser = JbdBmsParser()

        // Feed first 20 bytes of basic info
        val partial = vector1BasicInfo.copyOfRange(0, 20)
        assertNull(parser.parse(partial))

        // Reset before frame completion
        parser.reset()

        // Feed clean cell voltages frame
        val status = parser.parse(vector2CellVoltages)
        assertNotNull("Cell voltages should parse cleanly after reset", status)
        assertEquals(4, status?.cellVoltages?.size)
        assertNull("Capacity should be null since 0x03 was discarded by reset", status?.capacityAh)
    }

    @Test
    fun testEnterFactoryModeRequestMatchesReferenceFrame() {
        val req = JbdBmsParser.createEnterFactoryModeRequest()
        val expected = byteArrayOf(
            0xDD.toByte(), 0x5A, 0x00, 0x02, 0x56, 0x78, 0xFF.toByte(), 0x30, 0x77
        )
        assertArrayEquals(expected, req)
    }

    @Test
    fun testExitFactoryModeRequest() {
        val req = JbdBmsParser.createExitFactoryModeRequest()
        // Sum: 0x01 + 0x02 + 0x28 + 0x28 = 0x53 -> CRC = 0x10000 - 0x53 = 0xFFAD
        val expected = byteArrayOf(
            0xDD.toByte(), 0x5A, 0x01, 0x02, 0x28, 0x28, 0xFF.toByte(), 0xAD.toByte(), 0x77
        )
        assertArrayEquals(expected, req)
    }

    @Test
    fun testAuthPasswordRequest() {
        val req = JbdBmsParser.createAuthPasswordRequest("123456")
        // Sum: 0x06 + 0x06 + (0x31+0x32+0x33+0x34+0x35+0x36 = 0x0135) = 0x0141 -> CRC = 0xFEBF
        val expected = byteArrayOf(
            0xDD.toByte(), 0x5A, 0x06, 0x06,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36,
            0xFE.toByte(), 0xBF.toByte(), 0x77
        )
        assertArrayEquals(expected, req)
    }

    @Test
    fun testSetPasswordRequest() {
        val req = JbdBmsParser.createSetPasswordRequest("123456")
        // Sum: 0x07 + 0x06 + 0x0135 = 0x0142 -> CRC = 0xFEBE
        val expected = byteArrayOf(
            0xDD.toByte(), 0x5A, 0x07, 0x06,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36,
            0xFE.toByte(), 0xBE.toByte(), 0x77
        )
        assertArrayEquals(expected, req)
    }

    @Test
    fun testClearPasswordRequest() {
        val req = JbdBmsParser.createClearPasswordRequest()
        // Sum: 0x09 + 0x02 + 0x00 + 0x00 = 0x0B -> CRC = 0xFFF5
        val expected = byteArrayOf(
            0xDD.toByte(), 0x5A, 0x09, 0x02, 0x00, 0x00, 0xFF.toByte(), 0xF5.toByte(), 0x77
        )
        assertArrayEquals(expected, req)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testAuthPasswordRejectsShortPassword() {
        JbdBmsParser.createAuthPasswordRequest("12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetPasswordRejectsLongPassword() {
        JbdBmsParser.createSetPasswordRequest("1234567")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSetPasswordRejectsNonAsciiCharacters() {
        JbdBmsParser.createSetPasswordRequest("12345Б")
    }

    @Test
    fun testCommandResponseValidation() {
        // Valid OK response for REG_ENTER_FACTORY (0x00): DD 00 00 00 00 00 77
        val okEnterFactory = byteArrayOf(
            0xDD.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x77
        )
        assertTrue(JbdBmsParser.isCommandResponseOk(okEnterFactory))
        assertTrue(JbdBmsParser.isCommandResponseOk(okEnterFactory, JbdBmsParser.REG_ENTER_FACTORY))
        assertFalse(JbdBmsParser.isCommandResponseOk(okEnterFactory, JbdBmsParser.REG_SET_PASSWORD))

        // Valid OK response for REG_SET_PASSWORD (0x07): DD 07 00 00 00 00 77
        val okSetPassword = byteArrayOf(
            0xDD.toByte(), 0x07, 0x00, 0x00, 0x00, 0x00, 0x77
        )
        assertTrue(JbdBmsParser.isCommandResponseOk(okSetPassword, JbdBmsParser.REG_SET_PASSWORD))

        // Error response (Status 0x80) for REG_SET_PASSWORD (0x07): DD 07 80 00 FF 80 77
        val errorResponse = byteArrayOf(
            0xDD.toByte(), 0x07, JbdBmsParser.STATUS_ERROR, 0x00, 0xFF.toByte(), 0x80.toByte(), 0x77
        )
        assertFalse(JbdBmsParser.isCommandResponseOk(errorResponse))
        assertFalse(JbdBmsParser.isCommandResponseOk(errorResponse, JbdBmsParser.REG_SET_PASSWORD))

        // Corrupted CRC response
        val badCrcResponse = byteArrayOf(
            0xDD.toByte(), 0x07, 0x00, 0x00, 0x00, 0x01, 0x77
        )
        assertFalse(JbdBmsParser.isCommandResponseOk(badCrcResponse, JbdBmsParser.REG_SET_PASSWORD))
    }

    @Test
    fun testDeviceNameCommand05ErrorResponseMarksResolved() {
        val parser = JbdBmsParser()
        assertFalse(parser.isDeviceNameResolved)

        // DD 05 80 00 FF 80 77
        val errorDeviceNameFrame = byteArrayOf(
            0xDD.toByte(), 0x05, 0x80.toByte(), 0x00, 0xFF.toByte(), 0x80.toByte(), 0x77
        )
        val status = parser.assemble(errorDeviceNameFrame)
        assertNull(status)
        assertTrue(parser.isDeviceNameResolved)
        assertNull(parser.lastDeviceName)

        parser.reset()
        assertFalse(parser.isDeviceNameResolved)
    }
}

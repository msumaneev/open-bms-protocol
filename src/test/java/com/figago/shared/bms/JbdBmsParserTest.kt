package com.figago.shared.bms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        0x0B, 0xA0.toByte(),     // NTC 1: 0x0BA0 = 2976 (24.5 degC -> 25 degC)
        0x0B, 0x9D.toByte(),     // NTC 2: 0x0B9D = 2973 (24.2 degC -> 24 degC)
        0x0B, 0x98.toByte(),     // NTC 3: 0x0B98 = 2968 (23.7 degC -> 24 degC)
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

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ByteArray(clean.size) { i -> clean[i].toInt(16).toByte() }
    }

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
        // and negative temperature (-10.5 degC -> raw 2626 = 0x0A42)
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
            0x0A, 0x42                      // NTC 1: 2626 -> -10.5 degC
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
        assertNotNull(parser.lastCommandResponse)

        parser.reset()
        assertNull(parser.lastProtectionState)
        assertNull(parser.lastCycleCount)
        assertNull(parser.lastCommandResponse)

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
        // NTC 1: 2681 -> -5.0 degC
        // NTC 2: 2531 -> -20.0 degC
        // NTC 3: 2730 -> -0.1 degC (rounded 0 degC)
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
            0x0A, 0x79.toByte(),             // NTC 1: 2681 -> -5.0 degC
            0x09, 0xE3.toByte(),             // NTC 2: 2531 -> -20.0 degC
            0x0A, 0xAA.toByte()              // NTC 3: 2730 -> -0.1 degC
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
        assertNotNull("Valid CRC error frame must still be captured in lastCommandResponse", parser.lastCommandResponse)
        assertEquals(JbdBmsParser.CMD_BASIC_INFO, parser.lastCommandResponse?.register)
        assertEquals(0x80.toByte(), parser.lastCommandResponse?.status)
        assertFalse(parser.lastCommandResponse?.isOk ?: true)
    }

    @Test
    fun testNonZeroStatus0x01RejectedAsError() {
        val parser = JbdBmsParser()

        // BMS sends non-zero status = 0x01 on register 0x07 (CRC = 0x10000 - 0x0001 = 0xFFFF)
        val status01Frame = byteArrayOf(
            0xDD.toByte(), 0x07, 0x01, 0x00,
            0xFF.toByte(), 0xFF.toByte(),
            0x77
        )
        assertFalse(
            "Any non-zero status (e.g. 0x01) must be rejected by isCommandResponseOk",
            JbdBmsParser.isCommandResponseOk(status01Frame, JbdBmsParser.REG_SET_PASSWORD)
        )

        val status = parser.assemble(status01Frame)
        assertNull(status)
        assertNotNull(parser.lastCommandResponse)
        assertEquals(JbdBmsParser.REG_SET_PASSWORD, parser.lastCommandResponse?.register)
        assertEquals(0x01.toByte(), parser.lastCommandResponse?.status)
        assertFalse(parser.lastCommandResponse?.isOk ?: true)
    }

    @Test
    fun testOddLengthCellVoltages0x04Rejected() {
        val parser = JbdBmsParser()

        // Construct 0x04 frame with odd length (3 bytes = 1.5 cells, invalid)
        val oddCellFramePrefix = byteArrayOf(
            0xDD.toByte(), 0x04, 0x00, 0x03,
            0x0F, 0x23, 0x0F
        )
        val crc = JbdBmsParser.calculateChecksum(oddCellFramePrefix.copyOfRange(2, oddCellFramePrefix.size))
        val oddCellFrame = oddCellFramePrefix + crc + byteArrayOf(0x77)

        assertFalse(
            "Odd byte count in 0x04 must be rejected by isCommandResponseOk",
            JbdBmsParser.isCommandResponseOk(oddCellFrame, JbdBmsParser.CMD_CELL_VOLTAGES)
        )
        assertNull("Odd byte count in 0x04 must be rejected by assemble", parser.assemble(oddCellFrame))
    }

    @Test
    fun testTrailingBytesRejectedInIsCommandResponseOk() {
        val okAck = byteArrayOf(
            0xDD.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x77
        )
        assertTrue(JbdBmsParser.isCommandResponseOk(okAck, JbdBmsParser.REG_ENTER_FACTORY))

        val withTrailing = okAck + byteArrayOf(0x00)
        assertFalse(
            "Frame with extra trailing bytes must be rejected by isCommandResponseOk",
            JbdBmsParser.isCommandResponseOk(withTrailing, JbdBmsParser.REG_ENTER_FACTORY)
        )
    }

    @Test
    fun testStreamingCommandAckAndGluedTelemetryInAssemble() {
        val parser = JbdBmsParser()
        val capturedResponses = mutableListOf<JbdBmsParser.CommandResponse>()
        parser.onCommandResponse = { capturedResponses.add(it) }

        val ackEnterFactory = hexToBytes("DD 00 00 00 00 00 77")
        val ackAuthPassword = hexToBytes("DD 06 00 00 00 00 77")
        val ackSetPassword = hexToBytes("DD 07 00 00 00 00 77")
        val ackClearPassword = hexToBytes("DD 09 00 00 00 00 77")
        val ackExitFactory = hexToBytes("DD 01 00 00 00 00 77")

        assertNull(parser.assemble(ackEnterFactory))
        assertEquals(JbdBmsParser.REG_ENTER_FACTORY, parser.lastCommandResponse?.register)
        assertTrue(parser.lastCommandResponse?.isOk == true)

        // Feed glued ACK (0x07) + Basic Info (0x03) in a single BLE chunk
        val gluedAckAnd03 = ackAuthPassword + ackSetPassword + ackClearPassword + ackExitFactory + vector1BasicInfo
        val status = parser.assemble(gluedAckAnd03)
        assertNotNull("Telemetry in glued ACK+0x03 stream must be returned", status)
        assertEquals(15.47, status?.voltage ?: 0.0, 0.001)
        assertEquals(6, capturedResponses.size)
        assertEquals(JbdBmsParser.REG_ENTER_FACTORY, capturedResponses[0].register)
        assertEquals(JbdBmsParser.REG_USE_PASSWORD, capturedResponses[1].register)
        assertEquals(JbdBmsParser.REG_SET_PASSWORD, capturedResponses[2].register)
        assertEquals(JbdBmsParser.REG_CLEAR_PASSWORD, capturedResponses[3].register)
        assertEquals(JbdBmsParser.REG_EXIT_FACTORY, capturedResponses[4].register)
        assertEquals(JbdBmsParser.CMD_BASIC_INFO, capturedResponses[5].register)
    }

    @Test
    fun testReferenceSampleDumpVectorsAre100PercentValid() {
        // Verify every single TX and RX frame from hardware-dumps/jbd_xiaoxiang_4s_sample.txt (C-01)
        val authTx = hexToBytes("DD 5A 00 02 56 78 FF 30 77")
        val authRx = hexToBytes("DD 00 00 00 00 00 77")
        assertArrayEquals(authTx, JbdBmsParser.createEnterFactoryModeRequest())
        assertTrue(JbdBmsParser.isCommandResponseOk(authRx, JbdBmsParser.REG_ENTER_FACTORY))

        val basicTx = hexToBytes("DD A5 03 00 FF FD 77")
        val basicRx = hexToBytes("DD 03 00 1D 06 0B 00 00 01 ED 01 F4 00 00 2C 7C 00 00 00 00 10 00 80 63 02 04 03 0B A0 0B 9D 0B 98 FA 55 77")
        assertArrayEquals(basicTx, JbdBmsParser.createBasicInfoRequest())
        assertTrue(JbdBmsParser.isCommandResponseOk(basicRx, JbdBmsParser.CMD_BASIC_INFO))

        val cellTx = hexToBytes("DD A5 04 00 FF FC 77")
        val cellRx = hexToBytes("DD 04 00 08 0F 23 0F 1C 0F 12 0F 1D FF 4E 77")
        assertArrayEquals(cellTx, JbdBmsParser.createCellVoltagesRequest())
        assertTrue(JbdBmsParser.isCommandResponseOk(cellRx, JbdBmsParser.CMD_CELL_VOLTAGES))

        val devNameTx = hexToBytes("DD A5 05 00 FF FB 77")
        val devNameRx = hexToBytes("DD 05 00 0E 4A 42 44 2D 53 50 30 34 53 30 33 34 2D 4C FC 86 77")
        assertArrayEquals(devNameTx, JbdBmsParser.createDeviceNameRequest())
        assertTrue(JbdBmsParser.isCommandResponseOk(devNameRx, JbdBmsParser.CMD_DEVICE_NAME))

        val exitTx = hexToBytes("DD 5A 01 02 28 28 FF AD 77")
        val exitRx = hexToBytes("DD 01 00 00 00 00 77")
        assertArrayEquals(exitTx, JbdBmsParser.createExitFactoryModeRequest())
        assertTrue(JbdBmsParser.isCommandResponseOk(exitRx, JbdBmsParser.REG_EXIT_FACTORY))

        // Replay entire RX sequence through streaming parser
        val parser = JbdBmsParser()
        val rxFrames = mutableListOf<JbdBmsParser.CommandResponse>()
        parser.onCommandResponse = { rxFrames.add(it) }

        parser.assemble(authRx)
        parser.assemble(basicRx)
        parser.assemble(cellRx)
        val finalStatus = parser.assemble(devNameRx)
        parser.assemble(exitRx)

        assertEquals(5, rxFrames.size)
        assertTrue(rxFrames.all { it.isOk })
        assertEquals("JBD-SP04S034-L", parser.lastDeviceName)
        assertNotNull(finalStatus)
        assertEquals(15.47, finalStatus?.voltage ?: 0.0, 0.001)
        assertEquals(4, finalStatus?.cellVoltages?.size)
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
        JbdBmsParser.createSetPasswordRequest("12345\u0080")
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

    @Test
    fun testJkBmsParserRejects300BytesOfNoiseAndValidatesCrc() {
        val jkParser = JkBmsParser()

        // 1. Feed 350 bytes of arbitrary noise (and even fake 0x4E 0x57 header with garbage)
        val noise = ByteArray(350) { i -> (i * 37 + 13).toByte() }
        noise[0] = 0x4E
        noise[1] = 0x57
        assertNull("JkBmsParser must NEVER return fake 50% SoC on 300+ bytes of noise", jkParser.assemble(noise))

        // 2. Construct a valid CRC-verified JK telemetry response frame
        // Payload tags:
        // 0x79 (cells, len 6 = 2 cells): [0x01, 0x0C, 0xE4 (3300mV), 0x02, 0x0C, 0xEE (3310mV)]
        // 0x80 (MOS temp): [0x00, 0x19 (25 degC)]
        // 0x83 (total voltage): [0x02, 0x96 (662 -> 6.62V)]
        // 0x84 (current): [0x00, 0x64 (100 -> 1.00A)]
        // 0x85 (SoC): [0x55 (85%)]
        val tlvPayload = byteArrayOf(
            0x79, 0x06, 0x01, 0x0C, 0xE4.toByte(), 0x02, 0x0C, 0xEE.toByte(),
            0x80.toByte(), 0x00, 0x19,
            0x83.toByte(), 0x02, 0x96.toByte(),
            0x84.toByte(), 0x00, 0x64,
            0x85.toByte(), 0x55
        )
        // Header (11 bytes) + TLV payload (19 bytes) + Tail (9 bytes: 0x68 + 6 bytes record/pad + 2 bytes CRC) = 39 bytes
        val frameLen = 11 + tlvPayload.size + 9
        val frame = ByteArray(frameLen)
        frame[0] = 0x4E
        frame[1] = 0x57
        frame[2] = ((frameLen shr 8) and 0xFF).toByte()
        frame[3] = (frameLen and 0xFF).toByte()
        frame[8] = 0x06 // Command: read all
        frame[9] = 0x02 // Frame type: BMS response
        System.arraycopy(tlvPayload, 0, frame, 11, tlvPayload.size)
        frame[frameLen - 9] = 0x68 // End-of-record marker
        val crc = JkBmsParser.calculateChecksum16(frame, frameLen - 2)
        frame[frameLen - 2] = ((crc shr 8) and 0xFF).toByte()
        frame[frameLen - 1] = (crc and 0xFF).toByte()

        // Corrupt CRC first -> must return null
        val badCrcFrame = frame.clone()
        badCrcFrame[frameLen - 1] = (badCrcFrame[frameLen - 1] + 1).toByte()
        assertNull("JkBmsParser must reject frame with invalid 16-bit checksum", jkParser.assemble(badCrcFrame))

        // Valid frame -> must decode accurately
        val status = jkParser.assemble(frame)
        assertNotNull("JkBmsParser must decode valid CRC-verified telemetry frame", status)
        assertEquals(85, status?.soc)
        assertEquals(6.62, status?.voltage ?: 0.0, 0.001)
        assertEquals(1.00, status?.current ?: 0.0, 0.001)
        assertEquals(2, status?.cellVoltages?.size)
        assertEquals(3.300, status?.cellVoltages?.get(0) ?: 0.0, 0.001)
        assertEquals(3.310, status?.cellVoltages?.get(1) ?: 0.0, 0.001)
        assertEquals(25, status?.temperature)
    }

    @Test
    fun testDalyBmsParserValidatesChecksumAndResyncsNoise() {
        val dalyParser = DalyBmsParser()

        // Valid 0x90 response:
        // Voltage: 26.8V -> 268 (0x010C)
        // Current: +15.0A -> 30000 + 150 = 30150 (0x75C6)
        // SoC: 82.0% -> 820 (0x0334)
        val validFrame = ByteArray(13)
        validFrame[0] = 0xA5.toByte()
        validFrame[1] = 0x01 // BMS address
        validFrame[2] = 0x90.toByte()
        validFrame[3] = 0x08
        validFrame[4] = 0x01
        validFrame[5] = 0x0C
        validFrame[6] = 0x00
        validFrame[7] = 0x00
        validFrame[8] = 0x75
        validFrame[9] = 0xC6.toByte()
        validFrame[10] = 0x03
        validFrame[11] = 0x34
        validFrame[12] = DalyBmsParser.calculateChecksum(validFrame)

        // 1. Corrupt checksum -> must be rejected
        val corruptedFrame = validFrame.clone()
        corruptedFrame[12] = (corruptedFrame[12] + 1).toByte()
        assertNull("DalyBmsParser must reject 0x90 frame with invalid checksum", dalyParser.assemble(corruptedFrame))

        // 2. Leading noise + corrupted false start + valid frame -> must resync and parse valid frame
        val stream = byteArrayOf(0x00, 0xFF.toByte(), 0xA5.toByte(), 0x01, 0x90.toByte()) + validFrame
        val status = dalyParser.assemble(stream)
        assertNotNull("DalyBmsParser must resync after noise and decode valid frame", status)
        assertEquals(82, status?.soc)
        assertEquals(26.8, status?.voltage ?: 0.0, 0.001)
        assertEquals(15.0, status?.current ?: 0.0, 0.001)
    }

    @Test
    fun testSampleDumpFileLineByLine() {
        val dumpFile = File("hardware-dumps/jbd_xiaoxiang_4s_sample.txt")
        assertTrue(
            "Sample dump file must exist at ${dumpFile.absolutePath}",
            dumpFile.exists() && dumpFile.isFile
        )

        val parser = JbdBmsParser()
        val capturedResponses = mutableListOf<JbdBmsParser.CommandResponse>()
        parser.onCommandResponse = { capturedResponses.add(it) }

        var txCount = 0
        var rxCount = 0
        var latestStatus: AntBmsStatus? = null

        dumpFile.readLines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("===")) {
                return@forEachIndexed
            }

            val txMarkerIdx = line.indexOf("TX:")
            val rxMarkerIdx = line.indexOf("RX:")

            when {
                txMarkerIdx != -1 -> {
                    val hexPayload = line.substring(txMarkerIdx + 3).trim()
                    val txBytes = hexToBytes(hexPayload)
                    assertTrue("Line ${index + 1} TX frame too short", txBytes.size >= 7)
                    assertEquals("Line ${index + 1} TX start byte must be 0xDD", JbdBmsParser.START_BYTE, txBytes[0])
                    assertTrue(
                        "Line ${index + 1} TX opcode must be 0xA5 or 0x5A",
                        txBytes[1] == JbdBmsParser.OP_READ || txBytes[1] == JbdBmsParser.CMD_WRITE
                    )
                    val len = txBytes[3].toInt() and 0xFF
                    assertEquals("Line ${index + 1} TX frame length mismatch", 4 + len + 3, txBytes.size)
                    assertEquals("Line ${index + 1} TX stop byte must be 0x77", JbdBmsParser.STOP_BYTE, txBytes.last())

                    val expectedCrc = JbdBmsParser.calculateChecksum(txBytes.copyOfRange(2, 4 + len))
                    assertEquals("Line ${index + 1} TX CRC_H mismatch", expectedCrc[0], txBytes[4 + len])
                    assertEquals("Line ${index + 1} TX CRC_L mismatch", expectedCrc[1], txBytes[5 + len])
                    txCount++
                }
                rxMarkerIdx != -1 -> {
                    val hexPayload = line.substring(rxMarkerIdx + 3).trim()
                    val rxBytes = hexToBytes(hexPayload)
                    assertTrue(
                        "Line ${index + 1} RX frame failed isCommandResponseOk: $line",
                        JbdBmsParser.isCommandResponseOk(rxBytes)
                    )

                    val beforeResponses = capturedResponses.size
                    val status = parser.assemble(rxBytes)
                    assertEquals(
                        "Line ${index + 1} RX frame must emit one CommandResponse",
                        beforeResponses + 1,
                        capturedResponses.size
                    )
                    assertTrue(
                        "Line ${index + 1} RX CommandResponse must be OK",
                        capturedResponses.last().isOk
                    )
                    if (rxBytes[1] == JbdBmsParser.CMD_BASIC_INFO || rxBytes[1] == JbdBmsParser.CMD_CELL_VOLTAGES) {
                        assertNotNull("Line ${index + 1} telemetry frame must return AntBmsStatus", status)
                    }
                    if (status != null) {
                        latestStatus = status
                    }
                    rxCount++
                }
            }
        }

        assertEquals(5, txCount)
        assertEquals(5, rxCount)
        assertEquals(5, capturedResponses.size)
        assertEquals("JBD-SP04S034-L", parser.lastDeviceName)
        assertNotNull(latestStatus)
        assertEquals(15.47, latestStatus?.voltage ?: 0.0, 0.001)
        assertEquals(99, latestStatus?.soc)
        assertEquals(4, latestStatus?.cellVoltages?.size)
    }
}

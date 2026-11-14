package com.figago.shared.bms

/**
 * Streaming parser for JK (Jikong) BMS RS485 / BLE V1 protocol frames (0x4E 0x57 header).
 *
 * Enforces strict frame validation before producing telemetry:
 * - Header: 0x4E 0x57 ("NW")
 * - Frame length: uint16 Big-Endian at offset 2..3 (21..512 bytes)
 * - End-of-record marker: 0x68 at offset (frameLen - 9)
 * - Checksum: 16-bit Big-Endian sum of bytes [0 until frameLen - 2] at offset [frameLen - 2..frameLen - 1]
 * - Telemetry payload TLV registers: 0x79 (cell voltages), 0x83 (pack voltage), 0x84 (current), 0x85 (SoC)
 */
class JkBmsParser {

    private val buffer = ByteArray(1024)
    private var bufferSize = 0

    companion object {
        const val HEADER_BYTE_1: Byte = 0x4E
        const val HEADER_BYTE_2: Byte = 0x57
        const val END_RECORD_MARKER: Byte = 0x68

        const val CMD_READ_ALL: Byte = 0x06
        const val FRAME_TYPE_BMS_RESPONSE: Byte = 0x02
        const val FRAME_TYPE_ACTIVE_REPORT: Byte = 0x03

        const val TAG_CELL_VOLTAGES: Byte = 0x79
        const val TAG_TEMP_POWER_TUBE: Byte = 0x80.toByte()
        const val TAG_TEMP_BATTERY_BOX: Byte = 0x81.toByte()
        const val TAG_TEMP_BATTERY: Byte = 0x82.toByte()
        const val TAG_TOTAL_VOLTAGE: Byte = 0x83.toByte()
        const val TAG_CURRENT: Byte = 0x84.toByte()
        const val TAG_SOC: Byte = 0x85.toByte()

        private const val MIN_FRAME_LENGTH = 21
        private const val MAX_FRAME_LENGTH = 512

        fun getStatusCommand(): ByteArray {
            return byteArrayOf(
                0x4E, 0x57, 0x00, 0x13, 0x00, 0x00, 0x00, 0x00,
                0x06, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x68, 0x00, 0x00, 0x01, 0x29
            )
        }

        fun calculateChecksum16(data: ByteArray, length: Int = data.size): Int {
            var sum = 0
            for (i in 0 until length) {
                sum += data[i].toInt() and 0xFF
            }
            return sum and 0xFFFF
        }
    }

    @Synchronized
    fun reset() {
        bufferSize = 0
    }

    @Synchronized
    fun assemble(data: ByteArray): AntBmsStatus? {
        if (data.isEmpty() || data.size > buffer.size) return null

        if (bufferSize + data.size > buffer.size) {
            bufferSize = 0
        }

        System.arraycopy(data, 0, buffer, bufferSize, data.size)
        bufferSize += data.size

        var latestStatus: AntBmsStatus? = null

        while (bufferSize >= MIN_FRAME_LENGTH) {
            var headerIdx = -1
            for (i in 0 until bufferSize - 1) {
                if (buffer[i] == HEADER_BYTE_1 && buffer[i + 1] == HEADER_BYTE_2) {
                    headerIdx = i
                    break
                }
            }

            if (headerIdx == -1) {
                if (bufferSize > 0 && buffer[bufferSize - 1] == HEADER_BYTE_1) {
                    buffer[0] = HEADER_BYTE_1
                    bufferSize = 1
                } else {
                    bufferSize = 0
                }
                break
            }

            if (headerIdx > 0) {
                System.arraycopy(buffer, headerIdx, buffer, 0, bufferSize - headerIdx)
                bufferSize -= headerIdx
            }

            if (bufferSize < 4) {
                break
            }

            val frameLen = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
            if (frameLen !in MIN_FRAME_LENGTH..MAX_FRAME_LENGTH) {
                advanceBuffer(1)
                continue
            }

            if (bufferSize < frameLen) {
                break
            }

            val endMarkerIndex = frameLen - 9
            if (endMarkerIndex < 11 || buffer[endMarkerIndex] != END_RECORD_MARKER) {
                advanceBuffer(1)
                continue
            }

            val expectedCrc = calculateChecksum16(buffer, frameLen - 2)
            val actualCrc = ((buffer[frameLen - 2].toInt() and 0xFF) shl 8) or
                (buffer[frameLen - 1].toInt() and 0xFF)

            if (expectedCrc != actualCrc) {
                advanceBuffer(1)
                continue
            }

            val command = buffer[8]
            val frameType = buffer[9]
            val isTelemetryFrame = command == CMD_READ_ALL ||
                frameType == FRAME_TYPE_BMS_RESPONSE ||
                frameType == FRAME_TYPE_ACTIVE_REPORT

            if (isTelemetryFrame) {
                val parsed = parseTelemetryPayload(11, endMarkerIndex)
                if (parsed != null) {
                    latestStatus = parsed
                }
            }

            advanceBuffer(frameLen)
        }

        return latestStatus
    }

    private fun parseTelemetryPayload(startIndex: Int, endIndex: Int): AntBmsStatus? {
        var idx = startIndex
        val cellVoltages = ArrayList<Double>()
        val temperatures = ArrayList<Int>()
        var voltage: Double? = null
        var current: Double? = null
        var soc: Int? = null

        while (idx < endIndex) {
            val tag = buffer[idx]
            when (tag) {
                TAG_CELL_VOLTAGES -> {
                    if (idx + 1 >= endIndex) return null
                    val byteCount = buffer[idx + 1].toInt() and 0xFF
                    if (byteCount <= 0 || byteCount % 3 != 0 || idx + 2 + byteCount > endIndex) {
                        return null
                    }
                    val cellCount = byteCount / 3
                    cellVoltages.clear()
                    for (c in 0 until cellCount) {
                        val cellOffset = idx + 2 + c * 3
                        val mv = ((buffer[cellOffset + 1].toInt() and 0xFF) shl 8) or
                            (buffer[cellOffset + 2].toInt() and 0xFF)
                        cellVoltages.add(mv * 0.001)
                    }
                    idx += 2 + byteCount
                }
                TAG_TEMP_POWER_TUBE,
                TAG_TEMP_BATTERY_BOX,
                TAG_TEMP_BATTERY -> {
                    if (idx + 2 >= endIndex) return null
                    val rawTemp = ((buffer[idx + 1].toInt() and 0xFF) shl 8) or
                        (buffer[idx + 2].toInt() and 0xFF)
                    val tempC = if (rawTemp <= 100) rawTemp else -(rawTemp - 100)
                    temperatures.add(tempC)
                    idx += 3
                }
                TAG_TOTAL_VOLTAGE -> {
                    if (idx + 2 >= endIndex) return null
                    val rawVolt = ((buffer[idx + 1].toInt() and 0xFF) shl 8) or
                        (buffer[idx + 2].toInt() and 0xFF)
                    voltage = rawVolt * 0.01
                    idx += 3
                }
                TAG_CURRENT -> {
                    if (idx + 2 >= endIndex) return null
                    val rawCur = (((buffer[idx + 1].toInt() and 0xFF) shl 8) or
                        (buffer[idx + 2].toInt() and 0xFF)).toShort().toInt()
                    current = rawCur * 0.01
                    idx += 3
                }
                TAG_SOC -> {
                    if (idx + 1 >= endIndex) return null
                    val rawSoc = buffer[idx + 1].toInt() and 0xFF
                    if (rawSoc > 100) return null
                    soc = rawSoc
                    idx += 2
                }
                else -> {
                    // Unknown or unsupported tag in stream: abort unless required tags were already decoded
                    break
                }
            }
        }

        val resolvedVoltage = voltage ?: return null
        val resolvedCurrent = current ?: return null
        val resolvedSoc = soc ?: return null

        val maxCell = cellVoltages.maxOrNull()
        val minCell = cellVoltages.minOrNull()

        return AntBmsStatus(
            soc = resolvedSoc,
            voltage = resolvedVoltage,
            current = resolvedCurrent,
            temperature = temperatures.firstOrNull(),
            cellVoltages = cellVoltages,
            temperatures = temperatures,
            isChargeMosOpen = false,
            isDischargeMosOpen = false,
            balanceStatus = null,
            capacityAh = null,
            maxCellVoltage = maxCell,
            minCellVoltage = minCell
        )
    }

    private fun advanceBuffer(bytesToDrop: Int) {
        val remaining = bufferSize - bytesToDrop
        if (remaining > 0) {
            System.arraycopy(buffer, bytesToDrop, buffer, 0, remaining)
        }
        bufferSize = maxOf(0, remaining)
    }
}

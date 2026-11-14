package com.figago.shared.bms

/**
 * Streaming parser for Daly Smart BMS UART/BLE 13-byte protocol frames (0xA5 start byte, 0x90 status command).
 *
 * Enforces strict frame length (13 bytes), start marker (0xA5), data length byte (0x08),
 * and 8-bit checksum verification (sum of bytes 0..11 & 0xFF == byte 12).
 */
class DalyBmsParser {

    private val buffer = ByteArray(1024)
    private var bufferSize = 0

    companion object {
        const val START_BYTE: Byte = 0xA5.toByte()
        const val CMD_SOC_VOLTAGE_CURRENT: Byte = 0x90.toByte()
        const val EXPECTED_DATA_LENGTH: Byte = 0x08
        const val FRAME_LENGTH: Int = 13

        fun getStatusCommand(): ByteArray {
            // Frame to request SOC, voltage, current (0x90)
            return byteArrayOf(
                START_BYTE, 0x40, CMD_SOC_VOLTAGE_CURRENT, EXPECTED_DATA_LENGTH,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7D
            )
        }

        fun calculateChecksum(framePrefix12: ByteArray, offset: Int = 0): Byte {
            var sum = 0
            for (i in 0 until 12) {
                sum += framePrefix12[offset + i].toInt() and 0xFF
            }
            return (sum and 0xFF).toByte()
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

        while (bufferSize >= FRAME_LENGTH) {
            var headerIdx = -1
            for (i in 0 until bufferSize) {
                if (buffer[i] == START_BYTE) {
                    headerIdx = i
                    break
                }
            }

            if (headerIdx == -1) {
                bufferSize = 0
                break
            }

            if (headerIdx > 0) {
                System.arraycopy(buffer, headerIdx, buffer, 0, bufferSize - headerIdx)
                bufferSize -= headerIdx
            }

            if (bufferSize < FRAME_LENGTH) {
                break
            }

            val dataLen = buffer[3]
            val expectedChecksum = calculateChecksum(buffer, 0)
            val actualChecksum = buffer[12]

            if (dataLen != EXPECTED_DATA_LENGTH || actualChecksum != expectedChecksum) {
                advanceBuffer(1)
                continue
            }

            if (buffer[2] == CMD_SOC_VOLTAGE_CURRENT) {
                val voltageRaw = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                val currentOffset = ((buffer[8].toInt() and 0xFF) shl 8) or (buffer[9].toInt() and 0xFF)
                val currentRaw = currentOffset - 30000
                val socRaw = ((buffer[10].toInt() and 0xFF) shl 8) or (buffer[11].toInt() and 0xFF)

                latestStatus = AntBmsStatus(
                    soc = (socRaw / 10).coerceIn(0, 100),
                    voltage = voltageRaw * 0.1,
                    current = currentRaw * 0.1,
                    temperature = null,
                    isChargeMosOpen = false,
                    isDischargeMosOpen = false,
                    balanceStatus = null,
                    capacityAh = null,
                    maxCellVoltage = null,
                    minCellVoltage = null,
                    cellVoltages = emptyList(),
                    temperatures = emptyList()
                )
            }

            advanceBuffer(FRAME_LENGTH)
        }

        return latestStatus
    }

    private fun advanceBuffer(bytesToDrop: Int) {
        val remaining = bufferSize - bytesToDrop
        if (remaining > 0) {
            System.arraycopy(buffer, bytesToDrop, buffer, 0, remaining)
        }
        bufferSize = maxOf(0, remaining)
    }
}

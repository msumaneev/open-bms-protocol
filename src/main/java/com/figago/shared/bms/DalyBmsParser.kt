package com.figago.shared.bms

/**
 * Basic parser for Daly BMS (stub for future implementation).
 * Uses standard Daly protocol frames (0x90, 0x91, etc).
 */
class DalyBmsParser {
    private val buffer = mutableListOf<Byte>()

    companion object {
        fun getStatusCommand(): ByteArray {
            // Frame to request SOC, voltage, current (0x90)
            return byteArrayOf(
                0xA5.toByte(), 0x40, 0x90.toByte(), 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7D
            )
        }
    }

    fun assemble(data: ByteArray): AntBmsStatus? {
        if (data.isNotEmpty() && data[0] == 0xA5.toByte()) {
            buffer.clear()
        }
        buffer.addAll(data.toList())

        // Check if we have at least one full frame (13 bytes)
        if (buffer.size >= 13 && buffer[0] == 0xA5.toByte()) {
            // Very basic stub parsing for 0x90 frame
            if (buffer[2] == 0x90.toByte()) {
                val voltage = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                val currentOffset = ((buffer[8].toInt() and 0xFF) shl 8) or (buffer[9].toInt() and 0xFF)
                val current = currentOffset - 30000
                val soc = ((buffer[10].toInt() and 0xFF) shl 8) or (buffer[11].toInt() and 0xFF)

                buffer.clear()

                return AntBmsStatus(
                    soc = (soc / 10).toInt(),
                    voltage = voltage * 0.1,
                    current = current * 0.1,
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
        }
        return null
    }
}

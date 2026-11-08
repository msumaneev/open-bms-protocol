package com.figago.shared.bms

class AntBmsParser {
    private val buffer = ByteArray(1024)
    private var bufferSize = 0

    companion object {
        fun getStatusCommand(): ByteArray {
            return byteArrayOf(
                0x7E, 0xA1.toByte(), 0x01, 0x00, 0x00, 0xBE.toByte(), 0x18, 0x55, 0xAA.toByte(), 0x55
            )
        }
    }

    @Synchronized
    fun assemble(data: ByteArray): AntBmsStatus? {
        if (data.isEmpty() || data.size > buffer.size) return null

        // Buffer overflow protection: reset if capacity exceeded
        if (bufferSize + data.size > buffer.size) {
            bufferSize = 0
        }

        System.arraycopy(data, 0, buffer, bufferSize, data.size)
        bufferSize += data.size

        var latestStatus: AntBmsStatus? = null

        // Loop to extract any and all complete frames in the buffer (handles glued frames)
        while (bufferSize >= 10) {
            // 1. Scan for header 0x7E 0xA1
            var headerIdx = -1
            for (i in 0 until bufferSize - 1) {
                if (buffer[i] == 0x7E.toByte() && buffer[i + 1] == 0xA1.toByte()) {
                    headerIdx = i
                    break
                }
            }

            if (headerIdx == -1) {
                // No header found. Check if last byte is 0x7E (might be start of split header)
                if (bufferSize > 0 && buffer[bufferSize - 1] == 0x7E.toByte()) {
                    buffer[0] = 0x7E.toByte()
                    bufferSize = 1
                } else {
                    bufferSize = 0
                }
                break
            }

            // Drop leading garbage if header is not at index 0
            if (headerIdx > 0) {
                System.arraycopy(buffer, headerIdx, buffer, 0, bufferSize - headerIdx)
                bufferSize -= headerIdx
            }

            if (bufferSize < 10) {
                break // Need more data for header validation
            }

            val function = buffer[2].toInt() and 0xFF
            val dataLen = buffer[5].toInt() and 0xFF
            val frameLen = 6 + dataLen + 4

            if (bufferSize < frameLen) {
                break // Wait for complete frame
            }

            // Validate trailer 0xAA 0x55 at the end of the frame
            val trailerByte1 = buffer[frameLen - 2]
            val trailerByte2 = buffer[frameLen - 1]
            val isTrailerValid = trailerByte1 == 0xAA.toByte() && trailerByte2 == 0x55.toByte()

            if (isTrailerValid && function == 0x11) {
                val status = parseStatusData(frameLen)
                if (status != null) {
                    latestStatus = status
                }
                // Shift buffer to drop processed frame, preserving any following glued frames
                val remaining = bufferSize - frameLen
                if (remaining > 0) {
                    System.arraycopy(buffer, frameLen, buffer, 0, remaining)
                }
                bufferSize = remaining
            } else {
                // Corrupted frame or mismatched trailer: advance by 1 byte to search for next 0x7E 0xA1
                val remaining = bufferSize - 1
                if (remaining > 0) {
                    System.arraycopy(buffer, 1, buffer, 0, remaining)
                }
                bufferSize = remaining
            }
        }

        return latestStatus
    }

    private fun parseStatusData(frameLen: Int): AntBmsStatus? {
        if (frameLen < 10) return null

        val temperatureSensors = buffer[8].toInt() and 0xFF
        val cells = buffer[9].toInt() and 0xFF

        if (cells !in 1..32) return null
        val safeTempSensors = if (temperatureSensors in 1..8) temperatureSensors else 0

        val cellsStart = 34
        val cSize = cells * 2
        val tSize = safeTempSensors * 2
        val tempsStart = cellsStart + cSize
        val base = tempsStart + tSize

        if (frameLen < base + 52) return null // Ensure enough bytes for fixed telemetry block

        val cellVoltages = mutableListOf<Double>()
        var maxCell: Double? = null
        var minCell: Double? = null

        for (i in 0 until cells) {
            val v = get16Bit(cellsStart + i * 2) * 0.001
            cellVoltages.add(v)
            if (maxCell == null || v > maxCell) maxCell = v
            if (minCell == null || v < minCell) minCell = v
        }

        val temperatures = mutableListOf<Int>()
        var mainBatteryTemp: Int? = null
        for (i in 0 until safeTempSensors) {
            val tRaw = get16Bit(tempsStart + i * 2)
            val t = tRaw.toShort().toInt()
            temperatures.add(t)
            if (i == 0) mainBatteryTemp = t
        }

        val mosTemp = get16Bit(base + 0).toShort().toInt()
        val balancerTemp = get16Bit(base + 2).toShort().toInt()
        temperatures.add(mosTemp)
        temperatures.add(balancerTemp)
        if (mainBatteryTemp == null) mainBatteryTemp = mosTemp

        val voltageRaw = get16Bit(base + 4)
        val currentRaw = get16Bit(base + 6).toShort().toInt()
        val socRaw = get16Bit(base + 8)

        val chargeMos = buffer[base + 12].toInt() == 1
        val dischargeMos = buffer[base + 13].toInt() == 1
        val balanceStatus = buffer[base + 14].toInt() and 0xFF

        var capacityAh: Double? = null
        var totalCapacityAh: Double? = null
        var remainingCapacityAh: Double? = null
        var totalCycleAh: Double? = null
        if (frameLen >= base + 24) {
            val totalRaw = get32Bit(base + 16)
            totalCapacityAh = totalRaw * 0.000001
            val remRaw = get32Bit(base + 20)
            remainingCapacityAh = remRaw * 0.000001
            capacityAh = remainingCapacityAh
        }
        if (frameLen >= base + 28) {
            val cycleRaw = get32Bit(base + 24)
            if (cycleRaw in 0L..0x7FFFFFFFL) {
                totalCycleAh = cycleRaw * 0.001
            }
        }

        val maxCellFromFrame = get16Bit(base + 40) * 0.001
        val minCellFromFrame = get16Bit(base + 44) * 0.001
        if (maxCellFromFrame > 0.0) maxCell = maxCellFromFrame
        if (minCellFromFrame > 0.0) minCell = minCellFromFrame

        return AntBmsStatus(
            soc = socRaw,
            voltage = voltageRaw * 0.01,
            current = currentRaw * 0.1,
            temperature = mainBatteryTemp,
            cellVoltages = cellVoltages,
            temperatures = temperatures,
            isChargeMosOpen = chargeMos,
            isDischargeMosOpen = dischargeMos,
            balanceStatus = balanceStatus,
            maxCellVoltage = maxCell,
            minCellVoltage = minCell,
            capacityAh = capacityAh,
            totalCapacityAh = totalCapacityAh,
            remainingCapacityAh = remainingCapacityAh,
            totalCycleAh = totalCycleAh
        )
    }

    private fun get32Bit(index: Int): Long {
        if (index + 3 >= bufferSize) return 0
        val b0 = buffer[index].toLong() and 0xFF
        val b1 = buffer[index + 1].toLong() and 0xFF
        val b2 = buffer[index + 2].toLong() and 0xFF
        val b3 = buffer[index + 3].toLong() and 0xFF
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun get16Bit(index: Int): Int {
        if (index + 1 >= bufferSize) return 0
        val b0 = buffer[index].toInt() and 0xFF
        val b1 = buffer[index + 1].toInt() and 0xFF
        return (b1 shl 8) or b0
    }
}


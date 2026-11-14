package com.figago.shared.bms

/**
 * High-performance, fault-tolerant parser for JBD (Jiabaida) / Xiaoxiang BMS protocol.
 *
 * Implements full protocol frame decoding:
 * - Request framing and two's complement checksum calculation
 * - Dynamic response parsing for Command 0x03 (Basic Information: V, I, SOC, capacity, FETs, temperatures, protection)
 * - Dynamic response parsing for Command 0x04 (Cell Voltages: individual cell mV, min/max cell)
 * - Dynamic response parsing for Command 0x05 (Hardware Device Name / Model)
 * - Unified streaming ACK/NACK frame decoding for write/command registers (0x00, 0x01, 0x06, 0x07, 0x09)
 * - State accumulation: merges 0x03 and 0x04 frames into a unified [AntBmsStatus] / [BmsData]
 * - Pre-allocated ring/shift buffer resilient to MTU 20-byte BLE fragmentation, noise/garbage bytes, and glued frames.
 */
class JbdBmsParser {

    /**
     * Represents a validated JBD response frame (either telemetry or command ACK/NACK)
     * extracted by the streaming [assemble] parser.
     */
    data class CommandResponse(
        val register: Byte,
        val status: Byte,
        val payload: ByteArray,
        val rawFrame: ByteArray
    ) {
        val isOk: Boolean
            get() = status == STATUS_OK

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommandResponse) return false
            return register == other.register &&
                status == other.status &&
                payload.contentEquals(other.payload) &&
                rawFrame.contentEquals(other.rawFrame)
        }

        override fun hashCode(): Int {
            var result = register.toInt()
            result = 31 * result + status.toInt()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + rawFrame.contentHashCode()
            return result
        }
    }

    private val buffer = ByteArray(1024)
    private var bufferSize = 0

    // Cached state for assembling multi-packet telemetry (0x03 basic info + 0x04 cell voltages)
    private var cachedBasicStatus: AntBmsStatus? = null
    private var cachedCellVoltages: List<Double> = emptyList()
    private var cachedMaxCellVoltage: Double? = null
    private var cachedMinCellVoltage: Double? = null

    // Additional JBD-specific telemetry and diagnostics
    var lastProtectionState: Int? = null
        private set
    var lastCycleCount: Int? = null
        private set
    var lastSoftwareVersion: Int? = null
        private set
    var lastProductionDate: Int? = null
        private set
    var lastBalancedState: Long? = null
        private set
    var lastDeviceName: String? = null
        private set
    var isDeviceNameResolved: Boolean = false
        private set
    var lastTemperaturesCelsius: List<Double> = emptyList()
        private set

    /**
     * Most recent validated frame (telemetry or command ACK/NACK) decoded by [assemble].
     */
    var lastCommandResponse: CommandResponse? = null
        private set

    /**
     * Optional listener invoked synchronously whenever [assemble] extracts a valid, CRC-verified frame.
     */
    var onCommandResponse: ((CommandResponse) -> Unit)? = null

    companion object {
        const val START_BYTE: Byte = 0xDD.toByte()
        const val STOP_BYTE: Byte = 0x77
        const val OP_READ: Byte = 0xA5.toByte()
        const val CMD_WRITE: Byte = 0x5A

        const val CMD_BASIC_INFO: Byte = 0x03
        const val CMD_CELL_VOLTAGES: Byte = 0x04
        const val CMD_DEVICE_NAME: Byte = 0x05

        const val REG_ENTER_FACTORY: Byte = 0x00
        const val REG_EXIT_FACTORY: Byte = 0x01
        const val REG_USE_PASSWORD: Byte = 0x06
        const val REG_SET_PASSWORD: Byte = 0x07
        const val REG_CLEAR_PASSWORD: Byte = 0x09

        const val STATUS_OK: Byte = 0x00
        const val STATUS_ERROR: Byte = 0x80.toByte()

        // JBD Protection State Bitmask Flags
        const val PROT_CELL_OVERVOLTAGE: Int = 1 shl 0
        const val PROT_CELL_UNDERVOLTAGE: Int = 1 shl 1
        const val PROT_PACK_OVERVOLTAGE: Int = 1 shl 2
        const val PROT_PACK_UNDERVOLTAGE: Int = 1 shl 3
        const val PROT_CHARGE_OVERTEMP: Int = 1 shl 4
        const val PROT_CHARGE_UNDERTEMP: Int = 1 shl 5
        const val PROT_DISCHARGE_OVERTEMP: Int = 1 shl 6
        const val PROT_DISCHARGE_UNDERTEMP: Int = 1 shl 7
        const val PROT_CHARGE_OVERCURRENT: Int = 1 shl 8
        const val PROT_DISCHARGE_OVERCURRENT: Int = 1 shl 9
        const val PROT_SHORT_CIRCUIT: Int = 1 shl 10
        const val PROT_IC_ERROR: Int = 1 shl 11
        const val PROT_MOS_LOCK: Int = 1 shl 12

        private fun isKnownRegister(register: Byte): Boolean {
            return register == CMD_BASIC_INFO ||
                register == CMD_CELL_VOLTAGES ||
                register == CMD_DEVICE_NAME ||
                register == REG_ENTER_FACTORY ||
                register == REG_EXIT_FACTORY ||
                register == REG_USE_PASSWORD ||
                register == REG_SET_PASSWORD ||
                register == REG_CLEAR_PASSWORD
        }

        private fun isValidPayloadLength(register: Byte, status: Byte, dataLen: Int): Boolean {
            if (status != STATUS_OK) {
                return dataLen in 0..16
            }
            return when (register) {
                CMD_BASIC_INFO -> dataLen in 23..64
                CMD_CELL_VOLTAGES -> dataLen in 2..64 && (dataLen % 2 == 0)
                CMD_DEVICE_NAME -> dataLen in 0..64
                REG_ENTER_FACTORY,
                REG_EXIT_FACTORY,
                REG_USE_PASSWORD,
                REG_SET_PASSWORD,
                REG_CLEAR_PASSWORD -> dataLen in 0..64
                else -> false
            }
        }

        /**
         * Calculates the standard JBD 16-bit Big-Endian two's complement checksum:
         * Checksum = (0x10000 - (sum(payload) & 0xFFFF)) & 0xFFFF
         *
         * @param payload Bytes to be summed (for request: command + length; for response: status + length + data)
         * @return 2-byte Big-Endian checksum array [Checksum_H, Checksum_L]
         */
        fun calculateChecksum(payload: ByteArray): ByteArray {
            var sum = 0
            for (b in payload) {
                sum += b.toInt() and 0xFF
            }
            val checksum = (0x10000 - (sum and 0xFFFF)) and 0xFFFF
            return byteArrayOf(
                ((checksum shr 8) and 0xFF).toByte(),
                (checksum and 0xFF).toByte()
            )
        }

        /**
         * Creates a standard JBD read request frame:
         * [0xDD, 0xA5, command, 0x00, Checksum_H, Checksum_L, 0x77]
         */
        fun createRequest(command: Byte): ByteArray {
            val payload = byteArrayOf(command, 0x00)
            val checksum = calculateChecksum(payload)
            return byteArrayOf(
                START_BYTE,
                OP_READ,
                command,
                0x00,
                checksum[0],
                checksum[1],
                STOP_BYTE
            )
        }

        /**
         * Creates Request Frame for Command 0x03 (Basic Info):
         * DD A5 03 00 FF FD 77
         */
        fun createBasicInfoRequest(): ByteArray = createRequest(CMD_BASIC_INFO)

        /**
         * Creates Request Frame for Command 0x04 (Cell Voltages):
         * DD A5 04 00 FF FC 77
         */
        fun createCellVoltagesRequest(): ByteArray = createRequest(CMD_CELL_VOLTAGES)

        /**
         * Creates Request Frame for Command 0x05 (Device Name / Hardware Model):
         * DD A5 05 00 FF FB 77
         */
        fun createDeviceNameRequest(): ByteArray = createRequest(CMD_DEVICE_NAME)

        /**
         * Backward compatibility alias for [createBasicInfoRequest].
         */
        fun getStatusCommand(): ByteArray = createBasicInfoRequest()

        /**
         * Alias for [createCellVoltagesRequest].
         */
        fun getCellVoltagesCommand(): ByteArray = createCellVoltagesRequest()

        /**
         * Creates a JBD write request frame (opcode 0x5A):
         * [0xDD] [0x5A] [Register] [Length N] [Data: N bytes] [CRC_H] [CRC_L] [0x77]
         * Checksum = (0x10000 - ((Register_u8 + Length_u8 + sum(Data_u8)) & 0xFFFF)) & 0xFFFF
         */
        fun createWriteRequest(register: Byte, payload: ByteArray): ByteArray {
            require(payload.size <= 0xFF) { "Payload size must not exceed 255 bytes" }
            val lengthByte = payload.size.toByte()
            val crcInput = ByteArray(2 + payload.size)
            crcInput[0] = register
            crcInput[1] = lengthByte
            System.arraycopy(payload, 0, crcInput, 2, payload.size)
            val checksum = calculateChecksum(crcInput)

            val frame = ByteArray(4 + payload.size + 3)
            frame[0] = START_BYTE
            frame[1] = CMD_WRITE
            frame[2] = register
            frame[3] = lengthByte
            System.arraycopy(payload, 0, frame, 4, payload.size)
            frame[4 + payload.size] = checksum[0]
            frame[5 + payload.size] = checksum[1]
            frame[6 + payload.size] = STOP_BYTE
            return frame
        }

        /**
         * Creates Enter Factory Mode request (Register 0x00, payload 0x56 0x78):
         * DD 5A 00 02 56 78 FF 30 77
         *
         * WARNING: Factory/EEPROM mode commands are hardware-revision sensitive and must not be
         * executed in production without physical hardware verification for the target BMS board.
         */
        fun createEnterFactoryModeRequest(): ByteArray =
            createWriteRequest(REG_ENTER_FACTORY, byteArrayOf(0x56, 0x78))

        /**
         * Creates Exit Factory Mode request with EEPROM save (Register 0x01, payload 0x28 0x28):
         * DD 5A 01 02 28 28 FF AD 77
         *
         * WARNING: JBD firmware revisions differ on Register 0x01 semantics (some revisions use
         * 0x28 0x28 to save EEPROM and 0x00 0x00 to exit without saving, while others invert or
         * restrict these payloads). Do not enable EEPROM writes in production without verifying
         * physical hardware traces for the target BMS revision.
         */
        fun createExitFactoryModeRequest(): ByteArray =
            createWriteRequest(REG_EXIT_FACTORY, byteArrayOf(0x28, 0x28))

        /**
         * Creates Password Authentication request (Register 0x06, 6 ASCII bytes).
         *
         * WARNING: Password register layout (0x06/0x07/0x09) varies across JBD firmware revisions.
         * Keep disabled in production until verified on physical target hardware.
         */
        fun createAuthPasswordRequest(password: String): ByteArray {
            validatePassword(password)
            return createWriteRequest(REG_USE_PASSWORD, password.toByteArray(Charsets.US_ASCII))
        }

        /**
         * Creates Set Password request (Register 0x07, 6 ASCII bytes).
         *
         * WARNING: Password register layout (0x06/0x07/0x09) varies across JBD firmware revisions.
         * Keep disabled in production until verified on physical target hardware.
         */
        fun createSetPasswordRequest(password: String): ByteArray {
            validatePassword(password)
            return createWriteRequest(REG_SET_PASSWORD, password.toByteArray(Charsets.US_ASCII))
        }

        /**
         * Creates Clear Password request (Register 0x09, payload 0x00 0x00):
         * DD 5A 09 02 00 00 FF F5 77
         *
         * WARNING: Password register layout (0x06/0x07/0x09) varies across JBD firmware revisions.
         * Keep disabled in production until verified on physical target hardware.
         */
        fun createClearPasswordRequest(): ByteArray =
            createWriteRequest(REG_CLEAR_PASSWORD, byteArrayOf(0x00, 0x00))

        private fun validatePassword(password: String) {
            require(password.length == 6 && password.all { it.code in 0x20..0x7E }) {
                "JBD BMS password must be exactly 6 printable ASCII characters (0x20..0x7E)"
            }
        }

        /**
         * Validates a single standalone JBD command/telemetry response frame:
         * [0xDD] [Register] [Status] [Length N] [Data: N bytes] [CRC_H] [CRC_L] [0x77]
         *
         * Strictly requires:
         * - Exact frame byte length (no trailing bytes)
         * - Status == STATUS_OK (0x00); any non-zero status returns false
         * - Valid register-specific payload length (including even byte count for 0x04 cell voltages)
         * - Matching 16-bit Big-Endian two's complement checksum and 0x77 stop byte
         *
         * @param data Raw response frame bytes
         * @param expectedRegister Optional register to match against byte[1]
         * @return true if framing, CRC, payload length, register (if specified), and status (STATUS_OK = 0x00) are all valid
         */
        fun isCommandResponseOk(data: ByteArray, expectedRegister: Byte? = null): Boolean {
            if (data.size < 7) return false
            if (data[0] != START_BYTE) return false

            val register = data[1]
            if (expectedRegister != null && register != expectedRegister) return false

            val status = data[2]
            if (status != STATUS_OK) return false

            val dataLen = data[3].toInt() and 0xFF
            val expectedFrameLen = 4 + dataLen + 3
            if (data.size != expectedFrameLen) return false
            if (data[expectedFrameLen - 1] != STOP_BYTE) return false

            if (register == CMD_CELL_VOLTAGES && (dataLen < 2 || dataLen > 64 || dataLen % 2 != 0)) {
                return false
            }
            if (register == CMD_BASIC_INFO && (dataLen < 23 || dataLen > 64)) {
                return false
            }
            if (dataLen > 64) {
                return false
            }

            val crcInput = data.copyOfRange(2, 4 + dataLen)
            val expectedCrc = calculateChecksum(crcInput)
            val crcWithCmd = calculateChecksum(data.copyOfRange(1, 4 + dataLen))
            val matchesStandard = data[4 + dataLen] == expectedCrc[0] && data[5 + dataLen] == expectedCrc[1]
            val matchesWithCmd = register == CMD_DEVICE_NAME &&
                data[4 + dataLen] == crcWithCmd[0] &&
                data[5 + dataLen] == crcWithCmd[1]
            if (!matchesStandard && !matchesWithCmd) {
                return false
            }

            return true
        }
    }

    /**
     * Resets internal streaming buffer and accumulated telemetry state.
     * Call when BLE connection is terminated or restarted.
     */
    @Synchronized
    fun reset() {
        bufferSize = 0
        cachedBasicStatus = null
        cachedCellVoltages = emptyList()
        cachedMaxCellVoltage = null
        cachedMinCellVoltage = null
        lastProtectionState = null
        lastCycleCount = null
        lastSoftwareVersion = null
        lastProductionDate = null
        lastBalancedState = null
        lastDeviceName = null
        isDeviceNameResolved = false
        lastTemperaturesCelsius = emptyList()
        lastCommandResponse = null
    }

    /**
     * Parses incoming BLE chunk. Alias for [assemble].
     */
    fun parse(data: ByteArray): AntBmsStatus? = assemble(data)

    /**
     * Appends incoming BLE chunk to internal buffer and parses all complete, valid JBD frames
     * (both telemetry responses 0x03/0x04/0x05 and command ACK/NACK responses 0x00/0x01/0x06/0x07/0x09).
     * Handles MTU fragmentation (e.g. 20-byte chunks), glued frames, and corrupt data.
     *
     * @param data Incoming raw bytes from BLE characteristic notification
     * @return Updated [AntBmsStatus] if at least one valid telemetry frame was processed, or null if awaiting more data.
     */
    @Synchronized
    fun assemble(data: ByteArray): AntBmsStatus? {
        if (data.isEmpty() || data.size > buffer.size) return null

        // Buffer overflow protection: reset if incoming data exceeds buffer capacity
        if (bufferSize + data.size > buffer.size) {
            bufferSize = 0
        }

        System.arraycopy(data, 0, buffer, bufferSize, data.size)
        bufferSize += data.size

        var latestStatus: AntBmsStatus? = null

        // JBD response frame structure:
        // [0xDD] [Cmd/Reg] [Status] [Len N] [Data: N bytes] [CRC_H] [CRC_L] [0x77]
        // Minimum frame size with N=0 is 7 bytes.
        while (bufferSize >= 7) {
            // 1. Scan for start marker 0xDD
            var headerIdx = -1
            for (i in 0 until bufferSize) {
                if (buffer[i] == START_BYTE) {
                    headerIdx = i
                    break
                }
            }

            if (headerIdx == -1) {
                // No header in entire buffer: discard all noise
                bufferSize = 0
                break
            }

            // Drop leading noise if 0xDD is not at index 0
            if (headerIdx > 0) {
                System.arraycopy(buffer, headerIdx, buffer, 0, bufferSize - headerIdx)
                bufferSize -= headerIdx
            }

            // Need at least 4 bytes to determine frame length
            if (bufferSize < 4) {
                break
            }

            val command = buffer[1]
            val status = buffer[2]
            val dataLen = buffer[3].toInt() and 0xFF

            if (!isKnownRegister(command)) {
                advanceBuffer(1)
                continue
            }

            if (!isValidPayloadLength(command, status, dataLen)) {
                advanceBuffer(1)
                continue
            }

            val frameLen = 4 + dataLen + 3 // Header(4) + Data(N) + CRC(2) + Stop(1)

            if (bufferSize < frameLen) {
                // Incomplete frame: wait for more BLE chunks
                break
            }

            // Validate stop byte (0x77)
            val stopByte = buffer[frameLen - 1]
            if (stopByte != STOP_BYTE) {
                advanceBuffer(1)
                continue
            }

            // Validate 16-bit Big-Endian Checksum: 0x10000 - sum(Status + Length + Data)
            var sum = 0
            for (i in 2 until (4 + dataLen)) {
                sum += buffer[i].toInt() and 0xFF
            }
            val expectedCrc = (0x10000 - (sum and 0xFFFF)) and 0xFFFF
            val expectedCrcWithCmd = (0x10000 - ((sum + (command.toInt() and 0xFF)) and 0xFFFF)) and 0xFFFF
            val actualCrc = ((buffer[4 + dataLen].toInt() and 0xFF) shl 8) or
                            (buffer[5 + dataLen].toInt() and 0xFF)

            val isCrcValid = actualCrc == expectedCrc ||
                (command == CMD_DEVICE_NAME && actualCrc == expectedCrcWithCmd)
            if (!isCrcValid) {
                // Checksum failed: corrupted packet or false start byte
                advanceBuffer(1)
                continue
            }

            // Record validated command/telemetry frame and notify listener
            val response = CommandResponse(
                register = command,
                status = status,
                payload = buffer.copyOfRange(4, 4 + dataLen),
                rawFrame = buffer.copyOfRange(0, frameLen)
            )
            lastCommandResponse = response
            onCommandResponse?.invoke(response)

            // Process telemetry state only when status is strictly STATUS_OK (0x00)
            if (status == STATUS_OK) {
                val statusResult = processValidFrame(command, 4, dataLen)
                if (statusResult != null) {
                    latestStatus = statusResult
                }
            } else if (command == CMD_DEVICE_NAME) {
                isDeviceNameResolved = true
            }

            // Shift buffer to drop processed frame, allowing glued frames in the same buffer to be parsed
            advanceBuffer(frameLen)
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

    private fun processValidFrame(command: Byte, dataOffset: Int, dataLen: Int): AntBmsStatus? {
        return when (command) {
            CMD_BASIC_INFO -> parseBasicInfo(dataOffset, dataLen)
            CMD_CELL_VOLTAGES -> parseCellVoltages(dataOffset, dataLen)
            CMD_DEVICE_NAME -> {
                lastDeviceName = if (dataLen > 0) {
                    String(buffer, dataOffset, dataLen, Charsets.US_ASCII).trim()
                } else {
                    ""
                }
                isDeviceNameResolved = true
                cachedBasicStatus
            }
            else -> null
        }
    }

    private fun parseBasicInfo(offset: Int, dataLen: Int): AntBmsStatus? {
        // Basic Info payload requires at least 23 bytes (without external NTCs)
        if (dataLen < 23) return null

        // 1. Total Voltage: uint16 Big-Endian, 10mV resolution (0.01V)
        val voltageRaw = ((buffer[offset + 0].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val voltage = voltageRaw * 0.01

        // 2. Current: signed int16 Big-Endian, 10mA resolution (0.01A)
        // Positive: charging, Negative: discharging
        val currentRaw = (((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)).toShort().toInt()
        val current = currentRaw * 0.01

        // 3. Residual Capacity: uint16 Big-Endian, 10mAh resolution (0.01Ah)
        val remCapRaw = ((buffer[offset + 4].toInt() and 0xFF) shl 8) or (buffer[offset + 5].toInt() and 0xFF)
        val remainingCapacityAh = remCapRaw * 0.01

        // 4. Nominal Capacity: uint16 Big-Endian, 10mAh resolution (0.01Ah)
        val nomCapRaw = ((buffer[offset + 6].toInt() and 0xFF) shl 8) or (buffer[offset + 7].toInt() and 0xFF)
        val totalCapacityAh = nomCapRaw * 0.01

        // 5. Cycle Life: uint16 Big-Endian, full cycle count
        val cycleCount = ((buffer[offset + 8].toInt() and 0xFF) shl 8) or (buffer[offset + 9].toInt() and 0xFF)
        lastCycleCount = cycleCount
        val totalCycleAh = if (totalCapacityAh > 0.0) cycleCount * totalCapacityAh else cycleCount.toDouble()

        // 6. Production Date: uint16 Big-Endian (Day: bit 0..4, Month: bit 5..8, Year: 2000 + bit 9..15)
        val prodDateRaw = ((buffer[offset + 10].toInt() and 0xFF) shl 8) or (buffer[offset + 11].toInt() and 0xFF)
        lastProductionDate = prodDateRaw

        // 7. Balanced State: Low (cells 1..16), High (cells 17..32)
        val balLow = ((buffer[offset + 12].toInt() and 0xFF) shl 8) or (buffer[offset + 13].toInt() and 0xFF)
        val balHigh = ((buffer[offset + 14].toInt() and 0xFF) shl 8) or (buffer[offset + 15].toInt() and 0xFF)
        lastBalancedState = (balHigh.toLong() shl 16) or balLow.toLong()
        val balanceStatus = balLow

        // 8. Protection State: uint16 bitmask
        val protRaw = ((buffer[offset + 16].toInt() and 0xFF) shl 8) or (buffer[offset + 17].toInt() and 0xFF)
        lastProtectionState = protRaw

        // 9. Software Version: uint8
        val swVersion = buffer[offset + 18].toInt() and 0xFF
        lastSoftwareVersion = swVersion

        // 10. RSOC: uint8 (0..100%)
        val soc = buffer[offset + 19].toInt() and 0xFF

        // 11. FET Status: uint8 (bit 0: Charge MOS, bit 1: Discharge MOS)
        val fetStatus = buffer[offset + 20].toInt() and 0xFF
        val isChargeMosOpen = (fetStatus and 0x01) != 0
        val isDischargeMosOpen = (fetStatus and 0x02) != 0

        // 12. Serial Cells Count: uint8
        val serialCellsCount = buffer[offset + 21].toInt() and 0xFF

        // 13. NTC Quantity: uint8
        val ntcCount = buffer[offset + 22].toInt() and 0xFF

        // 14. NTC Temperatures: uint16 Big-Endian in 0.1 Kelvin
        // Formula: T(degC) = (raw - 2731) / 10.0
        val availableNtc = minOf(ntcCount, (dataLen - 23) / 2)
        val tempsIntList = ArrayList<Int>(availableNtc)
        val tempsDoubleList = ArrayList<Double>(availableNtc)
        for (i in 0 until availableNtc) {
            val kelvinRaw = ((buffer[offset + 23 + i * 2].toInt() and 0xFF) shl 8) or
                            (buffer[offset + 24 + i * 2].toInt() and 0xFF)
            val celsiusExact = (kelvinRaw - 2731) / 10.0
            val celsiusRounded = Math.round((kelvinRaw - 2731) / 10.0f)
            tempsIntList.add(celsiusRounded)
            tempsDoubleList.add(celsiusExact)
        }
        lastTemperaturesCelsius = tempsDoubleList
        val mainTemp = tempsIntList.firstOrNull()

        val assembled = AntBmsStatus(
            soc = soc,
            voltage = voltage,
            current = current,
            temperature = mainTemp,
            cellVoltages = cachedCellVoltages,
            temperatures = tempsIntList,
            isChargeMosOpen = isChargeMosOpen,
            isDischargeMosOpen = isDischargeMosOpen,
            balanceStatus = balanceStatus,
            maxCellVoltage = cachedMaxCellVoltage,
            minCellVoltage = cachedMinCellVoltage,
            capacityAh = remainingCapacityAh,
            totalCapacityAh = totalCapacityAh,
            remainingCapacityAh = remainingCapacityAh,
            totalCycleAh = totalCycleAh
        )
        cachedBasicStatus = assembled
        return assembled
    }

    private fun parseCellVoltages(offset: Int, dataLen: Int): AntBmsStatus? {
        val cellCount = dataLen / 2
        if (cellCount <= 0) return cachedBasicStatus

        val cellVoltages = ArrayList<Double>(cellCount)
        var maxCell: Double? = null
        var minCell: Double? = null

        for (i in 0 until cellCount) {
            val mv = ((buffer[offset + i * 2].toInt() and 0xFF) shl 8) or
                     (buffer[offset + i * 2 + 1].toInt() and 0xFF)
            val v = mv * 0.001
            cellVoltages.add(v)
            if (maxCell == null || v > maxCell) maxCell = v
            if (minCell == null || v < minCell) minCell = v
        }

        cachedCellVoltages = cellVoltages
        cachedMaxCellVoltage = maxCell
        cachedMinCellVoltage = minCell

        val currentBasic = cachedBasicStatus
        val mergedStatus = if (currentBasic != null) {
            currentBasic.copy(
                cellVoltages = cellVoltages,
                maxCellVoltage = maxCell,
                minCellVoltage = minCell
            )
        } else {
            // Standalone cell voltages before any basic info received:
            // Estimate pack voltage as sum of individual cell voltages
            AntBmsStatus(
                soc = 0,
                voltage = cellVoltages.sum(),
                current = 0.0,
                temperature = null,
                cellVoltages = cellVoltages,
                temperatures = emptyList(),
                isChargeMosOpen = false,
                isDischargeMosOpen = false,
                balanceStatus = null,
                maxCellVoltage = maxCell,
                minCellVoltage = minCell,
                capacityAh = null,
                totalCapacityAh = null,
                remainingCapacityAh = null,
                totalCycleAh = null
            )
        }

        cachedBasicStatus = mergedStatus
        return mergedStatus
    }
}

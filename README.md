# open-bms-protocol

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-17-orange.svg)](https://openjdk.org)

Zero-dependency Kotlin library for Bluetooth Low Energy (BLE) communication with Smart Battery Management Systems (BMS).
Includes streaming packet decoders, command encoders, chunked MTU simulation, and a crowdsourced physical hardware packet trace corpus.

Originally extracted and maintained from production use in [**FigaroGo**](https://play.google.com/store/apps/details?id=com.figago).

---

## Supported Protocols & Status

See full details in [**`HARDWARE_MATRIX.md`**](HARDWARE_MATRIX.md).

| Protocol | Registers & Capabilities | Hardware Verification |
|---|---|---|
| **JBD (Jiabaida) / Xiaoxiang** | Basic info (`0x03`), Cell voltages (`0x04`), Device model (`0x05`), EEPROM Mode (`0x5A`), Password lock/unlock (`0x00`/`0x06`/`0x07`/`0x09`/`0x01`) | 🟡 Simulated & Unit Tested |
| **ANT BMS** | Live streaming status (`0x7E 0xA1 0x11`), 4S..32S dynamic cell offsets, dual/multi NTC | 🟢 Verified on Real Hardware |
| **BM2 (Battery Monitor 2)** | 12V automotive battery broadcast parsing | 🟢 Verified on Real Hardware |
| **Daly BMS** | UART over BLE (`0xD2`, `0x90`..`0x98`) | 🟡 Unit Tested |
| **JK (Jikong) BMS** | Active balancing frame decoding | 🟡 Experimental |

---

## Key Features

- **Pure Kotlin JVM:** Zero Android SDK dependencies (`android.os.*` or `android.bluetooth.*`). Can be used on Android, desktop JVM, Raspberry Pi, server-side log analyzers, or CLI tools.
- **Robust Stream Reassembly:** Handles fragmented 20-byte BLE MTU chunks, concatenated frames, noise bytes, and checksum verification (`JBD` 16-bit complement checksum, `ANT` CRC).
- **Security & Password Management:** Full state-machine support for JBD `0x5A` EEPROM transactions (Enter Factory Mode $\to$ Read/Write Register $\to$ Save/Exit Mode).
- **Physical Trace Replay Testing:** Includes a testing harness that feeds real `.txt` / `.log` dumps into parsers to guarantee regression protection across firmware revisions.

---

## Quick Start

### 1. JBD / Xiaoxiang BMS Parsing

```kotlin
import com.figago.shared.bms.JbdBmsParser

val parser = JbdBmsParser()

// 1. Generate read requests
val basicInfoRequest: ByteArray = JbdBmsParser.createBasicInfoRequest() // DD A5 03 00 FF FD 77
val cellVoltagesRequest: ByteArray = JbdBmsParser.createCellVoltagesRequest() // DD A5 04 00 FF FC 77

// 2. Feed incoming BLE chunks from GATT notification characteristic
val status = parser.assemble(incomingBleBytes)
if (status != null) {
    println("Battery Voltage: ${status.voltage} V")
    println("Current: ${status.current} A")
    println("State of Charge (SoC): ${status.soc} %")
    println("Cell Voltages: ${status.cellVoltages}")
    println("Temperatures: ${status.temperatures} °C")
}
```

### 2. JBD Password Operations (`0x5A`)

```kotlin
// Enter Factory Mode with current password (e.g. 0x5678)
val authCmd = JbdBmsParser.createEnterFactoryModeCommand("5678")

// Clear password (set back to default)
val clearCmd = JbdBmsParser.createClearPasswordCommand()

// Set new password
val setCmd = JbdBmsParser.createSetPasswordCommand("1234")

// Commit & Exit Factory Mode
val exitCmd = JbdBmsParser.createExitFactoryModeCommand()
```

---

## How to Contribute Hardware Dumps

We are actively seeking raw BLE packet traces from real JBD / Xiaoxiang BMS hardware (especially from electric rickshaws, powerwalls, and DIY e-bikes).
Please see [`hardware-dumps/README.md`](hardware-dumps/README.md) for log submission instructions.

---

## License

This project is licensed under the [MIT License](LICENSE).

# open-bms-protocol

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-blue.svg)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-17-orange.svg)](https://openjdk.org)

Zero-dependency Kotlin library for Bluetooth Low Energy (BLE) communication with Smart Battery Management Systems (BMS).
Includes streaming packet decoders, command encoders, chunked MTU simulation, strict checksum verification, and a crowdsourced physical hardware packet trace corpus.

Originally extracted and maintained from production use in [**FigaroGo**](https://play.google.com/store/apps/details?id=com.figago).

---

## Included Pure-Kotlin Parsers & Verification Status

All 5 pure-Kotlin JVM parsers output the unified [`AntBmsStatus`](src/main/java/com/figago/shared/bms/BmsModels.kt) (`BmsData`) telemetry model.
The test suite currently passes **49 unit tests** (`AntBmsParserTest`: 10, `JbdBmsParserTest`: 39; 0 failures, 0 skipped, 0 errors), including line-by-line validation of [`hardware-dumps/jbd_xiaoxiang_4s_sample.txt`](hardware-dumps/jbd_xiaoxiang_4s_sample.txt).

See full details in [**`HARDWARE_MATRIX.md`**](HARDWARE_MATRIX.md).

| Parser Class | Protocol | Registers & Capabilities | Hardware Verification |
|---|---|---|---|
| `JbdBmsParser` | **JBD (Jiabaida) / Xiaoxiang** | Read-only telemetry (`0x03` Basic info, `0x04` Cell voltages, `0x05` Device model), streaming ACK/NACK decoding (`0x00`/`0x01`/`0x06`/`0x07`/`0x09`), strict `STATUS_OK` & CRC checks, `0x5A` spec reference helpers | 🟡 Simulated & Unit Tested |
| `JkBmsParser` | **JK (Jikong) BMS V1** | `0x4E 0x57` framing, `0x68` end-of-record check, 16-bit checksum verification, TLV telemetry (`0x79` cells, `0x80`–`0x82` temps, `0x83` voltage, `0x84` current, `0x85` SoC) | 🟡 Unit Tested (CRC & noise hardened) |
| `DalyBmsParser` | **Daly BMS** | UART over BLE 13-byte `0xA5` frames, strict `0x08` length & 8-bit checksum verification, `0x90` SoC/voltage/current decoding | 🟡 Unit Tested |
| `AntBmsParser` | **ANT BMS** | Live streaming status (`0x7E 0xA1 0x11`, `0xAA 0x55` trailer), 1S–32S dynamic cell offsets, multi-NTC & internal temps, MOS & cycle telemetry | 🟢 Verified on Real Hardware |
| `Bm2Parser` | **BM2 (Battery Monitor 2)** | 12V/24V automotive BLE broadcast AES-ECB decryption & SoC estimation | 🟢 Verified on Real Hardware |

---

## Key Features

- **Pure Kotlin JVM:** Zero Android SDK dependencies (`android.os.*` or `android.bluetooth.*`). Can be used on Android, desktop JVM, Raspberry Pi, server-side log analyzers, or CLI tools.
- **Robust Stream Reassembly & Strict Validation:** Handles fragmented 20-byte BLE MTU chunks, concatenated frames, noise bytes, and strict framing/checksum checks (`JBD` 16-bit two's complement checksum & register/length rules, `JK` 16-bit checksum & `0x68` marker, `Daly` 8-bit sum & 13-byte length, `ANT` header/trailer framing).
- **JBD `0x5A` Reference Helpers:** Includes frame builders for factory mode and password registers (`0x00`, `0x01`, `0x06`, `0x07`, `0x09`) alongside unified streaming `CommandResponse` ACK/NACK decoding.
- **Physical Trace Replay Testing:** `JbdBmsParserTest.testSampleDumpFileLineByLine` replays [`hardware-dumps/jbd_xiaoxiang_4s_sample.txt`](hardware-dumps/jbd_xiaoxiang_4s_sample.txt) line-by-line, verifying every TX checksum and every RX frame via `JbdBmsParser.isCommandResponseOk` and `JbdBmsParser.assemble`.

---

## Quick Start

### 1. Read-Only JBD / Xiaoxiang BLE Polling (Recommended for Production)

> **Production Recommendation:** Keep production BLE polling strictly **read-only** (`0xDD 0xA5 0x03`, `0x04`, `0x05`) until live physical GATT traces are collected and verified for your target BMS firmware revision.

```kotlin
import com.figago.shared.bms.JbdBmsParser

val parser = JbdBmsParser()

// 1. Generate read-only requests
val basicInfoRequest: ByteArray = JbdBmsParser.createBasicInfoRequest()       // DD A5 03 00 FF FD 77
val cellVoltagesRequest: ByteArray = JbdBmsParser.createCellVoltagesRequest() // DD A5 04 00 FF FC 77
val deviceNameRequest: ByteArray = JbdBmsParser.createDeviceNameRequest()     // DD A5 05 00 FF FB 77

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

### 2. JBD `0x5A` EEPROM / Password Write Frame Builders (Unverified Spec Reference)

> **Warning — Not Validated on Physical JBD Hardware:**
> The `0x5A` write frame builders (`createEnterFactoryModeRequest`, `createAuthPasswordRequest`, `createSetPasswordRequest`, `createClearPasswordRequest`, `createExitFactoryModeRequest`) are **protocol-spec reference helpers** and have **not yet been validated on physical JBD hardware**. Firmware revisions differ on EEPROM save/exit semantics (`0x01`) and password registers (`0x06`/`0x07`/`0x09`). Keep production BLE polling strictly read-only (`0xDD 0xA5 0x03/0x04/0x05`) until live GATT traces are collected.

```kotlin
// Enter Factory Mode (Register 0x00, payload 0x56 0x78)
val enterFactoryCmd = JbdBmsParser.createEnterFactoryModeRequest()

// Authenticate with 6-character ASCII password (Register 0x06)
val authCmd = JbdBmsParser.createAuthPasswordRequest("123456")

// Set new 6-character ASCII password (Register 0x07)
val setCmd = JbdBmsParser.createSetPasswordRequest("654321")

// Clear password (Register 0x09, payload 0x00 0x00)
val clearCmd = JbdBmsParser.createClearPasswordRequest()

// Save & Exit Factory Mode (Register 0x01, payload 0x28 0x28)
val exitCmd = JbdBmsParser.createExitFactoryModeRequest()
```

### 3. Running the Unit Tests

```bash
./gradlew test
```

Verified result: **49 tests passing** (`AntBmsParserTest`: 10, `JbdBmsParserTest`: 39, 0 failures, 0 skipped, 0 errors).

---

## How to Contribute Hardware Dumps

We are actively seeking raw BLE packet traces from real JBD / Xiaoxiang BMS hardware (especially from electric rickshaws, powerwalls, and DIY e-bikes).
Please see [`hardware-dumps/README.md`](hardware-dumps/README.md) for log submission instructions.

---

## License

This project is licensed under the [MIT License](LICENSE).

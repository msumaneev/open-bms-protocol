# Hardware & Protocol Verification Matrix

This matrix tracks the validation status of every supported pure-Kotlin BMS parser in `open-bms-protocol`.
We maintain a strict boundary between **Simulated / Unit-Tested** protocols and **Hardware-Verified** physical traces.

Current test suite coverage: **49 passing unit tests** (`AntBmsParserTest`: 10, `JbdBmsParserTest`: 39; 0 failures, 0 skipped, 0 errors).

| Parser Class | BMS Family | Typical Hardware / Devices | Registers / Commands | Validation Status | Real Traces in `hardware-dumps/` |
|---|---|---|---|---|---|
| `JbdBmsParser` | **JBD (Jiabaida) / Xiaoxiang** | E-Rickshaws, Solar powerwalls, DIY ebikes, Overkill Solar | **Read-only (recommended for production):**<br>`0x03` (Basic Pack Telemetry)<br>`0x04` (Cell Voltages)<br>`0x05` (Hardware Device Name)<br><br>**Unverified spec reference (`0x5A`):**<br>`0x00`/`0x01`/`0x06`/`0x07`/`0x09` EEPROM & password builders | 🟡 **Unit Tested & Simulated**<br>*(Looking for raw hardware traces!)* | [`jbd_xiaoxiang_4s_sample.txt`](hardware-dumps/jbd_xiaoxiang_4s_sample.txt) (CRC-verified reference trace, replayed line-by-line in `JbdBmsParserTest`) |
| `AntBmsParser` | **ANT BMS** | High-power electric mobility, 4S..32S battery packs | `0x7E 0xA1 0x11` (Streaming status with dynamic NTC & cell offsets) | 🟢 **Hardware Verified**<br>*(Battle-tested in FigaroGo)* | Coming soon |
| `Bm2Parser` | **BM2 (Battery Monitor)** | 12V / 24V Lead-Acid / LiFePO4 automotive monitors | AES-ECB BLE broadcast advertisement telemetry | 🟢 **Hardware Verified** | Coming soon |
| `DalyBmsParser` | **Daly BMS** | E-scooters, solar storage | Daly UART over BLE 13-byte `0xA5` frames (`0x90` SoC/V/I) with strict length & checksum validation | 🟡 **Unit Tested** | In progress |
| `JkBmsParser` | **JK (Jikong) BMS** | Active balancing 4S..24S packs | JK V1 `0x4E 0x57` framing, `0x68` end-of-record marker, 16-bit checksum & TLV telemetry (`0x79`, `0x80`–`0x85`) | 🟡 **Unit Tested & Hardened** | In progress |

---

## JBD `0x5A` EEPROM / Password Safety Notice

The JBD `0x5A` write frame builders in `JbdBmsParser` (`createEnterFactoryModeRequest`, `createAuthPasswordRequest`, `createSetPasswordRequest`, `createClearPasswordRequest`, `createExitFactoryModeRequest`) are **protocol-spec reference helpers** that have **not yet been validated on physical JBD hardware**.

- JBD firmware revisions differ on EEPROM exit/save payloads (`0x01` `0x28 0x28` vs `0x00 0x00`) and password register behavior (`0x06`/`0x07`/`0x09`).
- **Production recommendation:** Keep production BLE polling strictly **read-only** (`0xDD 0xA5 0x03`, `0x04`, `0x05`) until live GATT packet traces are captured and verified against physical hardware.

---

## Validation Status Definitions

- 🟢 **Hardware Verified:** Tested against real physical BMS hardware in production; raw BLE packet captures validated with zero CRC/frame errors across discharge/charge cycles.
- 🟡 **Unit Tested & Simulated:** Fully implemented protocol state machine, checksum algorithms, and packet encoders/decoders covered by JUnit tests using public specifications and simulated vectors.
- 🔴 **In Development:** Stub or work in progress.

---

## How to Help Us Verify JBD BMS Hardware

If you own an electric rickshaw, e-bike, scooter, or solar battery with a **JBD / Xiaoxiang / Overkill Solar BMS**:
1. Use **[FigaroGo on Google Play](https://play.google.com/store/apps/details?id=com.figago)** (tap **"Share BLE Log (Diagnostics)"** in the vehicle settings) or record a GATT capture with **nRF Connect**.
2. Submit an issue or Pull Request adding your trace to the [`hardware-dumps/`](hardware-dumps/) directory.
3. We will immediately add an automated replay test to verify your exact BMS firmware revision!

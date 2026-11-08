# Hardware & Protocol Verification Matrix

This matrix tracks the validation status of every supported BMS protocol in `open-bms-protocol`.
We maintain a strict boundary between **Simulated / Unit-Tested** protocols and **Hardware-Verified** physical traces.

| BMS Family | Typical Hardware / Devices | Registers / Commands | Validation Status | Real Traces in `hardware-dumps/` |
|---|---|---|---|---|
| **JBD (Jiabaida) / Xiaoxiang** | E-Rickshaws, Solar powerwalls, DIY ebikes, Overkill Solar | `0x03` (Basic Pack Telemetry)<br>`0x04` (Cell Voltages 4S..32S)<br>`0x05` (Hardware Device Name)<br>`0x5A` (EEPROM Mode, Password Write) | 🟡 **Unit Tested & Simulated**<br>*(Looking for raw hardware traces!)* | `jbd_xiaoxiang_4s_sample.txt` (simulated reference) |
| **ANT BMS** | High-power electric mobility, 7S..32S battery packs | `0x7E 0xA1 0x11` (Streaming status with dynamic NTC & cell offsets) | 🟢 **Hardware Verified**<br>*(Battle-tested in FigaGo)* | Coming soon |
| **BM2 (Battery Monitor)** | 12V Lead-Acid / LiFePO4 automotive monitors | Broadcast advertisement telemetry | 🟢 **Hardware Verified** | Coming soon |
| **Daly BMS** | E-scooters, solar storage | Standard Daly UART over BLE (`0xD2`, `0x90`..`0x98`) | 🟡 **Unit Tested** | In progress |
| **JK (Jikong) BMS** | Active balancing 4S..24S packs | JK BLE framing protocol | 🟡 **Experimental** | In progress |

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

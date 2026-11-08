# Raw BLE Hardware Dumps & Packet Traces

This directory contains raw BLE HEX traces collected from physical BMS devices or captured via diagnostic tools.

## Dump Format

Dumps are plain text files (`.txt` or `.log`) containing timestamped `TX` (commands sent to BMS) and `RX` (GATT notifications received from BMS) packets in hexadecimal format:

```text
=== FIGAROGO JBD BMS BLE DIAGNOSTIC DUMP ===
App Version: 3.11.30 (200177)
Device: Samsung SM-S936B (SDK 36)
Timestamp: 2026-11-08 13:00:00.000
Hardware Model: JBD-SP04S034
Stage: BASIC_INFO

[13:00:01.100] [STAGE: BASIC_INFO] TX: DD 5A 00 02 56 78 FF 30 77
[13:00:01.150] [STAGE: BASIC_INFO] RX: DD 00 00 00 FF FF 77
[13:00:01.200] [STAGE: BASIC_INFO] TX: DD A5 03 00 FF FD 77
[13:00:01.250] [STAGE: BASIC_INFO] RX: DD 03 00 1D 06 0B 00 00 01 ED 01 F4 00 00 2C 7C 00 00 00 00 10 00 80 63 02 04 03 0B A0 0B 9D 0B 98 FA 55 77
[13:00:01.300] [STAGE: CELL_VOLTAGES] TX: DD A5 04 00 FF FC 77
[13:00:01.350] [STAGE: CELL_VOLTAGES] RX: DD 04 00 08 0F 23 0F 1C 0F 12 0F 1D FF 4E 77
```

## How to Contribute Dumps

1. **Option A (Android app 1-tap):** Install **[FigaroGo](https://play.google.com/store/apps/details?id=com.figago)**, connect to your BMS, go to vehicle settings, and tap **"Share BLE Log (Diagnostics)"**.
2. **Option B (nRF Connect / Wireshark / btsnoop_hci):** Export raw notifications from service `0000ff00-0000-1000-8000-00805f9b34fb` (RX `0000ff01`, TX `0000ff02`).
3. Open a Pull Request or GitHub Issue with your log file.

# Backup Firmware Versions

These firmware binaries have different BLE transfer speeds. Use them to find the right balance between speed and reliability for your setup.

## Available Versions

| Filename | Delay (ms) | Target Time | Notes |
|----------|------------|-------------|-------|
| `firmware_fast_10ms.bin` | 10 | <1s | **Fastest** - May have BLE errors with large photos |
| `firmware_1.5s_50ms.bin` | 50 | ~1.5s | Good balance of speed and reliability |
| `firmware_2.5s_80ms.bin` | 80 | ~2.5s | Conservative |
| `firmware_3s_100ms.bin` | 100 | ~3s | More conservative |
| `firmware_4s_130ms.bin` | 130 | ~4s | Safe for weak connections |
| `firmware_5s_170ms.bin` | 170 | ~5s | **Most reliable** - Slowest transfer |

## Common Settings

All versions share these settings:
- **BLE_CHUNK_SIZE**: 500 bytes
- **Camera Orientation**: 180° flipped (vflip=1, hmirror=1)
- **Board**: Seeed XIAO ESP32S3

## Flashing

Flash using esptool or the PlatformIO upload command:

```bash
esptool.py --chip esp32s3 --port COM_PORT write_flash 0x0 firmware_XXXX.bin
```

Or copy the desired firmware to `.pio/build/seeed_xiao_esp32s3/firmware.bin` and use `platformio run -t upload`.

## Transfer Time Estimates

Transfer time depends on:
- Photo size (typically 8-18KB in VGA/QVGA mode)
- BLE connection quality
- Android device BLE stack

Formula: `Time ≈ (photo_size / 500) × delay_ms`

Example: 15KB photo with 50ms delay = (15000/500) × 50ms = 1.5s

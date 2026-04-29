# SUNMI MQTT Printer MVP

Incremental Android Studio project in Kotlin for a SUNMI P2. The built-in printer integration remains in place, and this version adds the first MQTT-based printing flow.

## What this MVP does

- Keeps the existing SUNMI built-in printer integration
- Connects to an MQTT broker
- Subscribes to the CL4P-TP SUNMI print request topic
- Parses incoming messages into print jobs
- Serializes print execution so jobs do not overlap
- Publishes print lifecycle status back to CL4P-TP
- Prints every valid job as:
  1. top ASCII separator
  2. centered `res/drawable/clp_logo.png`
  3. left-padded wrapped text body
  4. bottom ASCII separator
- Shows printer, MQTT, and print pipeline status on screen

## Project assumptions

- Target device: SUNMI P2
- Android version: 7.1.1
- SUNMI OS version: 1.5.0
- Current scope: first MQTT MVP only

## Open in Android Studio

1. Open Android Studio.
2. Choose `Open`.
3. Select the folder `C:\Users\mathe\code\CL4P-TP_Printer`.
4. Let Gradle sync finish.
5. If this is the first build on the machine, keep internet access enabled so Gradle can download project dependencies.

## Build the debug APK

### Option 1: Android Studio UI

1. In Android Studio, choose `Build > Build APK(s)`.
2. Wait for the build to finish.

### Option 2: Command line

From the project root:

```powershell
.\gradlew.bat assembleDebug
```

## Where the APK is generated

After a successful debug build, the APK is generated at:

`C:\Users\mathe\code\CL4P-TP_Printer\app\build\outputs\apk\debug\app-debug.apk`

## MQTT configuration

Edit the MQTT settings in:

- `app/src/main/java/com/matheusoliveira/cl4ptpprinter/mqtt/MqttConfig.kt`

The following values are centralized there:

- broker host
- broker port
- username
- password
- client ID
- print request topic
- print status topic
- QoS

If the NAS-side topic and payload contract changes later, update `MqttConfig.kt` and `MqttPayloadParser.kt` instead of scattering those assumptions across the app.

## Expected MQTT payload

The integrated CL4P-TP payload is the standard envelope published to `cl4ptp/sunmi/print/request`:

```json
{
  "id": "pedido-123",
  "type": "sunmi.print.request",
  "source": "api",
  "timestamp": "2026-03-24T21:00:00.000Z",
  "correlationId": "pedido-123",
  "version": 1,
  "data": {
    "content": "Mensagem para imprimir"
  }
}
```

The app publishes status envelopes to `cl4ptp/sunmi/print/status` with `queued`, `printing`, `completed`, or `failed`.

For local/manual testing, this MVP also supports:

1. Plain text payload:

```text
Mensagem simples para imprimir
```

2. Minimal JSON payload:

```json
{
  "jobId": "pedido-123",
  "text": "Mensagem para imprimir"
}
```

If the final NAS contract is different, update the parser in:

- `app/src/main/java/com/matheusoliveira/cl4ptpprinter/mqtt/MqttPayloadParser.kt`

## Print format rules

Every valid printable MQTT message is printed in this order:

1. ASCII separator
2. centered header image
3. left-padded wrapped text body
4. ASCII separator

The header image is always:

- loaded from `res/drawable/clp_logo.png`
- centered independently from the text layout
- printed without stretching or cropping
- restored back to left-aligned text afterwards

The text body is always:

- formatted by `PrintFormatter`
- padded on the left with a fixed number of spaces
- wrapped using word-based logic
- kept independent from the image width and image position
- configured from one central layout object

Current margin and width values are configurable approximations for 58mm thermal paper and should be fine-tuned after real-world tests:

- `app/src/main/java/com/matheusoliveira/cl4ptpprinter/printer/PrintLayoutConfig.kt`

## How to test with a sample MQTT message

After updating `MqttConfig.kt`, connect the app and publish a sample message to the configured topic.

Example with `mosquitto_pub` and plain text:

```bash
mosquitto_pub -h YOUR_BROKER_HOST -p 1883 -t cl4ptp/sunmi/print/request -m "Mensagem curta de teste"
```

Example with JSON:

```bash
mosquitto_pub -h YOUR_BROKER_HOST -p 1883 -t cl4ptp/sunmi/print/request -m "{\"jobId\":\"pedido-001\",\"text\":\"Mensagem longa com acentos: ação, impressão e São Paulo.\"}"
```

Use the on-screen `Test Printer` button for a local end-to-end print without MQTT.

## Manual install on the SUNMI device

Because `adb` may not be available on the device, transfer the APK manually using any convenient method:

- USB file transfer from a PC
- MicroSD card or USB storage, if the device supports it
- Cloud storage download
- Email attachment download
- Internal network file share

Then:

1. Transfer `app-debug.apk` to the device.
2. Open the device file manager.
3. Tap the APK file.
4. Approve the install prompt.
5. Open the app named `SUNMI MQTT Printer`.

## Enable installation from unknown sources on Android 7.1.1

On Android 7.1.1 this is usually a global setting:

1. Open `Settings`.
2. Open `Security`.
3. Enable `Unknown sources`.
4. Confirm the warning dialog.

On some SUNMI software builds the menu wording can vary slightly, but it is usually still under `Security`.

## Expected result

When the app starts, the status area should show printer, MQTT, and print pipeline updates such as:

- `Printer: Printer ready.`
- `MQTT: MQTT subscribed to cl4ptp/sunmi/print/request`
- `Print: No print jobs yet.`

When you publish a valid MQTT print message or press `Test Printer`, the printer should print something similar to:

```text
================================
[centered CLP logo]
    Mensagem formatada com margem
    esquerda fixa e quebra por
    palavras.
================================
```

## What is intentionally not implemented yet

This MVP does not implement:

- retry policies
- persistent queue
- local database
- remote image download
- HTML rendering
- final NAS-side contract negotiation beyond the isolated config/parser files

If the printer service, MQTT broker, or payload is invalid, the app should not crash. The status area and logs should show what failed.

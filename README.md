# RC Car

Phone-controlled Wi-Fi car: ESP32 DevKit + TB6612FNG motor driver + 2 TT motors.
The ESP32 makes its own Wi-Fi network; drive it from the **RC Pilot** Android app or from a web page.

## Parts and power

| Part | Power |
|---|---|
| ESP32 DevKit | USB power bank |
| TB6612FNG logic (VCC) | ESP32 3V3 |
| Motors (VM) | 4x AA holder through a switch |

All GNDs are joined.

## Wiring (ESP32 -> TB6612)

| ESP32 | TB6612 |
|---|---|
| D13 | PWMA |
| D27 | AIN1 |
| D19 | AIN2 |
| D18 | PWMB |
| D32 | BIN1 |
| D33 | BIN2 |
| D4 | STBY |
| 3V3 | VCC |
| GND | GND |

Left motor on A01/A02, right motor on B01/B02.

## Drive it

1. Join Wi-Fi **RC-Car** (password `drive1234`).
2. Open the **RC Pilot** app, or http://192.168.4.1 in a browser.

The wheels stop by themselves 400 ms after the phone stops sending.

## Folders

| Folder | What it is |
|---|---|
| `rc_car/` | Car firmware: web page, HTTP drive, UDP drive on port 4210 for the app |
| `rc-pilot/` | Android app (Kotlin, Jetpack Compose): 2-stick / 1-stick / tilt modes, ping, signal, boost, park, trim |
| `wire_test/` | Runs each wheel forward and back, for a bench check |
| `conn_check/`, `live_check/`, `esp_check/` | Wiring checks over serial (115200) |
| `fpv_car/`, `cam_test/` | Older ESP32-CAM version with video (not used now) |
| `guide.html`, `assembly/`, PDFs | Build guide and chassis assembly steps |

## Build

Firmware (board "ESP32 Dev Module"):

    arduino-cli compile --fqbn esp32:esp32:esp32 --upload -p <port> rc_car

App:

    cd rc-pilot && ./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk

## UDP protocol (port 4210)

| App sends | Car does |
|---|---|
| `D <seq> <left> <right>` (each -100..100) | sets motor power, replies `A <seq> <rssi>` |
| `L 0` / `L 1` | blue LED off / on |

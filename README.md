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

## Front sonar (HC-SR04)

| HC-SR04 | ESP32 |
|---|---|
| VCC | VIN (5 V) |
| GND | GND |
| TRIG | D25 |
| ECHO | 1k -> D26, and 2k from D26 to GND (5 V -> 3.3 V divider) |

What it does:

- **Bumper guard** (manual): forward is blocked closer than 15 cm; reverse and spinning still work.
- **Auto mode**: cruise, back up and turn away from anything closer than 30 cm; turns longer when it keeps hitting the same corner. Drops out of auto if the phone goes quiet for 1.2 s.
- Firmware runs as FreeRTOS tasks: `sonarTask` (16 Hz, median of 3), `driveTask` (50 Hz, the only writer of the motors), comms in `loop()` + the HTTP server task.

## Drive it

1. Join Wi-Fi **RC-Car** (password `drive1234`).
2. Open the **RC Pilot** app, or http://192.168.4.1 in a browser.

The wheels stop by themselves 400 ms after the phone stops sending.

## Folders

| Folder | What it is |
|---|---|
| `rc_car/` | Car firmware: web page, HTTP drive, UDP drive on port 4210 for the app |
| `rc-pilot/` | Android app (Kotlin, Jetpack Compose): 2-stick / 1-stick / tilt modes, ping, signal, boost, park, trim |
| `rc-pilot-dash/` | Same app with a first-person cabin: drawn road view, wheel, P R N D lever, gas + brake pedals, power and signal gauges |
| `wire_test/` | Runs each wheel forward and back, for a bench check |
| `sonar_check/` | HC-SR04 pin check on the ESP32 DevKit (D25/D26, tries both pin orders) |
| `cam_sonar_check/` | HC-SR04 check on the ESP32-CAM (GPIO13/14) |
| `uno_sonar_check/` | HC-SR04 check on an Arduino UNO (D9/D10, 5 V so no divider) |
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
| `D <seq> <left> <right>` (each -100..100) | sets motor power, replies `A <seq> <rssi> <range_cm> <auto> <blocked> <outL> <outR>` |
| `M 0` / `M 1` | auto mode off / on |
| `L 0` / `L 1` | blue LED off / on |

`range_cm` is -1 when nothing is in reach. `auto` is 0 off, 1 cruise, 2 backing, 3 turning.

HTTP: `/go?d=F|B|L|R|S`, `/speed?v=30..100`, `/light?on=0|1`, `/auto?on=0|1`, `/state` (JSON `{d,a,g,l,r}`, also the heartbeat for auto mode).

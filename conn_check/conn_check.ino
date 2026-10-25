// Connection check - ESP32 DevKit -> TB6612, no motors or VM needed
// 1. Short test: pulls one signal pin LOW at a time, the others must stay HIGH.
// 2. Wire test: the TB6612 has ~200k pull-down resistors on STBY/PWM/IN pins.
//    With the ESP32's ~45k pull-up on, a connected pin sits near 2.95 V; a loose one reads ~3.06 V+.
//    A pin only reads "connected" if both its wire AND GND are on.
// GPIO18/19 are not analog pins, so they get a digital test only (catches GND shorts, not loose wires).

#include "driver/rtc_io.h"

struct Pin { int gpio; const char *tb; };
const Pin PINS[] = {{4, "STBY"}, {13, "PWMA"}, {27, "AIN1"}, {19, "AIN2"},
                    {18, "PWMB"}, {32, "BIN1"}, {33, "BIN2"}};
const int N = sizeof(PINS) / sizeof(PINS[0]);
const int CONNECTED_MAX_MV = 3010;   // measured: wired 2930-2960 mV, loose 3060+ mV

void shortTest() {
  Serial.println("\n-- short test (signal pins touching each other) --");
  bool any = false;
  for (int i = 0; i < N; i++) {
    for (int k = 0; k < N; k++) pinMode(PINS[k].gpio, INPUT_PULLUP);
    pinMode(PINS[i].gpio, OUTPUT);
    digitalWrite(PINS[i].gpio, LOW);
    delay(5);
    for (int k = i + 1; k < N; k++) {
      if (digitalRead(PINS[k].gpio) == LOW) {
        Serial.printf("  [SHORT] GPIO%d (%s) touches GPIO%d (%s)\n",
                      PINS[i].gpio, PINS[i].tb, PINS[k].gpio, PINS[k].tb);
        any = true;
      }
    }
  }
  for (int k = 0; k < N; k++) pinMode(PINS[k].gpio, INPUT);
  Serial.println(any ? "  shorts found (if one pin is on GND, every pin looks shorted to it)" : "  [ OK ] no shorts between signal pins");
}

void wireTest() {
  Serial.println("\n-- wire test (needs GND and 3V3->VCC connected) --");
  for (int i = 0; i < N; i++) {
    int g = PINS[i].gpio;
    if (!rtc_gpio_is_valid_gpio((gpio_num_t)g)) {
      pinMode(g, INPUT_PULLUP); delay(5);
      bool high = digitalRead(g) == HIGH;
      pinMode(g, INPUT);
      Serial.printf("  GPIO%-2d -> %-4s  digital   %s\n", g, PINS[i].tb,
                    high ? "[ ?? ] not on GND (cannot tell wired vs loose on this pin)" : "[SHORT?] held LOW, touching GND");
      continue;
    }
    analogReadMilliVolts(g);                 // puts the pin in analog mode
    rtc_gpio_pullup_en((gpio_num_t)g);       // weak pull-up against the TB6612 pull-down
    rtc_gpio_pulldown_dis((gpio_num_t)g);
    delay(20);
    uint32_t mv = 0;
    for (int s = 0; s < 16; s++) mv += analogReadMilliVolts(g);
    mv /= 16;
    rtc_gpio_pullup_dis((gpio_num_t)g);
    Serial.printf("  GPIO%-2d -> %-4s  %4u mV  %s\n", g, PINS[i].tb, mv,
                  mv < 300 ? "[SHORT?] stuck at 0 V, touching GND" :
                  mv <= CONNECTED_MAX_MV ? "[ OK ] connected" : "[ -- ] not connected yet");
  }
}

void setup() {
  Serial.begin(115200);
  delay(800);
  Serial.println("\n=== ESP32 -> TB6612 connection check ===");
  Serial.println("  [ OK ] ESP32 running (no dead short on 3V3)");
  shortTest();
  wireTest();
  Serial.println("\n=== done, press EN to run again ===");
}

void loop() {}

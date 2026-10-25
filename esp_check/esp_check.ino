// ESP32 DevKit pin health check
// For each usable GPIO: can it drive HIGH, drive LOW, and do its pull-up / pull-down work?
// A pin that fails is damaged, or is shorted to GND/3V3/another pin by a wire or solder blob.
// The TB6612 pins (4, 13, 25, 26, 27, 32, 33) pass even while wired: its ~200k pull-downs are too weak to matter.

#include "driver/gpio.h"

const int PINS[] = {2, 4, 5, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27, 32, 33};
const int N = sizeof(PINS) / sizeof(PINS[0]);

bool readBack(int p, int level) {
  gpio_set_direction((gpio_num_t)p, GPIO_MODE_INPUT_OUTPUT);
  gpio_set_level((gpio_num_t)p, level);
  delayMicroseconds(200);
  return gpio_get_level((gpio_num_t)p) == level;
}

void setup() {
  Serial.begin(115200);
  delay(800);
  Serial.println("\n=== ESP32 pin health check ===");
  int bad = 0;
  for (int i = 0; i < N; i++) {
    int p = PINS[i];
    gpio_reset_pin((gpio_num_t)p);
    bool hi = readBack(p, 1), lo = readBack(p, 0);
    gpio_set_direction((gpio_num_t)p, GPIO_MODE_INPUT);
    pinMode(p, INPUT_PULLUP);   delay(2); bool pu = digitalRead(p) == HIGH;
    pinMode(p, INPUT_PULLDOWN); delay(2); bool pd = digitalRead(p) == LOW;
    pinMode(p, INPUT);
    bool ok = hi && lo && pu && pd;
    if (!ok) bad++;
    Serial.printf("  GPIO%-2d  drive H %s  drive L %s  pull-up %s  pull-down %s  %s\n", p,
                  hi ? "ok" : "--", lo ? "ok" : "--", pu ? "ok" : "--", pd ? "ok" : "--",
                  ok ? "[ OK ]" : (!pu ? "[FAIL] held LOW: touching GND?" : !pd ? "[FAIL] held HIGH: touching 3V3?" : "[FAIL] pin cannot drive"));
  }
  Serial.printf("\n  %d of %d pins OK. 34-39 are input-only, 6-11 run the flash, 1/3 are USB serial: not tested.\n", N - bad, N);
  Serial.println("=== done ===");
}

void loop() {}

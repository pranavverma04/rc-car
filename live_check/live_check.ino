// Live check - reads PWMB (GPIO25) and AIN2 (GPIO26) twice a second.
// Same idea as conn_check: wired to TB6612 ~2.95 V, loose ~3.06 V+, touching GND ~0 V.

#include "driver/rtc_io.h"

const int LIVE[] = {25, 26};
const char *NAME[] = {"PWMB", "AIN2"};

const char *state(uint32_t mv) {
  return mv < 300 ? "GND SHORT" : mv <= 3010 ? "CONNECTED" : "loose";
}

void setup() {
  Serial.begin(115200);
  for (int g : LIVE) {
    analogReadMilliVolts(g);
    rtc_gpio_pullup_en((gpio_num_t)g);
    rtc_gpio_pulldown_dis((gpio_num_t)g);
  }
}

void loop() {
  for (int i = 0; i < 2; i++) {
    uint32_t mv = 0;
    for (int s = 0; s < 8; s++) mv += analogReadMilliVolts(LIVE[i]);
    mv /= 8;
    Serial.printf("%s %4u mV %-9s   ", NAME[i], mv, state(mv));
  }
  Serial.println();
  delay(500);
}

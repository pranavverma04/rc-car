// HC-SR04 check on the ESP32-CAM (AI Thinker). No camera, no Wi-Fi: just the sensor.
// Wiring: VCC -> 5V, GND -> GND, TRIG -> GPIO13, ECHO -> GPIO14 (1k in series if you have one).
// Board: "AI Thinker ESP32-CAM". Flash with GPIO0 to GND, then release and reset. Serial Monitor 115200.
// Good sensor: "ECHO wire: held LOW", and cm changes when you wave a hand 20-50 cm in front.
const int TRIG = 13, ECHO = 14;

const char *wire(int pin) {   // a floating pin follows the internal pull; a pin tied to the sensor's output does not
  pinMode(pin, INPUT_PULLUP);   delayMicroseconds(50); int up = digitalRead(pin);
  pinMode(pin, INPUT_PULLDOWN); delayMicroseconds(50); int dn = digitalRead(pin);
  pinMode(pin, INPUT);
  if (up == 1 && dn == 0) return "floating: ECHO wire not connected or sensor has no power";
  if (up == 0 && dn == 0) return "held LOW: ECHO connected, sensor powered";
  return "held HIGH: wrong pin or sensor stuck";
}

void setup() {
  Serial.begin(115200);
  pinMode(TRIG, OUTPUT); digitalWrite(TRIG, LOW);
  pinMode(ECHO, INPUT);
  delay(300);
  Serial.println("\nHC-SR04 check on ESP32-CAM. Wave a hand 20-50 cm in front.");
}

void loop() {
  const char *w = wire(ECHO);
  digitalWrite(TRIG, LOW);  delayMicroseconds(4);
  digitalWrite(TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  uint32_t us = pulseIn(ECHO, HIGH, 30000);
  if (us == 0) Serial.printf("no echo        ECHO wire: %s\n", w);
  else         Serial.printf("%4lu us = %3lu cm   ECHO wire: %s\n", us, us / 58, w);
  delay(500);
}

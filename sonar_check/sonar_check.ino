// HC-SR04 wiring check. Serial 115200. Expected: TRIG D23, ECHO D22 (through a divider, or direct).
// Prints every 700 ms: is the ECHO wire connected or floating, raw echo time normal and with TRIG/ECHO swapped.
const int A = 23, B = 22;

uint32_t ping(int trig, int echo) {
  pinMode(trig, OUTPUT); pinMode(echo, INPUT);
  digitalWrite(trig, LOW); delayMicroseconds(4);
  digitalWrite(trig, HIGH); delayMicroseconds(10);
  digitalWrite(trig, LOW);
  return pulseIn(echo, HIGH, 30000);
}

const char *wire(int pin) {   // a floating pin follows the internal pull; a pin tied to a driven output does not
  pinMode(pin, INPUT_PULLUP);   delayMicroseconds(50); int up = digitalRead(pin);
  pinMode(pin, INPUT_PULLDOWN); delayMicroseconds(50); int dn = digitalRead(pin);
  pinMode(pin, INPUT);
  if (up == 1 && dn == 0) return "floating (nothing driving it)";
  if (up == 0 && dn == 0) return "held LOW (sensor output, idle)";
  return "held HIGH (stuck / wrong pin)";
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\nHC-SR04 check. Wave a hand 20-50 cm in front.");
}

void loop() {
  Serial.printf("D22: %-32s  D23: %-32s  ", wire(B), wire(A));
  uint32_t n = ping(A, B);     // normal: TRIG 23, ECHO 22
  delay(60);
  uint32_t s = ping(B, A);     // swapped
  Serial.printf("echo normal %5lu us (%3ld cm)   swapped %5lu us (%3ld cm)\n", n, n / 58, s, s / 58);
  pinMode(A, INPUT); pinMode(B, INPUT);
  delay(700);
}

// HC-SR04 on UNO, tries both pin orders: normal (TRIG D9, ECHO D10) and swapped (TRIG D10, ECHO D9).
// Also reports the idle level of each pin so a wire on the wrong header pin shows up.
unsigned long ping(int t, int e) {
  pinMode(t, OUTPUT); pinMode(e, INPUT);
  digitalWrite(t, LOW); delayMicroseconds(4);
  digitalWrite(t, HIGH); delayMicroseconds(10);
  digitalWrite(t, LOW);
  return pulseIn(e, HIGH, 30000);
}
const char *lvl(int p) { pinMode(p, INPUT_PULLUP); delayMicroseconds(50); int v = digitalRead(p); pinMode(p, INPUT); return v ? "float" : "LOW"; }
void setup() { Serial.begin(115200); Serial.println("\nHC-SR04 both-ways check"); }
void loop() {
  const char *a = lvl(9); const char *b = lvl(10);
  unsigned long n = ping(9, 10); delay(60);
  unsigned long s = ping(10, 9);
  pinMode(9, INPUT); pinMode(10, INPUT);
  Serial.print("D9 idle "); Serial.print(a); Serial.print("  D10 idle "); Serial.print(b);
  Serial.print("   normal(9->10) "); Serial.print(n / 58); Serial.print(" cm   swapped(10->9) "); Serial.print(s / 58); Serial.println(" cm");
  delay(400);
}

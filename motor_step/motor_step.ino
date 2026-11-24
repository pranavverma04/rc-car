// Motor step - ESP32 DevKit + TB6612, same pins as rc_car
// Moves ONE wheel for 3 s per serial command (115200), so you can watch each wheel on its own.
//   lf / lb  = left forward / backward      rf / rb  = right forward / backward
// Lift the car and turn the battery switch on first.

const int PWMA = 13, AIN1 = 27, AIN2 = 19;   // right side (A01/A02)
const int PWMB = 18, BIN1 = 32, BIN2 = 33;   // left side  (B01/B02)
const int STBY = 4, LED = 2;

void side(int pwm, int in1, int in2, int dir) {
  digitalWrite(in1, dir > 0 ? HIGH : LOW);
  digitalWrite(in2, dir < 0 ? HIGH : LOW);
  ledcWrite(pwm, dir ? 255 : 0);   // full power: a stiff motor or weak contact may not start lower
}

void setup() {
  int pins[] = {STBY, AIN1, AIN2, BIN1, BIN2, LED};
  for (int p : pins) { pinMode(p, OUTPUT); digitalWrite(p, LOW); }
  ledcAttach(PWMA, 20000, 8); ledcWrite(PWMA, 0);
  ledcAttach(PWMB, 20000, 8); ledcWrite(PWMB, 0);
  Serial.begin(115200);
  delay(300);
  digitalWrite(STBY, HIGH);
  Serial.println("\nmotor_step ready: lf lb rf rb");
}

void loop() {
  if (!Serial.available()) return;
  String c = Serial.readStringUntil('\n');
  c.trim();
  int pwm, in1, in2, dir;
  if      (c == "lf") { pwm = PWMB; in1 = BIN1; in2 = BIN2; dir = 1; }
  else if (c == "lb") { pwm = PWMB; in1 = BIN1; in2 = BIN2; dir = -1; }
  else if (c == "rf") { pwm = PWMA; in1 = AIN1; in2 = AIN2; dir = 1; }
  else if (c == "rb") { pwm = PWMA; in1 = AIN1; in2 = AIN2; dir = -1; }
  else return;
  Serial.printf("%s running 3 s...", c.c_str());
  digitalWrite(LED, HIGH);
  side(pwm, in1, in2, dir);
  delay(3000);
  side(pwm, in1, in2, 0);
  digitalWrite(LED, LOW);
  Serial.println(" stopped");
}

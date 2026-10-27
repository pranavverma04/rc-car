// Wire test - ESP32 DevKit + TB6612, same pins as rc_car
// Runs each motor output one at a time, slowly, and says on serial (115200) what it is doing.
// With no motors: put the multimeter (DC volts) on A01-A02 or B01-B02 and watch the sign flip.
// With motors: lift the car, watch each wheel.

const int PWMA = 13, AIN1 = 27, AIN2 = 19;
const int PWMB = 18, BIN1 = 32, BIN2 = 33;
const int STBY = 4, LED = 2;

void side(int pwm, int in1, int in2, int dir) {
  digitalWrite(in1, dir > 0 ? HIGH : LOW);
  digitalWrite(in2, dir < 0 ? HIGH : LOW);
  ledcWrite(pwm, dir ? 150 : 0);   // ~60% power
}

void step(const char *what, int pwm, int in1, int in2, int dir) {
  Serial.printf("%-28s", what);
  digitalWrite(LED, HIGH);
  side(pwm, in1, in2, dir);
  delay(2000);
  side(pwm, in1, in2, 0);
  digitalWrite(LED, LOW);
  Serial.println("done");
  delay(1000);
}

void setup() {
  int pins[] = {STBY, AIN1, AIN2, BIN1, BIN2, LED};
  for (int p : pins) { pinMode(p, OUTPUT); digitalWrite(p, LOW); }
  ledcAttach(PWMA, 20000, 8); ledcWrite(PWMA, 0);
  ledcAttach(PWMB, 20000, 8); ledcWrite(PWMB, 0);
  Serial.begin(115200);
  delay(500);
  Serial.println("\n=== TB6612 wire test ===");
  digitalWrite(STBY, HIGH);
}

void loop() {
  step("LEFT  forward  (B01 +)", PWMB, BIN1, BIN2, 1);    // left motor is on B01/B02
  step("LEFT  backward (B01 -)", PWMB, BIN1, BIN2, -1);
  step("RIGHT forward  (A01 +)", PWMA, AIN1, AIN2, 1);
  step("RIGHT backward (A01 -)", PWMA, AIN1, AIN2, -1);
  Serial.println("--- repeat in 3 s ---");
  delay(3000);
}

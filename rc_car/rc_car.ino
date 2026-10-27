// RC Car - ESP32 DevKit + TB6612FNG + HC-SR04 front sonar
// Phone joins Wi-Fi "RC-Car" (password drive1234), opens http://192.168.4.1
//
// HTTP:  /go?d=F|B|L|R|S   /speed?v=30..100   /light?on=0|1   /auto?on=0|1   /state (JSON, also a heartbeat)
// UDP 4210 (RC Pilot app):
//   "D <seq> <left> <right>"  each -100..100  ->  "A <seq> <rssi> <range_cm> <auto> <blocked> <outL> <outR>"
//   "M 0|1"  auto-pilot off/on       "L 0|1"  blue LED
//   range_cm = -1 when nothing in reach, auto = 0 off / 1 cruise / 2 backing / 3 turning
//
// Tasks (FreeRTOS, both on core 1; Wi-Fi lives on core 0):
//   sonarTask  ~16 Hz  pings the HC-SR04, median of 3, writes rangeCm
//   driveTask   50 Hz  the only writer of the motors: failsafe, bumper guard, auto-pilot state machine
//   loop()             UDP + serial. HTTP runs in the esp_http_server task.
// Board: "ESP32 Dev Module"

#include <WiFi.h>
#include <WiFiUdp.h>
#include "esp_http_server.h"
#include "esp_wifi.h"

// ---- Wi-Fi ----
const char *AP_SSID = "RC-Car";
const char *AP_PASS = "drive1234";   // at least 8 characters

// ---- Motor pins (TB6612) ----
const int PWMA = 13, AIN1 = 27, AIN2 = 19;   // right side (A01/A02)
const int PWMB = 18, BIN1 = 32, BIN2 = 33;   // left side  (B01/B02)
const int STBY = 4;                          // LOW = driver off, so the motors stay still while the board boots
const int LED = 2;                           // blue LED on the DevKit

// ---- Sonar pins (HC-SR04) ----
// ECHO is a 5 V signal: go through 1k (sensor side) + 2k (to GND), tap the middle for GPIO26.
const int TRIG = 25, ECHO = 26;

const uint32_t PWM_HZ = 20000;   // above hearing, no motor whine
const uint8_t PWM_BITS = 8;      // duty 0..255

// ---- Safety ----
const uint32_t FAILSAFE_MS = 400;       // manual: stop if no drive command for this long
const uint32_t AUTO_LINK_MS = 1200;     // auto: drop out of auto if the phone has gone quiet this long
const int GUARD_CM = 15;                // manual: forward is blocked closer than this (reverse and spin still work)

// ---- Auto-pilot ----
const int AVOID_CM = 30;                // start avoiding
const int CLEAR_CM = 45;                // path counts as clear again
const int AUTO_SPEED = 55;              // cruise power %
const int TURN_SPEED = 60;              // spin power %
const uint32_t BACK_MS = 350;
const uint32_t TURN_MS = 450;
const uint32_t STUCK_WINDOW_MS = 2500;  // two avoids this close together = a corner: turn longer

// ---- Sonar timing ----
const uint32_t SONAR_PERIOD_MS = 60;
const uint32_t ECHO_TIMEOUT_US = 25000; // ~4.3 m round trip
const int SONAR_MAX_CM = 400;

const uint16_t UDP_PORT = 4210;
WiFiUDP udp;

// Shared state. 32-bit reads/writes are atomic on the ESP32, so plain volatiles are enough here.
volatile int wantL = 0, wantR = 0;          // what the phone asks for (manual)
volatile int outL = 0, outR = 0;            // what is on the motors
volatile int rangeCm = -1;                  // front distance, -1 = nothing in reach / no echo
volatile uint8_t autoState = 0;             // 0 off, 1 cruise, 2 backing, 3 turning
volatile bool autoWanted = false;           // phone asked for auto
volatile bool blocked = false;              // guard is holding the car back right now
volatile uint32_t lastCmdMs = 0;            // last drive command (D packet or /go)
volatile uint32_t lastSeenMs = 0;           // last sign of life of any kind (/state polls too)
volatile int speedPct = 80;                 // web page speed; TT motors stall below ~30%

// ---------- Motors (driveTask only) ----------
void side(int pwm, int in1, int in2, int val) {   // val -100..100, sign = direction
  digitalWrite(in1, val > 0 ? HIGH : LOW);
  digitalWrite(in2, val < 0 ? HIGH : LOW);
  ledcWrite(pwm, abs(val) * 255 / 100);
}
void setMotors(int l, int r) {
  l = constrain(l, -100, 100);
  r = constrain(r, -100, 100);
  side(PWMB, BIN1, BIN2, l);   // left motor is on B01/B02
  side(PWMA, AIN1, AIN2, r);
  outL = l; outR = r;
}

// ---------- Sonar ----------
int pingCm() {
  if (digitalRead(ECHO) == HIGH) return -1;   // sensor still busy from last time: skip this round
  digitalWrite(TRIG, LOW);  delayMicroseconds(4);
  digitalWrite(TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  uint32_t us = pulseIn(ECHO, HIGH, ECHO_TIMEOUT_US);
  if (us == 0) return -1;
  int cm = us / 58;                            // sound: 29 us per cm, there and back
  return cm >= SONAR_MAX_CM ? -1 : cm;
}

void sonarTask(void *) {
  int ring[3] = {-1, -1, -1};
  int i = 0;
  for (;;) {
    ring[i] = pingCm();
    i = (i + 1) % 3;
    // median of the last 3 (a miss counts as far) kills the single-sample spikes the HC-SR04 is known for
    int a = ring[0] < 0 ? SONAR_MAX_CM : ring[0];
    int b = ring[1] < 0 ? SONAR_MAX_CM : ring[1];
    int c = ring[2] < 0 ? SONAR_MAX_CM : ring[2];
    int m = max(min(a, b), min(max(a, b), c));
    rangeCm = m >= SONAR_MAX_CM ? -1 : m;
    vTaskDelay(pdMS_TO_TICKS(SONAR_PERIOD_MS));
  }
}

// ---------- Drive (owns the motors) ----------
void driveTask(void *) {
  uint32_t stateUntil = 0, lastAvoidMs = 0;
  int turnDir = 1;          // +1 spin right, -1 spin left; flips every avoid so corners get escaped
  int stuck = 0;            // grows when avoids come back to back
  TickType_t wake = xTaskGetTickCount();

  for (;;) {
    uint32_t now = millis();
    int d = rangeCm;
    int l = 0, r = 0;
    bool near = d >= 0 && d < AVOID_CM;

    if (autoWanted && now - lastSeenMs > AUTO_LINK_MS) {
      autoWanted = false;   // phone gone: do not keep roaming
      Serial.println("Auto off: link lost");
    }

    if (autoWanted) {
      if (autoState == 0) { autoState = 1; stuck = 0; }
      switch (autoState) {
        case 1:   // cruise
          l = r = AUTO_SPEED;
          if (near) {
            stuck = (now - lastAvoidMs < STUCK_WINDOW_MS) ? min(stuck + 1, 3) : 0;
            lastAvoidMs = now;
            turnDir = -turnDir;
            autoState = 2; stateUntil = now + BACK_MS;
          }
          break;
        case 2:   // back up
          l = r = -AUTO_SPEED;
          if (now >= stateUntil) { autoState = 3; stateUntil = now + TURN_MS + 250 * stuck; }
          break;
        case 3:   // spin
          l = turnDir * TURN_SPEED; r = -turnDir * TURN_SPEED;
          if (now >= stateUntil) {
            if (d >= 0 && d < CLEAR_CM) stateUntil = now + 300;   // still something there: keep turning
            else autoState = 1;
          }
          break;
      }
      blocked = false;
    } else {
      autoState = 0;
      bool linkOk = now - lastCmdMs < FAILSAFE_MS;
      l = linkOk ? wantL : 0;
      r = linkOk ? wantR : 0;
      // Bumper guard: no driving forward into something, but reverse and spinning still work so you can get out
      bool forward = l > 0 && r > 0;
      blocked = forward && d >= 0 && d < GUARD_CM;
      if (blocked) l = r = 0;
    }

    setMotors(l, r);
    vTaskDelayUntil(&wake, pdMS_TO_TICKS(20));
  }
}

// ---------- Input from the phone ----------
void drive(char c) {   // web page buttons
  int p = speedPct, turn = speedPct * 7 / 10;   // spin a bit slower than straight: easier to aim
  switch (c) {
    case 'F': wantL = p;     wantR = p;     break;
    case 'B': wantL = -p;    wantR = -p;    break;
    case 'L': wantL = -turn; wantR = turn;  break;
    case 'R': wantL = turn;  wantR = -turn; break;
    default:  wantL = 0;     wantR = 0;
  }
  lastCmdMs = lastSeenMs = millis();
}

int phoneRssi() {   // signal of the first connected phone, 0 if none
  wifi_sta_list_t list;
  return esp_wifi_ap_get_sta_list(&list) == ESP_OK && list.num ? list.sta[0].rssi : 0;
}

void handleUdp() {
  char buf[48];
  while (udp.parsePacket() > 0) {
    int n = udp.read(buf, sizeof(buf) - 1);
    if (n <= 0) continue;
    buf[n] = 0;
    unsigned long seq;
    int l, r, on;
    if (sscanf(buf, "D %lu %d %d", &seq, &l, &r) == 3) {
      lastCmdMs = lastSeenMs = millis();
      if (!autoWanted) { wantL = constrain(l, -100, 100); wantR = constrain(r, -100, 100); }
      char ack[64];
      int m = snprintf(ack, sizeof(ack), "A %lu %d %d %d %d %d %d", seq, phoneRssi(), (int)rangeCm,
                       (int)autoState, blocked ? 1 : 0, (int)outL, (int)outR);
      udp.beginPacket(udp.remoteIP(), udp.remotePort());
      udp.write((const uint8_t *)ack, m);
      udp.endPacket();
    } else if (sscanf(buf, "M %d", &on) == 1) {
      lastSeenMs = millis();
      autoWanted = on != 0;
      wantL = wantR = 0;
    } else if (sscanf(buf, "L %d", &on) == 1) {
      digitalWrite(LED, on ? HIGH : LOW);
    }
  }
}

void handleSerial() {   // 'o' = one line of state, 'a'/'m' = auto on/off from the USB cable
  while (Serial.available()) {
    char c = Serial.read();
    if (c == 'o') Serial.printf("range %d cm  auto %d  blocked %d  want %d/%d  out %d/%d  rssi %d\n",
                                (int)rangeCm, (int)autoState, blocked, (int)wantL, (int)wantR, (int)outL, (int)outR, phoneRssi());
    if (c == 'a') { autoWanted = true; lastSeenMs = millis(); }
    if (c == 'm') autoWanted = false;
  }
  // keep serial auto alive while the cable is the only link
  static uint32_t t = 0;
  if (autoWanted && !phoneRssi() && millis() - t > 500) { t = millis(); lastSeenMs = millis(); }
}

// ---------- Web page ----------
static const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>RC Car</title>
<style>
*{box-sizing:border-box;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh}
#s{font-size:15px;opacity:.7;margin:8px}
#r{font-family:ui-monospace,monospace;font-size:22px;margin:4px;letter-spacing:1px}
#r.near{color:#e8a33a}
.pad{display:grid;grid-template-columns:repeat(3,96px);grid-template-rows:repeat(3,96px);gap:12px;margin:10px}
button{border:0;border-radius:18px;background:#2a2a2a;color:#eee;font-size:34px;touch-action:none}
button.on{background:#e8a33a;color:#111}
#light,#auto{font-size:16px}
#light{grid-column:3;grid-row:3}
#auto{grid-column:1;grid-row:3}
label{display:flex;gap:12px;align-items:center;font-size:15px;margin:14px}
input[type=range]{width:200px;accent-color:#e8a33a}
</style></head><body>
<div id="s">connecting...</div>
<div id="r">range -- cm</div>
<div class="pad">
 <button data-d="F" style="grid-column:2;grid-row:1">&#9650;</button>
 <button data-d="L" style="grid-column:1;grid-row:2">&#9664;</button>
 <button data-d="S" style="grid-column:2;grid-row:2;font-size:18px">STOP</button>
 <button data-d="R" style="grid-column:3;grid-row:2">&#9654;</button>
 <button data-d="B" style="grid-column:2;grid-row:3">&#9660;</button>
 <button id="auto">AUTO</button>
 <button id="light">LIGHT</button>
</div>
<label>Speed <input id="sp" type="range" min="30" max="100" step="5" value="80"> <span id="spv">80%</span></label>
<script>
const s=document.getElementById('s'),r=document.getElementById('r');
let dir='S',busy=false,auto=0;
const names={0:'',1:'auto: cruising',2:'auto: backing up',3:'auto: turning'};
function send(){if(busy)return;busy=true;
 fetch('/go?d='+dir).then(()=>{s.textContent=dir=='S'?'stopped':'driving '+dir}).catch(()=>s.textContent='car not reachable').finally(()=>busy=false)}
setInterval(()=>{if(dir!='S')send()},150);   // keep-alive while a button is held
function set(d){if(auto)return;dir=d;document.querySelectorAll('[data-d]').forEach(b=>b.classList.toggle('on',b.dataset.d==d&&d!='S'));send()}
document.querySelectorAll('[data-d]').forEach(b=>{
 b.addEventListener('pointerdown',e=>{e.preventDefault();b.setPointerCapture(e.pointerId);set(b.dataset.d)});
 ['pointerup','pointercancel','lostpointercapture'].forEach(t=>b.addEventListener(t,()=>set('S')));
});
const keys={ArrowUp:'F',ArrowDown:'B',ArrowLeft:'L',ArrowRight:'R'};
addEventListener('keydown',e=>{if(keys[e.key]&&dir!=keys[e.key])set(keys[e.key])});
addEventListener('keyup',e=>{if(keys[e.key])set('S')});
addEventListener('blur',()=>set('S'));
const sp=document.getElementById('sp');
sp.oninput=()=>{document.getElementById('spv').textContent=sp.value+'%';fetch('/speed?v='+sp.value)};
let lit=0;document.getElementById('light').onclick=e=>{lit^=1;e.target.classList.toggle('on',!!lit);fetch('/light?on='+lit)};
const ab=document.getElementById('auto');
ab.onclick=()=>{auto^=1;fetch('/auto?on='+auto)};
setInterval(()=>fetch('/state').then(x=>x.json()).then(j=>{   // telemetry + heartbeat for auto mode
 auto=j.a?1:0;ab.classList.toggle('on',!!auto);
 r.textContent='range '+(j.d<0?'--':j.d)+' cm'+(j.g?'  BLOCKED':'');r.classList.toggle('near',j.d>=0&&j.d<30);
 if(auto)s.textContent=names[j.a];else if(j.g)s.textContent='something in front';
}).catch(()=>{}),250);
fetch('/go?d=S').then(()=>s.textContent='ready').catch(()=>s.textContent='car not reachable');
</script></body></html>)HTML";

static esp_err_t pageHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, PAGE, HTTPD_RESP_USE_STRLEN);
}

static bool queryArg(httpd_req_t *req, const char *key, char *out, size_t n) {
  char q[24];
  return httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK && httpd_query_key_value(q, key, out, n) == ESP_OK;
}

static esp_err_t goHandler(httpd_req_t *req) {
  char d[4] = "S";
  queryArg(req, "d", d, sizeof(d));
  if (!autoWanted) drive(d[0]); else lastSeenMs = millis();
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t speedHandler(httpd_req_t *req) {
  char v[6] = "";
  if (queryArg(req, "v", v, sizeof(v))) speedPct = constrain(atoi(v), 30, 100);
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t lightHandler(httpd_req_t *req) {
  char v[4] = "0";
  queryArg(req, "on", v, sizeof(v));
  digitalWrite(LED, v[0] == '1' ? HIGH : LOW);
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t autoHandler(httpd_req_t *req) {
  char v[4] = "0";
  queryArg(req, "on", v, sizeof(v));
  autoWanted = v[0] == '1';
  wantL = wantR = 0;
  lastSeenMs = millis();
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t stateHandler(httpd_req_t *req) {
  lastSeenMs = millis();
  char j[96];
  snprintf(j, sizeof(j), "{\"d\":%d,\"a\":%d,\"g\":%d,\"l\":%d,\"r\":%d}", (int)rangeCm, (int)autoState, blocked ? 1 : 0, (int)outL, (int)outR);
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, j);
}

void startServer() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  httpd_handle_t web = NULL;
  if (httpd_start(&web, &cfg) != ESP_OK) { Serial.println("Web server FAILED"); return; }
  httpd_uri_t page = {"/", HTTP_GET, pageHandler, NULL};
  httpd_uri_t go = {"/go", HTTP_GET, goHandler, NULL};
  httpd_uri_t speed = {"/speed", HTTP_GET, speedHandler, NULL};
  httpd_uri_t light = {"/light", HTTP_GET, lightHandler, NULL};
  httpd_uri_t aut = {"/auto", HTTP_GET, autoHandler, NULL};
  httpd_uri_t state = {"/state", HTTP_GET, stateHandler, NULL};
  httpd_register_uri_handler(web, &page);
  httpd_register_uri_handler(web, &go);
  httpd_register_uri_handler(web, &speed);
  httpd_register_uri_handler(web, &light);
  httpd_register_uri_handler(web, &aut);
  httpd_register_uri_handler(web, &state);
}

void setup() {
  // Motors off first, before anything else can twitch them
  int pins[] = {STBY, AIN1, AIN2, BIN1, BIN2, LED, TRIG};
  for (int p : pins) { pinMode(p, OUTPUT); digitalWrite(p, LOW); }
  pinMode(ECHO, INPUT);
  ledcAttach(PWMA, PWM_HZ, PWM_BITS); ledcWrite(PWMA, 0);
  ledcAttach(PWMB, PWM_HZ, PWM_BITS); ledcWrite(PWMB, 0);
  digitalWrite(STBY, HIGH);   // driver on, all outputs still at 0

  Serial.begin(115200);
  Serial.println("\nRC Car starting (sonar + auto-pilot build)");

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  WiFi.setSleep(false);
  Serial.printf("Wi-Fi \"%s\" up. Open http://%s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  startServer();
  udp.begin(UDP_PORT);

  xTaskCreatePinnedToCore(sonarTask, "sonar", 2048, NULL, 1, NULL, 1);
  xTaskCreatePinnedToCore(driveTask, "drive", 4096, NULL, 2, NULL, 1);
  Serial.println("Serial: o = state, a = auto on, m = auto off");
}

void loop() {
  handleUdp();
  handleSerial();
  static uint32_t lastLog = 0;
  static uint8_t lastState = 255;
  if (autoState != lastState || millis() - lastLog > 2000) {
    lastState = autoState; lastLog = millis();
    Serial.printf("range %3d cm  auto %d  blocked %d  out %4d/%4d\n", (int)rangeCm, (int)autoState, blocked, (int)outL, (int)outR);
  }
  delay(2);
}

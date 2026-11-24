// RC Car (basic, no sonar) - ESP32 DevKit + TB6612FNG (no camera)
// Phone joins Wi-Fi "RC-Car" (password drive1234), opens http://192.168.4.1
// Drive: /go?d=F|B|L|R|S   Speed: /speed?v=30..100   Light (blue LED on the DevKit): /light?on=0|1
// RC Pilot app: UDP port 4210. "D <seq> <left> <right>" (each -100..100) -> reply "A <seq> <rssi>"; "L 0|1" = light
// Board: "ESP32 Dev Module"

#include <WiFi.h>
#include <WiFiUdp.h>
#include "esp_http_server.h"
#include "esp_wifi.h"

// ---- Wi-Fi ----
const char *AP_SSID = "RC-Car";
const char *AP_PASS = "drive1234";   // at least 8 characters

// ---- Motor pins (TB6612) ----
// The left motor is wired to B01/B02, so "A" here (left) uses the B pins and "B" (right) uses the A pins.
const int PWMA = 18, AIN1 = 32, AIN2 = 33;   // left side  (B01/B02)
const int PWMB = 13, BIN1 = 27, BIN2 = 19;   // right side (A01/A02)
const int STBY = 4;                          // LOW = driver off, so the motors stay still while the board boots
const int LED = 2;                           // blue LED on the DevKit

const uint32_t PWM_HZ = 20000;   // above hearing, no motor whine
const uint8_t PWM_BITS = 8;      // duty 0..255

// Stop if the phone has not sent a command for this long (phone gone, Wi-Fi dropped)
const uint32_t FAILSAFE_MS = 400;

const uint16_t UDP_PORT = 4210;
WiFiUDP udp;

volatile uint32_t lastCmdMs = 0;
volatile char currentDir = 'S';
volatile int speedPct = 80;      // TT motors stall below ~30%

// ---------- Motors ----------
void side(int pwm, int in1, int in2, int dir, int pct) {   // dir: 1 forward, -1 back, 0 stop
  digitalWrite(in1, dir > 0 ? HIGH : LOW);
  digitalWrite(in2, dir < 0 ? HIGH : LOW);
  ledcWrite(pwm, dir ? pct * 255 / 100 : 0);
}

void drive(char d) {
  int p = speedPct, turn = speedPct * 7 / 10;   // spin a bit slower than straight: easier to aim
  switch (d) {
    case 'F': side(PWMA, AIN1, AIN2, 1, p);     side(PWMB, BIN1, BIN2, 1, p);     break;
    case 'B': side(PWMA, AIN1, AIN2, -1, p);    side(PWMB, BIN1, BIN2, -1, p);    break;
    case 'L': side(PWMA, AIN1, AIN2, -1, turn); side(PWMB, BIN1, BIN2, 1, turn);  break;  // spin left
    case 'R': side(PWMA, AIN1, AIN2, 1, turn);  side(PWMB, BIN1, BIN2, -1, turn); break;  // spin right
    default:  d = 'S'; side(PWMA, AIN1, AIN2, 0, 0); side(PWMB, BIN1, BIN2, 0, 0);
  }
  currentDir = d;
}

// Analog drive from the app: each side -100..100, sign = direction
void driveLR(int l, int r) {
  l = constrain(l, -100, 100);
  r = constrain(r, -100, 100);
  side(PWMA, AIN1, AIN2, (l > 0) - (l < 0), abs(l));
  side(PWMB, BIN1, BIN2, (r > 0) - (r < 0), abs(r));
  currentDir = (l || r) ? 'A' : 'S';
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
      lastCmdMs = millis();
      driveLR(l, r);
      char ack[32];
      int m = snprintf(ack, sizeof(ack), "A %lu %d", seq, phoneRssi());
      udp.beginPacket(udp.remoteIP(), udp.remotePort());
      udp.write((const uint8_t *)ack, m);
      udp.endPacket();
    } else if (sscanf(buf, "L %d", &on) == 1) {
      digitalWrite(LED, on ? HIGH : LOW);
    }
  }
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
.pad{display:grid;grid-template-columns:repeat(3,96px);grid-template-rows:repeat(3,96px);gap:12px;margin:10px}
button{border:0;border-radius:18px;background:#2a2a2a;color:#eee;font-size:34px;touch-action:none}
button.on{background:#e8a33a;color:#111}
#light{grid-column:3;grid-row:3;font-size:16px}
label{display:flex;gap:12px;align-items:center;font-size:15px;margin:14px}
input[type=range]{width:200px;accent-color:#e8a33a}
</style></head><body>
<div id="s">connecting...</div>
<div class="pad">
 <button data-d="F" style="grid-column:2;grid-row:1">&#9650;</button>
 <button data-d="L" style="grid-column:1;grid-row:2">&#9664;</button>
 <button data-d="S" style="grid-column:2;grid-row:2;font-size:18px">STOP</button>
 <button data-d="R" style="grid-column:3;grid-row:2">&#9654;</button>
 <button data-d="B" style="grid-column:2;grid-row:3">&#9660;</button>
 <button id="light">LIGHT</button>
</div>
<label>Speed <input id="sp" type="range" min="30" max="100" step="5" value="80"> <span id="spv">80%</span></label>
<script>
const s=document.getElementById('s');
let dir='S',busy=false;
function send(){if(busy)return;busy=true;
 fetch('/go?d='+dir).then(()=>{s.textContent=dir=='S'?'stopped':'driving '+dir}).catch(()=>s.textContent='car not reachable').finally(()=>busy=false)}
setInterval(()=>{if(dir!='S')send()},150);   // keep-alive while a button is held
function set(d){dir=d;document.querySelectorAll('[data-d]').forEach(b=>b.classList.toggle('on',b.dataset.d==d&&d!='S'));send()}
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
fetch('/go?d=S').then(()=>s.textContent='ready').catch(()=>s.textContent='car not reachable');
</script></body></html>)HTML";

static esp_err_t pageHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, PAGE, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t goHandler(httpd_req_t *req) {
  char q[16], d[4] = "S";
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK)
    httpd_query_key_value(q, "d", d, sizeof(d));
  lastCmdMs = millis();
  drive(d[0]);
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t speedHandler(httpd_req_t *req) {
  char q[16], v[6] = "";
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK &&
      httpd_query_key_value(q, "v", v, sizeof(v)) == ESP_OK)
    speedPct = constrain(atoi(v), 30, 100);
  if (currentDir != 'S') drive(currentDir);   // apply now while driving
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t lightHandler(httpd_req_t *req) {
  char q[16], v[4] = "0";
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK)
    httpd_query_key_value(q, "on", v, sizeof(v));
  digitalWrite(LED, v[0] == '1' ? HIGH : LOW);
  return httpd_resp_sendstr(req, "ok");
}

void startServer() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  httpd_handle_t web = NULL;
  if (httpd_start(&web, &cfg) != ESP_OK) { Serial.println("Web server FAILED"); return; }
  httpd_uri_t page = {"/", HTTP_GET, pageHandler, NULL};
  httpd_uri_t go = {"/go", HTTP_GET, goHandler, NULL};
  httpd_uri_t speed = {"/speed", HTTP_GET, speedHandler, NULL};
  httpd_uri_t light = {"/light", HTTP_GET, lightHandler, NULL};
  httpd_register_uri_handler(web, &page);
  httpd_register_uri_handler(web, &go);
  httpd_register_uri_handler(web, &speed);
  httpd_register_uri_handler(web, &light);
}

void setup() {
  // Motors off first, before anything else can twitch them
  int pins[] = {STBY, AIN1, AIN2, BIN1, BIN2, LED};
  for (int p : pins) { pinMode(p, OUTPUT); digitalWrite(p, LOW); }
  ledcAttach(PWMA, PWM_HZ, PWM_BITS); ledcWrite(PWMA, 0);
  ledcAttach(PWMB, PWM_HZ, PWM_BITS); ledcWrite(PWMB, 0);
  digitalWrite(STBY, HIGH);   // driver on, all outputs still at 0

  Serial.begin(115200);
  Serial.println("\nRC Car starting");

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  WiFi.setSleep(false);
  Serial.printf("Wi-Fi \"%s\" up. Open http://%s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  startServer();
  udp.begin(UDP_PORT);
}

void loop() {
  if (currentDir != 'S' && millis() - lastCmdMs > FAILSAFE_MS) {
    drive('S');
    Serial.println("Failsafe stop");
  }
  handleUdp();
  delay(2);
}

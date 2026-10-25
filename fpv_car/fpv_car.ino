// FPV Car - ESP32-CAM (AI Thinker) + TB6612FNG
// Phone joins Wi-Fi "FPV-Car" (password drive1234), opens http://192.168.4.1
// Video: port 81 /stream   Drive: port 80 /go?d=F|B|L|R|S   Light: /light?on=0|1
// Board: "AI Thinker ESP32-CAM". Do not put an SD card in: IO12-15 are used for the motors.

#include <WiFi.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "img_converters.h"

struct Jpeg { uint8_t *buf; size_t len; bool owned; };

// ---- Wi-Fi ----
const char *AP_SSID = "FPV-Car";
const char *AP_PASS = "drive1234";   // at least 8 characters

// ---- Motor pins (guide section 4) ----
const int AIN1 = 14, AIN2 = 15;   // left side
const int BIN1 = 13, BIN2 = 12;   // right side
const int FLASH_LED = 4;

// Stop if the phone has not sent a command for this long (phone gone, Wi-Fi dropped)
const uint32_t FAILSAFE_MS = 400;

// ---- AI Thinker camera pins ----
#define PWDN_GPIO_NUM 32
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 0
#define SIOD_GPIO_NUM 26
#define SIOC_GPIO_NUM 27
#define Y9_GPIO_NUM 35
#define Y8_GPIO_NUM 34
#define Y7_GPIO_NUM 39
#define Y6_GPIO_NUM 36
#define Y5_GPIO_NUM 21
#define Y4_GPIO_NUM 19
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 5
#define VSYNC_GPIO_NUM 25
#define HREF_GPIO_NUM 23
#define PCLK_GPIO_NUM 22

volatile uint32_t lastCmdMs = 0;
volatile char currentDir = 'S';
bool cameraOk = false;

// ---------- Motors ----------
void side(int in1, int in2, int dir) {   // dir: 1 forward, -1 back, 0 stop
  digitalWrite(in1, dir > 0 ? HIGH : LOW);
  digitalWrite(in2, dir < 0 ? HIGH : LOW);
}

void drive(char d) {
  switch (d) {
    case 'F': side(AIN1, AIN2, 1);  side(BIN1, BIN2, 1);  break;
    case 'B': side(AIN1, AIN2, -1); side(BIN1, BIN2, -1); break;
    case 'L': side(AIN1, AIN2, -1); side(BIN1, BIN2, 1);  break;  // spin left
    case 'R': side(AIN1, AIN2, 1);  side(BIN1, BIN2, -1); break;  // spin right
    default:  d = 'S'; side(AIN1, AIN2, 0); side(BIN1, BIN2, 0);
  }
  currentDir = d;
}

// ---------- Web page ----------
static const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>FPV Car</title>
<style>
*{box-sizing:border-box;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
body{margin:0;background:#111;color:#eee;font-family:system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;min-height:100vh}
#v{width:100%;max-width:640px;background:#000;aspect-ratio:4/3;object-fit:contain}
#s{font-size:14px;opacity:.7;margin:6px}
.pad{display:grid;grid-template-columns:repeat(3,90px);grid-template-rows:repeat(3,90px);gap:10px;margin:10px}
button{border:0;border-radius:16px;background:#2a2a2a;color:#eee;font-size:32px;touch-action:none}
button.on{background:#e8a33a;color:#111}
#light{grid-column:3;grid-row:3;font-size:16px}
</style></head><body>
<img id="v" alt="camera">
<div id="s">connecting...</div>
<div class="pad">
 <button data-d="F" style="grid-column:2;grid-row:1">&#9650;</button>
 <button data-d="L" style="grid-column:1;grid-row:2">&#9664;</button>
 <button data-d="S" style="grid-column:2;grid-row:2;font-size:18px">STOP</button>
 <button data-d="R" style="grid-column:3;grid-row:2">&#9654;</button>
 <button data-d="B" style="grid-column:2;grid-row:3">&#9660;</button>
 <button id="light">LIGHT</button>
</div>
<script>
const v=document.getElementById('v'),s=document.getElementById('s');
v.src='http://'+location.hostname+':81/stream';
v.onerror=()=>{s.textContent='no video, retrying';setTimeout(()=>v.src=v.src.split('?')[0]+'?'+Date.now(),1000)};
v.onload=()=>s.textContent='ok';
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
let lit=0;document.getElementById('light').onclick=e=>{lit^=1;e.target.classList.toggle('on',!!lit);fetch('/light?on='+lit)};
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

static esp_err_t lightHandler(httpd_req_t *req) {
  char q[16], v[4] = "0";
  if (httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK)
    httpd_query_key_value(q, "on", v, sizeof(v));
  digitalWrite(FLASH_LED, v[0] == '1' ? HIGH : LOW);
  return httpd_resp_sendstr(req, "ok");
}

// ---------- MJPEG stream ----------
// GC2145 sensors have no JPEG hardware: compress raw frames in software
static bool toJpeg(camera_fb_t *fb, Jpeg &j) {
  if (fb->format == PIXFORMAT_JPEG) { j = {fb->buf, fb->len, false}; return true; }
  j.owned = true;
  return frame2jpg(fb, 70, &j.buf, &j.len);
}

#define BOUNDARY "frame"
static esp_err_t streamHandler(httpd_req_t *req) {
  if (!cameraOk) {
    httpd_resp_send_err(req, HTTPD_500_INTERNAL_SERVER_ERROR, "camera not found");
    return ESP_FAIL;
  }
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=" BOUNDARY);
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  char head[64];
  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) { delay(10); continue; }
    Jpeg j;
    bool ok = toJpeg(fb, j);
    esp_camera_fb_return(fb);
    if (!ok) continue;
    int n = snprintf(head, sizeof(head), "\r\n--" BOUNDARY "\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", j.len);
    esp_err_t r = httpd_resp_send_chunk(req, head, n);
    if (r == ESP_OK) r = httpd_resp_send_chunk(req, (const char *)j.buf, j.len);
    if (j.owned) free(j.buf);
    if (r != ESP_OK) break;   // phone closed the page
  }
  return ESP_OK;
}

void startServers() {
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.server_port = 80;
  httpd_handle_t web = NULL;
  if (httpd_start(&web, &cfg) == ESP_OK) {
    httpd_uri_t page = {"/", HTTP_GET, pageHandler, NULL};
    httpd_uri_t go = {"/go", HTTP_GET, goHandler, NULL};
    httpd_uri_t light = {"/light", HTTP_GET, lightHandler, NULL};
    httpd_register_uri_handler(web, &page);
    httpd_register_uri_handler(web, &go);
    httpd_register_uri_handler(web, &light);
  }
  // The stream blocks its task, so it gets its own server
  cfg.server_port = 81;
  cfg.ctrl_port += 1;
  httpd_handle_t cam = NULL;
  if (httpd_start(&cam, &cfg) == ESP_OK) {
    httpd_uri_t stream = {"/stream", HTTP_GET, streamHandler, NULL};
    httpd_register_uri_handler(cam, &stream);
  }
}

bool startCamera() {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM; c.pin_d1 = Y3_GPIO_NUM; c.pin_d2 = Y4_GPIO_NUM; c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM; c.pin_d5 = Y7_GPIO_NUM; c.pin_d6 = Y8_GPIO_NUM; c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM;
  c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_QVGA;   // 320x240: small but low lag. Try FRAMESIZE_VGA for sharper video
  c.jpeg_quality = 12;             // 10-63, lower = better picture, bigger frames
  c.grab_mode = CAMERA_GRAB_LATEST;
  if (psramFound()) {
    c.fb_count = 2;
    c.fb_location = CAMERA_FB_IN_PSRAM;
  } else {
    c.fb_count = 1;
    c.fb_location = CAMERA_FB_IN_DRAM;
  }
  esp_err_t err = esp_camera_init(&c);
  if (err == ESP_ERR_NOT_SUPPORTED) {   // GC2145 board: no JPEG, grab raw and compress in software
    Serial.println("Sensor has no JPEG, using RGB565");
    c.pixel_format = PIXFORMAT_RGB565;
    c.fb_count = 1;
    err = esp_camera_init(&c);
  }
  if (err != ESP_OK) {
    Serial.printf("Camera error 0x%x: check the ribbon cable latch\n", err);
    return false;
  }
  return true;
}

void setup() {
  // Motors off first, before anything else can twitch them
  int pins[] = {AIN1, AIN2, BIN1, BIN2, FLASH_LED};
  for (int p : pins) { pinMode(p, OUTPUT); digitalWrite(p, LOW); }

  Serial.begin(115200);
  Serial.println("\nFPV Car starting");

  cameraOk = startCamera();
  Serial.println(cameraOk ? "Camera ok" : "Camera FAILED (drive still works)");

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASS);
  WiFi.setSleep(false);
  Serial.printf("Wi-Fi \"%s\" up. Open http://%s\n", AP_SSID, WiFi.softAPIP().toString().c_str());

  startServers();
}

void loop() {
  if (currentDir != 'S' && millis() - lastCmdMs > FAILSAFE_MS) {
    drive('S');
    Serial.println("Failsafe stop");
  }
  delay(20);
}

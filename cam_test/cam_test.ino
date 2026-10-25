// ESP32-CAM bench test (AI Thinker). No motors, nothing else wired.
// 1. Open Serial Monitor at 115200 and press RESET: it prints a check list.
// 2. Join Wi-Fi "CAM-Test" (password test1234), open http://192.168.4.1
// Board: "AI Thinker ESP32-CAM".

#include <WiFi.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "img_converters.h"

struct Jpeg { uint8_t *buf; size_t len; bool owned; };
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

const char *AP_SSID = "CAM-Test";
const char *AP_PASS = "test1234";
// Your home Wi-Fi: the CAM also joins it, so a laptop on the same Wi-Fi can watch. Leave "" to skip.
const char *HOME_SSID = "";
const char *HOME_PASS = "";
const int FLASH_LED = 4;

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

static const char PAGE[] = R"HTML(<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><title>CAM Test</title>
<style>body{margin:0;background:#111;color:#eee;font:16px sans-serif;text-align:center}
img{width:100%;max-width:800px;display:block;margin:0 auto;background:#222}
a,button{display:inline-block;margin:8px;padding:10px 16px;background:#333;color:#eee;border:0;border-radius:6px;text-decoration:none}</style>
</head><body><h3>ESP32-CAM test</h3><img id="v">
<div><button onclick="v.src=location.protocol+'//'+location.hostname+':81/stream'">Start stream</button>
<a href="/capture" target="_blank">Single photo</a>
<button onclick="fetch('/light?on=1')">Flash on</button><button onclick="fetch('/light?on=0')">Flash off</button></div>
<p id="s"></p><script>fetch('/status').then(r=>r.text()).then(t=>s.textContent=t)</script>
</body></html>)HTML";

void pass(const char *what, bool ok) {
  Serial.printf("  [%s] %s\n", ok ? " OK " : "FAIL", what);
}

esp_err_t pageHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "text/html");
  return httpd_resp_send(req, PAGE, HTTPD_RESP_USE_STRLEN);
}

esp_err_t statusHandler(httpd_req_t *req) {
  char buf[160];
  sensor_t *s = esp_camera_sensor_get();
  snprintf(buf, sizeof(buf), "sensor PID 0x%02X | PSRAM %u KB free | heap %u KB free | uptime %lus",
           s ? s->id.PID : 0, ESP.getFreePsram() / 1024, ESP.getFreeHeap() / 1024, millis() / 1000);
  httpd_resp_set_type(req, "text/plain");
  return httpd_resp_send(req, buf, HTTPD_RESP_USE_STRLEN);
}

// GC2145 sensors have no JPEG hardware: compress raw frames in software
static bool toJpeg(camera_fb_t *fb, Jpeg &j) {
  if (fb->format == PIXFORMAT_JPEG) { j = {fb->buf, fb->len, false}; return true; }
  j.owned = true;
  return frame2jpg(fb, 70, &j.buf, &j.len);
}

esp_err_t captureHandler(httpd_req_t *req) {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) { httpd_resp_send_500(req); return ESP_FAIL; }
  Jpeg j;
  bool ok = toJpeg(fb, j);
  esp_camera_fb_return(fb);
  if (!ok) { httpd_resp_send_500(req); return ESP_FAIL; }
  httpd_resp_set_type(req, "image/jpeg");
  esp_err_t r = httpd_resp_send(req, (const char *)j.buf, j.len);
  if (j.owned) free(j.buf);
  return r;
}

esp_err_t lightHandler(httpd_req_t *req) {
  char q[16], v[4];
  bool on = httpd_req_get_url_query_str(req, q, sizeof(q)) == ESP_OK &&
            httpd_query_key_value(q, "on", v, sizeof(v)) == ESP_OK && v[0] == '1';
  digitalWrite(FLASH_LED, on ? HIGH : LOW);
  return httpd_resp_send(req, "ok", 2);
}

#define BOUNDARY "frame"
esp_err_t streamHandler(httpd_req_t *req) {
  httpd_resp_set_type(req, "multipart/x-mixed-replace;boundary=" BOUNDARY);
  char hdr[64];
  uint32_t frames = 0, t0 = millis();
  while (true) {
    camera_fb_t *fb = esp_camera_fb_get();
    if (!fb) return ESP_FAIL;
    Jpeg j;
    bool ok = toJpeg(fb, j);
    esp_camera_fb_return(fb);
    if (!ok) continue;
    int n = snprintf(hdr, sizeof(hdr), "\r\n--" BOUNDARY "\r\nContent-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n", j.len);
    esp_err_t r = httpd_resp_send_chunk(req, hdr, n);
    if (r == ESP_OK) r = httpd_resp_send_chunk(req, (const char *)j.buf, j.len);
    if (j.owned) free(j.buf);
    if (r != ESP_OK) return r;   // browser closed
    if (++frames % 50 == 0) {
      Serial.printf("stream: %.1f fps\n", 50000.0 / (millis() - t0));
      t0 = millis();
    }
  }
}

void setup() {
  // Weak 5V (powered from the DevKit): do not reset on short voltage dips
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);
  Serial.begin(115200);
  delay(500);
  pinMode(FLASH_LED, OUTPUT);
  digitalWrite(FLASH_LED, LOW);
  Serial.println("\n=== ESP32-CAM test ===");

  pass("PSRAM found", psramFound());
  Serial.printf("  chip %s rev %d, flash %u MB, PSRAM %u KB\n", ESP.getChipModel(), ESP.getChipRevision(),
                ESP.getFlashChipSize() / (1024 * 1024), ESP.getPsramSize() / 1024);

  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = Y2_GPIO_NUM; c.pin_d1 = Y3_GPIO_NUM; c.pin_d2 = Y4_GPIO_NUM; c.pin_d3 = Y5_GPIO_NUM;
  c.pin_d4 = Y6_GPIO_NUM; c.pin_d5 = Y7_GPIO_NUM; c.pin_d6 = Y8_GPIO_NUM; c.pin_d7 = Y9_GPIO_NUM;
  c.pin_xclk = XCLK_GPIO_NUM; c.pin_pclk = PCLK_GPIO_NUM; c.pin_vsync = VSYNC_GPIO_NUM; c.pin_href = HREF_GPIO_NUM;
  c.pin_sccb_sda = SIOD_GPIO_NUM; c.pin_sccb_scl = SIOC_GPIO_NUM;
  c.pin_pwdn = PWDN_GPIO_NUM; c.pin_reset = RESET_GPIO_NUM;
  c.xclk_freq_hz = 10000000;   // lower clock, less current
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = psramFound() ? FRAMESIZE_VGA : FRAMESIZE_QVGA;
  c.jpeg_quality = 12;
  c.fb_count = psramFound() ? 2 : 1;
  c.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;

  esp_err_t err = esp_camera_init(&c);
  if (err == ESP_ERR_NOT_SUPPORTED) {   // sensor found but has no JPEG: retry raw to learn which one
    Serial.println("  sensor has no JPEG, retrying RGB565 QVGA to read its ID");
    c.pixel_format = PIXFORMAT_RGB565; c.frame_size = FRAMESIZE_QVGA; c.fb_count = 1;
    err = esp_camera_init(&c);
  }
  pass("camera init", err == ESP_OK);
  if (err != ESP_OK) {
    Serial.printf("  error 0x%x. Check the ribbon cable: lift the black latch, push the cable fully in, close it.\n", err);
  } else {
    sensor_t *s = esp_camera_sensor_get();
    camera_sensor_info_t *info = esp_camera_sensor_get_info(&s->id);
    Serial.printf("  sensor PID 0x%02X = %s (0x26 = OV2640, 0x36 = OV3660)\n", s->id.PID, info ? info->name : "unknown");
    camera_fb_t *fb = esp_camera_fb_get();
    pass("first frame", fb != nullptr);
    if (fb) { Serial.printf("  %ux%u, %u bytes\n", fb->width, fb->height, fb->len); esp_camera_fb_return(fb); }
  }

  digitalWrite(FLASH_LED, HIGH); delay(150); digitalWrite(FLASH_LED, LOW);
  Serial.println("  flash LED blinked once (look at the board)");

  delay(500);                       // let the 5V recover after camera start
  WiFi.mode(HOME_SSID[0] ? WIFI_AP_STA : WIFI_AP);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);   // low Wi-Fi power: smaller current spikes, range ~10 m
  pass("Wi-Fi access point", WiFi.softAP(AP_SSID, AP_PASS));
  Serial.printf("  join \"%s\" / %s, open http://%s\n", AP_SSID, AP_PASS, WiFi.softAPIP().toString().c_str());
  if (HOME_SSID[0]) {
    WiFi.begin(HOME_SSID, HOME_PASS);
    for (int i = 0; i < 30 && WiFi.status() != WL_CONNECTED; i++) delay(500);
    pass("joined home Wi-Fi", WiFi.status() == WL_CONNECTED);
    if (WiFi.status() == WL_CONNECTED) Serial.printf("  or on home Wi-Fi open http://%s\n", WiFi.localIP().toString().c_str());
  }

  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  httpd_handle_t web = nullptr, stream = nullptr;
  if (httpd_start(&web, &cfg) == ESP_OK) {
    httpd_uri_t u[] = {{"/", HTTP_GET, pageHandler, nullptr}, {"/status", HTTP_GET, statusHandler, nullptr},
                       {"/capture", HTTP_GET, captureHandler, nullptr}, {"/light", HTTP_GET, lightHandler, nullptr}};
    for (auto &x : u) httpd_register_uri_handler(web, &x);
  }
  cfg.server_port = 81;
  cfg.ctrl_port += 1;
  if (httpd_start(&stream, &cfg) == ESP_OK) {
    httpd_uri_t s = {"/stream", HTTP_GET, streamHandler, nullptr};
    httpd_register_uri_handler(stream, &s);
  }
  Serial.println("=== ready ===");
}

void loop() { delay(1000); }

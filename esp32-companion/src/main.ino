#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>
#include <BluetoothSerial.h>
#include "config.h"

// Display functions (from display.ino)
extern void displayInit();
extern void displayShowMessage(const char* title, const char* message);
extern void displayShowStatus(const char* ip, bool connected);
extern void displayShowImage(const uint8_t* buf, uint32_t bufSize);
extern void displayShowReceiving();
extern void displayShowDone();

// Global objects
WebServer server(HTTP_PORT);
BluetoothSerial SerialBT;

// Image buffer
uint8_t* imageBuffer = nullptr;
uint32_t imageSize = 0;
bool imageReady = false;

// WiFi reconnect
unsigned long lastWifiCheck = 0;
const unsigned long WIFI_CHECK_INTERVAL = 30000;

void setup() {
    Serial.begin(115200);
    Serial.println("\n=== OmniChat CYD Companion ===");
    
    // Initialize display
    displayInit();
    displayShowMessage("OmniChat CYD", "Starting...");
    
    // Initialize Bluetooth
    SerialBT.begin(DEVICE_NAME);
    Serial.println("Bluetooth started: " + String(DEVICE_NAME));
    
    // Connect to WiFi
    connectWiFi();
    
    // Setup HTTP server
    setupServer();
    
    // Allocate image buffer
    imageBuffer = (uint8_t*)malloc(JPEG_BUF_SIZE);
    if (imageBuffer == NULL) {
        Serial.println("Failed to allocate image buffer!");
        displayShowMessage("ERROR", "Memory allocation failed");
    }
    
    // Show status
    displayShowStatus(WiFi.localIP().toString().c_str(), WiFi.isConnected());
    
    Serial.println("Setup complete!");
}

void loop() {
    server.handleClient();
    
    // Handle Bluetooth data
    handleBluetooth();
    
    // WiFi reconnect check
    if (millis() - lastWifiCheck > WIFI_CHECK_INTERVAL) {
        lastWifiCheck = millis();
        if (WiFi.status() != WL_CONNECTED) {
            Serial.println("WiFi disconnected, reconnecting...");
            connectWiFi();
            displayShowStatus(WiFi.localIP().toString().c_str(), WiFi.isConnected());
        }
    }
    
    delay(1);
}

void connectWiFi() {
    displayShowMessage("WiFi", ("Connecting to " + String(WIFI_SSID)).c_str());
    
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    
    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 20) {
        delay(500);
        Serial.print(".");
        attempts++;
    }
    
    if (WiFi.status() == WL_CONNECTED) {
        Serial.println("\nWiFi connected!");
        Serial.println("IP: " + WiFi.localIP().toString());
        displayShowStatus(WiFi.localIP().toString().c_str(), true);
    } else {
        Serial.println("\nWiFi connection failed!");
        displayShowMessage("WiFi", "Connection failed!");
    }
}

void setupServer() {
    // CORS headers
    server.enableCORS(true);
    
    // GET /status - Device status
    server.on("/status", HTTP_GET, []() {
        StaticJsonDocument<200> doc;
        doc["device"] = DEVICE_NAME;
        doc["ip"] = WiFi.localIP().toString();
        doc["wifi_connected"] = WiFi.isConnected();
        doc["free_heap"] = ESP.getFreeHeap();
        doc["uptime"] = millis() / 1000;
        
        String response;
        serializeJson(doc, response);
        server.send(200, "application/json", response);
    });
    
    // POST /display - Display JPEG image
    server.on("/display", HTTP_POST, []() {
        if (server.hasArg("plain") == false) {
            server.send(400, "application/json", "{\"error\":\"No image data\"}");
            return;
        }
        
        displayShowReceiving();
        
        // Get image data
        String body = server.arg("plain");
        uint32_t len = body.length();
        
        if (len > JPEG_BUF_SIZE) {
            server.send(413, "application/json", "{\"error\":\"Image too large\"}");
            return;
        }
        
        // Copy to buffer
        memcpy(imageBuffer, body.c_str(), len);
        imageSize = len;
        imageReady = true;
        
        // Display image
        displayShowImage(imageBuffer, imageSize);
        
        // Send response
        StaticJsonDocument<100> doc;
        doc["status"] = "ok";
        doc["size"] = len;
        doc["width"] = SCREEN_WIDTH;
        doc["height"] = SCREEN_HEIGHT;
        
        String response;
        serializeJson(doc, response);
        server.send(200, "application/json", response);
        
        displayShowDone();
        delay(500);
        displayShowStatus(WiFi.localIP().toString().c_str(), true);
    });
    
    // POST /text - Display text message
    server.on("/text", HTTP_POST, []() {
        if (server.hasArg("plain") == false) {
            server.send(400, "application/json", "{\"error\":\"No text data\"}");
            return;
        }
        
        String body = server.arg("plain");
        
        // Parse JSON
        StaticJsonDocument<500> doc;
        DeserializationError error = deserializeJson(doc, body);
        
        if (error) {
            server.send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
            return;
        }
        
        const char* text = doc["text"] | "";
        const char* title = doc["title"] | "Message";
        
        // Display text
        displayShowMessage(title, text);
        
        server.send(200, "application/json", "{\"status\":\"ok\"}");
        
        delay(3000);
        displayShowStatus(WiFi.localIP().toString().c_str(), true);
    });
    
    // OPTIONS for CORS
    server.on("/display", HTTP_OPTIONS, []() {
        server.sendHeader("Access-Control-Allow-Origin", "*");
        server.sendHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
        server.send(204);
    });
    
    server.on("/text", HTTP_OPTIONS, []() {
        server.sendHeader("Access-Control-Allow-Origin", "*");
        server.sendHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
        server.send(204);
    });
    
    server.begin();
    Serial.println("HTTP server started on port " + String(HTTP_PORT));
}

void handleBluetooth() {
    if (SerialBT.available()) {
        // Read incoming data
        uint32_t startTime = millis();
        
        // Read header (JSON with metadata)
        String header = "";
        while (SerialBT.available()) {
            char c = SerialBT.read();
            if (c == '\n' && header.endsWith("---BT_MSG_END---")) {
                header.remove(header.length() - 15);
                break;
            }
            header += c;
            if (millis() - startTime > 5000) {
                Serial.println("BT header timeout");
                return;
            }
        }
        
        // Parse header
        StaticJsonDocument<200> doc;
        DeserializationError error = deserializeJson(doc, header);
        
        if (error) {
            Serial.println("BT header parse error: " + String(error.c_str()));
            return;
        }
        
        const char* type = doc["type"] | "text";
        const char* content = doc["content"] | "";
        
        if (String(type) == "image") {
            // Display base64 image (simplified - in real implementation decode base64)
            displayShowMessage("BT Image", "Image received via Bluetooth");
        } else {
            // Display text
            displayShowMessage("BT Message", content);
        }
        
        delay(2000);
        displayShowStatus(WiFi.localIP().toString().c_str(), WiFi.isConnected());
    }
}

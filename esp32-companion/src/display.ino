#include <TFT_eSPI.h>
#include <TJpg_Decoder.h>
#include "config.h"

extern TFT_eSPI tft;

// JPEG buffer
static uint8_t *jpegBuf = nullptr;

void displayInit() {
    tft.init();
    tft.setRotation(1); // Landscape
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.setTextSize(1);
    
    // Turn on backlight
    pinMode(TFT_BL, OUTPUT);
    digitalWrite(TFT_BL, HIGH);
    
    // Setup JPEG decoder
    TJpgDec.setJpgScale(1);
    TJpgDec.setSwapBytes(true);
    TJpgDec.setCallback(tft_output);
    
    Serial.println("Display initialized");
}

void displayShowMessage(const char* title, const char* message) {
    tft.fillScreen(TFT_BLACK);
    tft.setTextColor(TFT_CYAN, TFT_BLACK);
    tft.drawString(title, 10, 10, 2);
    tft.setTextColor(TFT_WHITE, TFT_BLACK);
    tft.drawString(message, 10, 40, 1);
}

void displayShowStatus(const char* ip, bool connected) {
    tft.fillRect(0, 0, SCREEN_WIDTH, STATUS_HEIGHT, TFT_NAVY);
    tft.setTextColor(TFT_WHITE, TFT_NAVY);
    tft.setTextSize(1);
    
    if (connected) {
        tft.drawString("IP: " + String(ip), 5, 5, 1);
        tft.fillCircle(SCREEN_WIDTH - 10, 10, 4, TFT_GREEN);
    } else {
        tft.drawString("Connecting...", 5, 5, 1);
        tft.fillCircle(SCREEN_WIDTH - 10, 10, 4, TFT_RED);
    }
}

void displayShowImage(const uint8_t* buf, uint32_t bufSize) {
    tft.fillRect(0, STATUS_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT - STATUS_HEIGHT, TFT_BLACK);
    
    int16_t result = TJpgDec.drawJpg(0, STATUS_HEIGHT, buf, bufSize);
    if (result != 0) {
        tft.setTextColor(TFT_RED, TFT_BLACK);
        tft.drawString("JPEG Error: " + String(result), 10, 120, 1);
    }
}

void displayShowReceiving() {
    tft.fillRect(0, STATUS_HEIGHT, SCREEN_WIDTH, 40, TFT_BLACK);
    tft.setTextColor(TFT_YELLOW, TFT_BLACK);
    tft.drawString("Receiving image...", 10, STATUS_HEIGHT + 10, 1);
}

void displayShowDone() {
    tft.fillRect(0, STATUS_HEIGHT, SCREEN_WIDTH, 40, TFT_BLACK);
    tft.setTextColor(TFT_GREEN, TFT_BLACK);
    tft.drawString("Image displayed!", 10, STATUS_HEIGHT + 10, 1);
}

// JPEG output callback for TJpg_Decoder
bool tft_output(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t* bitmap) {
    if (y >= tft.height()) return false;
    tft.pushImage(x, y, w, h, bitmap);
    return true;
}

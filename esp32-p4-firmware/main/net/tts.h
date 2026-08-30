/*
 * tts.h - Text-to-speech: call FishTTS and play through the board speaker
 */
#ifndef TTS_H
#define TTS_H

#include <stdbool.h>
#include "esp_codec_dev.h"

/* Initialize the codec / I2S for playback. Returns ESP_OK. */
esp_err_t tts_init(void);

/* Synthesize `text` via TTS and play it. Blocks until playback starts.
 * Returns 0 on success, non-zero on failure. */
int tts_speak(const char *text, int volume);

/* Set output volume 0..100. */
void tts_set_volume(int volume);

/* Set the TTS endpoint/model/voice (from settings). */
void tts_set_config(const char *url, const char *model, const char *voice);

/* Stop any in-progress playback. */
void tts_stop(void);

#endif

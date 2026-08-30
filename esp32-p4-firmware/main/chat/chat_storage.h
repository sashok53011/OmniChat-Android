/*
 * chat_storage.h - SPIFFS persistence of app state (sessions, providers, memory, config)
 */
#ifndef CHAT_STORAGE_H
#define CHAT_STORAGE_H

#include <stdbool.h>
#include "chat_model.h"

/* Mount SPIFFS (idempotent). Returns ESP_OK on success. */
int chat_storage_init(void);

/* Load the whole persisted app_state (providers, config, memory). Sessions loaded separately. */
int chat_storage_load_state(app_state_t *state);

/* Persist config + providers + memory (not sessions). */
int chat_storage_save_state(const app_state_t *state);

/* Sessions: load all into an array (heap owned). Returns count. */
int chat_storage_load_sessions(chat_session_t **out_sessions, int max);

/* Persist a single session. */
int chat_storage_save_session(const chat_session_t *session);

/* Delete a session. */
int chat_storage_delete_session(const char *id);

#endif /* CHAT_STORAGE_H */

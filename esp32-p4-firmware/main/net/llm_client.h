/*
 * llm_client.h - talk to OpenAI-compatible chat/completions endpoints
 */
#ifndef LLM_CLIENT_H
#define LLM_CLIENT_H

#include "chat_model.h"

typedef struct {
    char *body;      /* malloc'd full response text (owned by caller) */
    int status;      /* HTTP status */
    char error[256];
} llm_result_t;

/*
 * Query a single OpenAI-compatible provider (opencode.ai, ollama cloud).
 * messages: array of {role, text}. system_prompt appended as the first system message.
 * Returns 0 on success (body set), non-zero on failure.
 */
int llm_openai_compatible(
    const ai_provider_t *provider,
    const chat_message_t *messages,
    int message_count,
    const char *system_prompt,
    llm_result_t *out);

/* Free the result body. */
void llm_result_free(llm_result_t *r);

/* Extract assistant text from a raw chat/completions JSON response (returns malloc'd string). */
char *llm_parse_choices(const char *json_body);

#endif /* LLM_CLIENT_H */

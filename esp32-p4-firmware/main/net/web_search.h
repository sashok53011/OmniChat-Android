/*
 * web_search.h - DuckDuckGo HTML web search for OmniChat-P4
 */
#ifndef WEB_SEARCH_H
#define WEB_SEARCH_H

/*
 * Search DuckDuckGo HTML and return a malloc'd plaintext summary of up to 4 results.
 * Returns NULL on failure or no results. Caller frees.
 */
char *web_search_query(const char *query);

#endif

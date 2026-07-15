#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

#if defined(_WIN32)
#define MILKY_EXPORT __declspec(dllexport)
#else
#define MILKY_EXPORT __attribute__((visibility("default")))
#endif

MILKY_EXPORT bool milky_console_load_plugin_onload(const unsigned char *config) {
    (void) config;
    return true;
}

MILKY_EXPORT int milky_console_load_plugin_onunload(void) {
    return 0;
}

MILKY_EXPORT void milky_console_load_plugin_onmessage(const unsigned char *message) {
    (void) message;

    /* The current ABI carries no buffer length, so the fixture deliberately
       treats the payload as opaque and only records that the callback ran. */
    const char *marker = getenv("MILKY_PLUGIN_TEST_MESSAGE_MARKER");
    if (marker == NULL || marker[0] == '\0') {
        return;
    }

    FILE *file = fopen(marker, "wb");
    if (file == NULL) {
        return;
    }
    fputs("received", file);
    fclose(file);
}

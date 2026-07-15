#include <stdbool.h>

#if defined(_WIN32)
#define MILKY_EXPORT __declspec(dllexport)
#else
#define MILKY_EXPORT __attribute__((visibility("default")))
#endif

MILKY_EXPORT bool milky_console_load_plugin_onload(const unsigned char *config) {
    (void) config;
    return false;
}

MILKY_EXPORT int milky_console_load_plugin_onunload(void) {
    return 0;
}

MILKY_EXPORT void milky_console_load_plugin_onmessage(const unsigned char *message) {
    (void) message;
}

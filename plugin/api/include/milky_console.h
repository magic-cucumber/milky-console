#ifndef MILKY_CONSOLE_H
#define MILKY_CONSOLE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(MILKY_CONSOLE_BUILDING_LIBRARY)
#    define MILKY_CONSOLE_EXPORT __declspec(dllexport)
#  else
#    define MILKY_CONSOLE_EXPORT __declspec(dllimport)
#  endif
#else
#  define MILKY_CONSOLE_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef int32_t milky_bool_t;


#define MILKY_CONSOLE_HOST_ABI_VERSION 1u
#define MILKY_CONSOLE_PLUGIN_ABI_VERSION 1u

typedef struct milky_console_host_api {
    uint32_t abi_version;
    uint32_t struct_size;
    int32_t (*send_message)(const char *body);
} milky_console_host_api_t;

#define MILKY_CONSOLE_HOST_API_V1_SIZE \
    (offsetof(milky_console_host_api_t, send_message) + sizeof(((milky_console_host_api_t *)0)->send_message))

typedef struct milky_console_plugin_api {
    uint32_t abi_version;
    uint32_t struct_size;

    milky_bool_t (*on_load)(
        const char *config_json,
        const milky_console_host_api_t *host_api
    );
    int32_t (*on_unload)(void);
    void (*on_message)(const char *body);
} milky_console_plugin_api_t;

#define MILKY_CONSOLE_PLUGIN_API_V1_SIZE \
    (offsetof(milky_console_plugin_api_t, on_message) + sizeof(((milky_console_plugin_api_t *)0)->on_message))

MILKY_CONSOLE_EXPORT const milky_console_plugin_api_t *milky_plugin_get_api(
    uint32_t requested_abi_version
);

#ifdef __cplusplus
}
#endif

#endif /* MILKY_CONSOLE_H */

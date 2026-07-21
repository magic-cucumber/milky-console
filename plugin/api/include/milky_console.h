#ifndef MILKY_CONSOLE_H
#define MILKY_CONSOLE_H

#include <stddef.h>
#include <stdint.h>

/*
 * Symbol visibility
 */
#if defined(_WIN32)
#  if defined(MILKY_CONSOLE_BUILDING_LIBRARY)
#    define MILKY_CONSOLE_EXPORT __declspec(dllexport)
#  else
#    define MILKY_CONSOLE_EXPORT __declspec(dllimport)
#  endif
#  define MILKY_CONSOLE_CALL __cdecl
#else
#  define MILKY_CONSOLE_EXPORT __attribute__((visibility("default")))
#  define MILKY_CONSOLE_CALL
#endif

/*
 * Optional compiler annotations
 */
#if defined(__GNUC__) || defined(__clang__)
#  define MILKY_CONSOLE_NODISCARD __attribute__((warn_unused_result))
#  define MILKY_CONSOLE_NONNULL(...) __attribute__((nonnull(__VA_ARGS__)))
#else
#  define MILKY_CONSOLE_NODISCARD
#  define MILKY_CONSOLE_NONNULL(...)
#endif

#ifdef __cplusplus
extern "C" {
#endif

/*
 * API primitive types. All supported targets use 32-bit int and unsigned int;
 * these fundamental C types map directly to common Kotlin Int and UInt.
 */
typedef int milky_bool_t;
typedef int milky_result_t;

/* A UUID in canonical textual form, plus its terminating NUL byte. */
#define MILKY_MESSAGE_ID_SIZE 37u

#define MILKY_FALSE ((milky_bool_t)0)
#define MILKY_TRUE  ((milky_bool_t)1)

/*
 * Common result codes
 */
#define MILKY_RESULT_OK                ((milky_result_t)0)
#define MILKY_RESULT_INVALID_ARGUMENT  ((milky_result_t)-1)
#define MILKY_RESULT_UNSUPPORTED       ((milky_result_t)-2)
#define MILKY_RESULT_INTERNAL_ERROR    ((milky_result_t)-3)
#define MILKY_RESULT_TIMEOUT           ((milky_result_t)-4)
#define MILKY_RESULT_NOT_FOUND         ((milky_result_t)-5)
#define MILKY_RESULT_BUFFER_TOO_SHORT ((milky_result_t)-6)

typedef struct milky_message_send_result {
    milky_result_t result;
    char uuid[MILKY_MESSAGE_ID_SIZE];
} milky_message_send_result_t;

typedef struct milky_message_wait_result {
    milky_result_t result;
    /* Required UTF-8 response bytes, including the terminating NUL byte. */
    size_t required_size;
} milky_message_wait_result_t;

/*
 * ABI versions
 *
 * Increment the ABI version only when compatibility cannot be preserved.
 * Compatible extensions should append fields to the end of the relevant
 * structure and update its corresponding *_SIZE constant.
 */
#define MILKY_CONSOLE_HOST_ABI_VERSION   1u
#define MILKY_CONSOLE_PLUGIN_ABI_VERSION 1u

/*
 * Host API
 *
 * The host owns this structure and all function pointers contained in it.
 * The plugin must not modify or free it.
 *
 * New members may only be appended to the end of this structure.
 */
typedef struct milky_console_host_api {
    unsigned int abi_version;
    unsigned int struct_size;


    milky_message_send_result_t (MILKY_CONSOLE_CALL *send_message)(
        uint64_t uin,
        const char *type,
        const char *message
    );

    milky_message_wait_result_t (MILKY_CONSOLE_CALL *wait_message_result)(
        const char *id,
        int timeout,
        char *buffer,
        size_t buffer_size
    );
} milky_console_host_api_t;

#define MILKY_CONSOLE_HOST_API_V1_SIZE \
    (offsetof(milky_console_host_api_t, wait_message_result) + \
     sizeof(((milky_console_host_api_t *)0)->wait_message_result))

/*
 * Plugin API
 *
 * The plugin owns the returned structure. It must remain valid and unchanged
 * until the plugin dynamic library is unloaded.
 *
 * New members may only be appended to the end of this structure.
 */
typedef struct milky_console_plugin_api {
    unsigned int abi_version;
    unsigned int struct_size;

    /*
     * Initializes the plugin.
     *
     * config_json may be NULL. When non-NULL, it points to a null-terminated
     * UTF-8 JSON string.
     *
     * host_api must remain valid until on_unload returns.
     *
     * Returns MILKY_TRUE on success and MILKY_FALSE on failure.
     */
    milky_bool_t (MILKY_CONSOLE_CALL *on_load)(
        const char *config_json,
        const milky_console_host_api_t *host_api
    );

    /*
     * Shuts down the plugin.
     *
     * The plugin must stop using host_api before this function returns.
     */
    milky_result_t (MILKY_CONSOLE_CALL *on_unload)(void);

    /*
     * Delivers a null-terminated UTF-8 string to the plugin.
     *
     * message must not be NULL and is only guaranteed to remain valid for the
     * duration of the call.
     */
    void (MILKY_CONSOLE_CALL *on_message)(const char *message);
} milky_console_plugin_api_t;

#define MILKY_CONSOLE_PLUGIN_API_V1_SIZE \
    (offsetof(milky_console_plugin_api_t, on_message) + \
     sizeof(((milky_console_plugin_api_t *)0)->on_message))

/*
 * Plugin entry point
 *
 * Returns a pointer to a statically allocated plugin API structure compatible
 * with requested_abi_version, or NULL when the requested ABI is unsupported.
 *
 * The returned structure must:
 *
 * - have abi_version set to the implemented ABI version;
 * - have struct_size set to the initialized structure size;
 * - remain valid until the dynamic library is unloaded;
 * - never be modified or freed by the host.
 */
MILKY_CONSOLE_EXPORT
MILKY_CONSOLE_NODISCARD
const milky_console_plugin_api_t *MILKY_CONSOLE_CALL
milky_plugin_get_api(unsigned int requested_abi_version);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* MILKY_CONSOLE_H */

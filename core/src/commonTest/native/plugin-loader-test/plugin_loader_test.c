#include "milky_console.h"

static milky_bool_t MILKY_CONSOLE_CALL on_load(
    const char *config_json,
    const milky_console_host_api_t *host_api
) {
    (void) config_json;
    (void) host_api;
    return MILKY_TRUE;
}

static milky_result_t MILKY_CONSOLE_CALL on_unload(void) {
    return MILKY_RESULT_OK;
}

static void MILKY_CONSOLE_CALL on_message(const char *message) {
    (void) message;
}

static const milky_console_plugin_api_t PLUGIN_API = {
    MILKY_CONSOLE_PLUGIN_ABI_VERSION,
    sizeof(milky_console_plugin_api_t),
    on_load,
    on_unload,
    on_message,
};

const milky_console_plugin_api_t *MILKY_CONSOLE_CALL
milky_plugin_get_api(unsigned int requested_abi_version) {
    return requested_abi_version == MILKY_CONSOLE_PLUGIN_ABI_VERSION ? &PLUGIN_API : NULL;
}

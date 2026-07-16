#include "milky_console.h"
#include <stdlib.h>

static milky_bool_t MILKY_CONSOLE_CALL on_load(
    const char *config_json,
    const milky_console_host_api_t *host_api
) {
    (void) config_json;
    (void) host_api;
    #if defined(_MSC_VER)
    _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
    #endif
    abort();
}

static const milky_console_plugin_api_t PLUGIN_API = {
    MILKY_CONSOLE_PLUGIN_ABI_VERSION,
    sizeof(milky_console_plugin_api_t),
    on_load,
    NULL,
    NULL,
};

const milky_console_plugin_api_t *MILKY_CONSOLE_CALL
milky_plugin_get_api(unsigned int requested_abi_version) {
    return requested_abi_version == MILKY_CONSOLE_PLUGIN_ABI_VERSION ? &PLUGIN_API : NULL;
}

#include "milky_console.h"
#include <string.h>
static milky_bool_t MILKY_CONSOLE_CALL on_load(const char *config, const milky_console_host_api_t *host) { (void) host; return config != NULL && strcmp(config, "{\"enabled\":true}") == 0 ? MILKY_TRUE : MILKY_FALSE; }
static const milky_console_plugin_api_t API = { MILKY_CONSOLE_PLUGIN_ABI_VERSION, sizeof(milky_console_plugin_api_t), on_load, NULL, NULL };
const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int requested_abi_version) { return requested_abi_version == MILKY_CONSOLE_PLUGIN_ABI_VERSION ? &API : NULL; }

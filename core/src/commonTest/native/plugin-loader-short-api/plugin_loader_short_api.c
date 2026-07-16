#include "milky_console.h"
static const milky_console_plugin_api_t API = { MILKY_CONSOLE_PLUGIN_ABI_VERSION, 0u, NULL, NULL, NULL };
const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int requested_abi_version) { (void) requested_abi_version; return &API; }

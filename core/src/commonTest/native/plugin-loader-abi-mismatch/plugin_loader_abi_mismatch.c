#include "milky_console.h"
static const milky_console_plugin_api_t API = { 999u, sizeof(milky_console_plugin_api_t), NULL, NULL, NULL };
const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int requested_abi_version) { (void) requested_abi_version; return &API; }

#include "milky_console.h"
#include <stdlib.h>

const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int requested_abi_version) {
    (void) requested_abi_version;
    abort();
}

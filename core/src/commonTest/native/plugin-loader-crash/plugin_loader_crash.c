#include "milky_console.h"
#include <stdlib.h>

const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int requested_abi_version) {
    (void) requested_abi_version;
    #if defined(_MSC_VER)
    _set_abort_behavior(0, _WRITE_ABORT_MSG | _CALL_REPORTFAULT);
    #endif
    abort();
}

#include "milky_console.h"

#include <stdlib.h>
#include <string.h>

static const milky_console_host_api_t *HOST_API;

static milky_bool_t request_and_wait(void) {
    char response[128];
    milky_message_send_result_t sent = HOST_API->send_message("/get_login_info", "{}");
    if (sent.result != MILKY_RESULT_OK) return MILKY_FALSE;
    milky_message_wait_result_t waited = HOST_API->wait_message_result(sent.uuid, 5000, response, sizeof(response));
    return waited.result == MILKY_RESULT_OK && strstr(response, "\"status\":\"ok\"") != NULL;
}

static milky_bool_t MILKY_CONSOLE_CALL on_load(const char *config_json, const milky_console_host_api_t *host_api) {
    (void)config_json;
    HOST_API = host_api;
    return HOST_API != NULL;
}

static milky_result_t MILKY_CONSOLE_CALL on_unload(void) { return MILKY_RESULT_OK; }
static void MILKY_CONSOLE_CALL on_message(const char *message) {
    (void)message;
    if (!request_and_wait()) abort();
}

static const milky_console_plugin_api_t PLUGIN_API = {
    MILKY_CONSOLE_PLUGIN_ABI_VERSION, sizeof(milky_console_plugin_api_t), on_load, on_unload, on_message,
};

const milky_console_plugin_api_t *MILKY_CONSOLE_CALL milky_plugin_get_api(unsigned int version) {
    return version == MILKY_CONSOLE_PLUGIN_ABI_VERSION ? &PLUGIN_API : NULL;
}

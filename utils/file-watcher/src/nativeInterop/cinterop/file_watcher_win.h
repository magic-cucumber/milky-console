#include <windows.h>
#include <stdint.h>

static inline uint32_t file_notify_next_offset(const FILE_NOTIFY_INFORMATION* value) {
    return value->NextEntryOffset;
}

static inline uint32_t file_notify_action(const FILE_NOTIFY_INFORMATION* value) {
    return value->Action;
}

static inline uint32_t file_notify_name_length(const FILE_NOTIFY_INFORMATION* value) {
    return value->FileNameLength;
}

static inline uint16_t file_notify_name_code_unit(
    const FILE_NOTIFY_INFORMATION* value,
    uint32_t index
) {
    return (uint16_t) value->FileName[index];
}

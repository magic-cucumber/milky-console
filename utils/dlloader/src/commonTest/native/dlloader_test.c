#if defined(_WIN32)
#define DLLOADER_TEST_EXPORT __declspec(dllexport)
#else
#define DLLOADER_TEST_EXPORT __attribute__((visibility("default")))
#endif

static const char *const DLLOADER_TEST_NAMES[] = {
    "dlloader_test_succeeds",
};

DLLOADER_TEST_EXPORT int dlloader_test_succeeds(void) {
    return 0;
}

DLLOADER_TEST_EXPORT int dlloader_test_return_42(void) {
    return 42;
}

DLLOADER_TEST_EXPORT int dlloader_test_add(int left, int right) {
    return left + right;
}

/*
 * Test-runner ABI: every registered test has the signature int(void), where
 * zero means success. The Kotlin loader uses this manifest because ordinary
 * shared libraries do not offer a portable way to enumerate exported symbols.
 */
DLLOADER_TEST_EXPORT int dlloader_test_count(void) {
    return (int)(sizeof(DLLOADER_TEST_NAMES) / sizeof(DLLOADER_TEST_NAMES[0]));
}

DLLOADER_TEST_EXPORT const char *dlloader_test_name(int index) {
    const int count = dlloader_test_count();
    return index >= 0 && index < count ? DLLOADER_TEST_NAMES[index] : 0;
}

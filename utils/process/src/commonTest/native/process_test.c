#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

static int parse_exit_code(const char *value) {
    return value == NULL ? 0 : atoi(value);
}

static int write_inherited_pipe(const char *value) {
    const char message[] = "inherited pipe";
#ifdef _WIN32
    DWORD written;
    HANDLE handle = (HANDLE) (uintptr_t) strtoull(value, NULL, 10);
    return WriteFile(handle, message, (DWORD) (sizeof(message) - 1), &written, NULL)
        && written == sizeof(message) - 1 ? 0 : 1;
#else
    int fd = (int) strtol(value, NULL, 10);
    return write(fd, message, sizeof(message) - 1) == sizeof(message) - 1 ? 0 : 1;
#endif
}

int main(int argc, char **argv) {
    if (argc >= 3 && strcmp(argv[1], "--exit-code") == 0) {
        return parse_exit_code(argv[2]);
    }

    if (argc >= 3 && strcmp(argv[1], "--stdout") == 0) {
        fputs(argv[2], stdout);
        return 0;
    }

    if (argc >= 3 && strcmp(argv[1], "--stderr") == 0) {
        fputs(argv[2], stderr);
        return 0;
    }

    if (argc >= 3 && strcmp(argv[1], "--env") == 0) {
        const char *value = getenv(argv[2]);
        if (value != NULL) {
            fputs(value, stdout);
        }
        return 0;
    }

    if (argc >= 2 && strcmp(argv[1], "--echo-stdin") == 0) {
        int ch;
        while ((ch = getchar()) != EOF) {
            putchar(ch);
        }
        return 0;
    }

    if (argc >= 3 && strcmp(argv[1], "--write-inherited-pipe") == 0) {
        return write_inherited_pipe(argv[2]);
    }

    if (argc >= 3 && strcmp(argv[1], "--not-write-inherited-pipe") == 0) {
        return write_inherited_pipe(argv[2]) == 0 ? 1 : 0;
    }

    fprintf(stderr, "unknown process_test command\n");
    return 2;
}

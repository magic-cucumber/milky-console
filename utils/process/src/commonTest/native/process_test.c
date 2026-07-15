#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int parse_exit_code(const char *value) {
    return value == NULL ? 0 : atoi(value);
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

    fprintf(stderr, "unknown process_test command\n");
    return 2;
}

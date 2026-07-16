#ifndef MILKY_CONSOLE_POSIX_SPAWN_H
#define MILKY_CONSOLE_POSIX_SPAWN_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <spawn.h>
#include <unistd.h>

/* glibc exposes this GNU extension only when _GNU_SOURCE is defined. */
#if defined(__linux__)
int posix_spawn_file_actions_addchdir_np(
    posix_spawn_file_actions_t *file_actions,
    const char *path
);
#endif

#endif

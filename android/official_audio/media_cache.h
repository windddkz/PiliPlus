#ifndef PILIPLUS_MEDIA_CACHE_H
#define PILIPLUS_MEDIA_CACHE_H
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <limits.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

/* Validate our persisted index before the original parser allocates native trees from it. */
static int cache_number(FILE *file, const char *key, int64_t *value) {
    char line[128];
    if (!fgets(line, sizeof line, file) || strncmp(line, key, strlen(key)))
        return 0;
    char *number = line + strlen(key), *end;
    if (*number < '0' || *number > '9')
        return 0;
    errno = 0;
    unsigned long long parsed = strtoull(number, &end, 10);
    if (errno || parsed > INT64_MAX || strcmp(end, "\n"))
        return 0;
    *value = (int64_t)parsed;
    return 1;
}
static int cache_line(FILE *file, const char *expected) {
    char line[128];
    return fgets(line, sizeof line, file) && !strcmp(line, expected);
}
static int cache_index_valid(const char *map, const char *data) {
    struct stat ms, ds;
    int fd = open(map, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return 0;
    int valid = !fstat(fd, &ms) && S_ISREG(ms.st_mode) && ms.st_size > 0 && ms.st_size <= 32768 &&
                !lstat(data, &ds) && S_ISREG(ds.st_mode) && ds.st_size > 0 &&
                ds.st_size <= 128 * 1024 * 1024;
    FILE *file = fdopen(fd, "r");
    if (!file) {
        close(fd);
        return 0;
    }
    int64_t index, init, physical_size, logical_size, previous = 0;
    valid = valid && cache_number(file, "tree_index:", &index) && index == 0 &&
            cache_number(file, "tree_physical_init_pos:", &init) && init == 0 &&
            cache_number(file, "tree_physical_size:", &physical_size) &&
            physical_size <= ds.st_size && cache_number(file, "tree_file_size:", &logical_size) &&
            cache_line(file, "tree-info-flush\n");
    // Official overflow reset leaves file size unknown (zero), while extents remain usable.
    unsigned entries = 0;
    while (valid) {
        int ch = fgetc(file);
        if (ch == EOF)
            break;
        if (ungetc(ch, file) == EOF) {
            valid = 0;
            break;
        }
        int64_t logical, physical, size;
        valid =
            ++entries <= 1024 && cache_number(file, "entry_logical_pos:", &logical) &&
            cache_number(file, "entry_physical_pos:", &physical) &&
            cache_number(file, "entry_size:", &size) && size > 0 && logical >= previous &&
            logical <= INT64_MAX - size &&
            (logical_size == 0 || (logical <= logical_size && size <= logical_size - logical)) &&
            physical <= physical_size && size <= physical_size - physical &&
            cache_line(file, "entry-info-flush\n");
        if (valid)
            previous = logical + size;
    }
    valid = valid && entries > 0 && !ferror(file);
    fclose(file);
    return valid;
}
#endif

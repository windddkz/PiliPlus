#define _GNU_SOURCE
#include "../official_audio/media_cache.h"
#include <assert.h>
int main(void) {
    char directory[] = "/tmp/piliplus-cache-index-XXXXXX";
    assert(mkdtemp(directory));
    char map[128], data[128], link[128];
    snprintf(map, sizeof map, "%s/1.map", directory);
    snprintf(data, sizeof data, "%s/1.bin", directory);
    snprintf(link, sizeof link, "%s/link", directory);
    FILE *f = fopen(data, "w");
    assert(f);
    assert(fwrite("0123456789", 1, 10, f) == 10);
    fclose(f);
    const char *header =
        "tree_index:0\ntree_physical_init_pos:0\ntree_physical_size:10\ntree_file_size:100\ntree-info-flush\n";
    const char *entries[] = {
        "entry_logical_pos:50\nentry_physical_pos:0\nentry_size:10\nentry-info-flush\n",
        "entry_logical_pos:50\nentry_physical_pos:1\nentry_size:10\nentry-info-flush\n",
        "entry_logical_pos:99\nentry_physical_pos:0\nentry_size:10\nentry-info-flush\n",
        "entry_logical_pos:-1\nentry_physical_pos:0\nentry_size:10\nentry-info-flush\n",
        "entry_logical_pos:18446744073709551615\nentry_physical_pos:0\nentry_size:10\nentry-info-flush\n",
        "entry_logical_pos:50\nentry_physical_pos:0\nentry_size:0\nentry-info-flush\n",
        "entry_logical_pos:50\nentry_physical_pos:0\nentry_size:10\n",
        ""};
    for (unsigned i = 0; i < sizeof entries / sizeof entries[0]; i++) {
        f = fopen(map, "w");
        assert(f);
        fputs(header, f);
        fputs(entries[i], f);
        fclose(f);
        assert(cache_index_valid(map, data) == (i == 0));
    }
    f = fopen(map, "w");
    assert(f);
    fputs(header, f);
    fputs(entries[0], f);
    fputs(entries[0], f);
    fclose(f);
    assert(!cache_index_valid(map, data));
    f = fopen(map, "w");
    assert(f);
    fputs(header, f);
    fputs(entries[0], f);
    fclose(f);
    assert(!symlink(map, link));
    assert(!cache_index_valid(link, data));
    unlink(link);
    assert(!symlink(data, link));
    assert(!cache_index_valid(map, link));
    unlink(link);
    f = fopen(map, "a");
    assert(f);
    for (int i = 0; i < 33000; i++)
        fputc('0', f);
    fclose(f);
    assert(!cache_index_valid(map, data));
    // The official cache resets file size to unknown when its bounded file wraps.
    f = fopen(map, "w");
    assert(f);
    fputs(
        "tree_index:0\ntree_physical_init_pos:0\ntree_physical_size:10\ntree_file_size:0\ntree-info-flush\n",
        f);
    fputs(entries[0], f);
    fclose(f);
    assert(cache_index_valid(map, data));
    f = fopen(map, "w");
    assert(f);
    fputs(
        "tree_index:0\ntree_physical_init_pos:0\ntree_physical_size:10\ntree_file_size:0\ntree-info-flush\n",
        f);
    fputs(
        "entry_logical_pos:9223372036854775800\nentry_physical_pos:0\nentry_size:10\nentry-info-flush\n",
        f);
    fclose(f);
    assert(!cache_index_valid(map, data));
    f = fopen(map, "w");
    assert(f);
    fputs(header, f);
    fputs(entries[0], f);
    fclose(f);
    assert(!truncate(data, 128 * 1024 * 1024));
    assert(cache_index_valid(map, data));
    assert(!truncate(data, 128 * 1024 * 1024 + 1));
    assert(!cache_index_valid(map, data));
    unlink(map);
    unlink(data);
    rmdir(directory);
    puts("Cache index extents, truncation, overflow, overlap, size and symlink checks passed");
}

// shortcut: fixed-target debug traces only; synchronize child inspection before testing concurrent surface replacement.
#define VIDEO_TRACE_ROWS 8192
typedef struct {
    uint64_t begin, end;
    int64_t pts, duration;
    uintptr_t child_method;
    int width, height, kind, result, tid, render_flag;
    int64_t render_timestamp;
} VideoTraceRow;
static int video_trace_enabled;
static unsigned video_overflow;
static pthread_mutex_t video_trace_lock = PTHREAD_MUTEX_INITIALIZER;
static VideoTraceRow video_rows[VIDEO_TRACE_ROWS];
static unsigned video_count;
static uint64_t video_submits, video_updates;
static void record_video(VideoTraceRow *r) {
    r->end = monotonic_ns();
    r->tid = gettid();
    pthread_mutex_lock(&video_trace_lock);
    if (r->kind == 0)
        video_submits++;
    else
        video_updates++;
    if (video_count < VIDEO_TRACE_ROWS)
        video_rows[video_count++] = *r;
    else
        video_overflow++;
    pthread_mutex_unlock(&video_trace_lock);
}
static int observed_video_submit(void *self, void *frame) {
    VideoTraceRow r = {.begin = monotonic_ns()};
    if (frame) {
        r.pts = FIELD(frame, 0x18, int64_t);
        r.duration = FIELD(frame, 0x30, int64_t);
        r.width = FIELD(frame, 0x54, int);
        r.height = FIELD(frame, 0x58, int);
    }
    void *child = FIELD(self, 0xd0, void *);
    if (child)
        r.child_method = FIELD(FIELD(child, 0, void *), 0xb8, uintptr_t) - base;
    if (r.child_method == 0x1ca0a8) {
        r.render_flag = FIELD(child, 0x60, unsigned char);
        r.render_timestamp = FIELD(child, 0x68, int64_t);
    }
    int result = ((int (*)(void *, void *))(base + 0x19be94))(self, frame);
    r.result = result;
    record_video(&r);
    return result;
}
static void observed_video_update(void *self) {
    VideoTraceRow r = {.begin = monotonic_ns(), .kind = 1};
    ((void (*)(void *))(base + 0x19bd30))(self);
    record_video(&r);
}
static int replace_video_trace(int install) {
    if (!video_trace_enabled)
        return 0;
    uintptr_t original[2] = {base + 0x19be94, base + 0x19bd30},
              next[2] = {(uintptr_t)observed_video_submit, (uintptr_t)observed_video_update};
    if (install) {
        video_count = video_overflow = 0;
        video_submits = video_updates = 0;
    }
    return patch_pair((uintptr_t *)(base + 0x2bb970), (uintptr_t *)(base + 0x2bb978),
                      install ? original : next, install ? next : original, mprotect);
}
static int dump_video_trace(FILE *out) {
    if (fputs(
            "begin_ns\tend_ns\tkind\tresult\tpts_us\tduration_us\twidth\theight\tchild_method\ttid\trender_flag\trender_timestamp\n",
            out) < 0)
        return -1;
    for (unsigned i = 0; i < video_count; i++) {
        VideoTraceRow *r = &video_rows[i];
        if (fprintf(out, "%llu\t%llu\t%d\t%d\t%lld\t%lld\t%d\t%d\t%llx\t%d\t%d\t%lld\n",
                    (unsigned long long)r->begin, (unsigned long long)r->end, r->kind, r->result,
                    (long long)r->pts, (long long)r->duration, r->width, r->height,
                    (unsigned long long)r->child_method, r->tid, r->render_flag,
                    (long long)r->render_timestamp) < 0)
            return -1;
    }
    return 0;
}

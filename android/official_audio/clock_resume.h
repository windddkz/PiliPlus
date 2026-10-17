static pthread_mutex_t clock_resume_lock=PTHREAD_MUTEX_INITIALIZER;
static void *clock_resume_pending;
static int64_t clock_resume_since;
static int (*clock_play_original)(void *);

static void clear_clock_resume(void){
    pthread_mutex_lock(&clock_resume_lock);
    clock_resume_pending=NULL;clock_resume_since=0;
    pthread_mutex_unlock(&clock_resume_lock);
}
static int play_with_clock_resume(void *engine){
    int paused=FIELD(engine,0xb8,int)==3;
    int rc=clock_play_original(engine);
    if(!rc&&paused){
        void *sink=FIELD(engine,0x88,void *);
        void *tracker=sink?FIELD(sink,0x60,void *):NULL;
        void *clock=tracker?FIELD(tracker,0x20,void *):NULL;
        if(clock&&FIELD(clock,0,uintptr_t)==base+0x2b9b30){
            pthread_mutex_lock(&clock_resume_lock);
            clock_resume_pending=clock;clock_resume_since=(int64_t)(monotonic_ns()/1000);
            pthread_mutex_unlock(&clock_resume_lock);
        }
    }
    return rc;
}
static int resume_clock_timestamp(void *state,int valid,int64_t frames,int64_t stamp,int64_t now){
    int rebase=0;
    pthread_mutex_lock(&clock_resume_lock);
    if(state==clock_resume_pending&&valid&&frames>0&&stamp>=clock_resume_since&&
       frames>=FIELD(state,0x60,int64_t)&&now>=stamp&&
       now-stamp<=FIELD(state,0x28,int64_t)){
        clock_resume_pending=NULL;clock_resume_since=0;rebase=1;
    }
    pthread_mutex_unlock(&clock_resume_lock);
    if(rebase){
        // Paused wall time is not audio drift; rebuild the anchor inside its normal update callback.
        FIELD(state,0x39,unsigned char)=0;
    }
    return rebase;
}
static int replace_clock_resume(int install){
    uintptr_t old=base+0x185c7c,next=(uintptr_t)play_with_clock_resume;
    clear_clock_resume();
    if(install)clock_play_original=(int (*)(void *))old;
    return replace((uintptr_t *)(base+0x2b96b8),install?old:next,install?next:old);
}

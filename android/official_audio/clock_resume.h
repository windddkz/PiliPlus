static pthread_mutex_t clock_resume_lock=PTHREAD_MUTEX_INITIALIZER;
static void *clock_resume_pending;
static int64_t clock_resume_since;
static int clock_resume_paused;
static int (*clock_play_original)(void *);
static int (*clock_pause_original)(void *);

static void clear_clock_resume(void){
    pthread_mutex_lock(&clock_resume_lock);
    clock_resume_pending=NULL;clock_resume_since=0;clock_resume_paused=0;
    pthread_mutex_unlock(&clock_resume_lock);
}
static void mark_clock_lifecycle(void *engine,int paused){
    void *sink=FIELD(engine,0x88,void *);
    void *tracker=sink?FIELD(sink,0x60,void *):NULL;
    void *clock=tracker?FIELD(tracker,0x20,void *):NULL;
    if(clock&&FIELD(clock,0,uintptr_t)==base+0x2b9b30){
        pthread_mutex_lock(&clock_resume_lock);
        clock_resume_pending=clock;clock_resume_since=(int64_t)(monotonic_ns()/1000);
        clock_resume_paused=paused;
        pthread_mutex_unlock(&clock_resume_lock);
    }
}
static int play_with_clock_resume(void *engine){
    int paused=FIELD(engine,0xb8,int)==3;
    int rc=clock_play_original(engine);
    if(!rc&&paused)mark_clock_lifecycle(engine,0);
    return rc;
}
static int pause_with_clock_resume(void *engine){
    int playing=FIELD(engine,0xb8,int)==2;
    int rc=clock_pause_original(engine);
    if(!rc&&playing)mark_clock_lifecycle(engine,1);
    return rc;
}
static int resume_clock_timestamp(void *state,int valid,int64_t frames,int64_t stamp,int64_t now){
    int rebase=0;
    pthread_mutex_lock(&clock_resume_lock);
    if(state==clock_resume_pending&&valid&&frames>0&&stamp>=clock_resume_since&&
       frames>=FIELD(state,0x60,int64_t)&&now>0&&stamp>0&&
       (now>=stamp?now-stamp:stamp-now)<=FIELD(state,0x28,int64_t)){
        // The native caller samples now before GetTimestamp, so stamp can be slightly later.
        rebase=clock_resume_paused||frames==FIELD(state,0x60,int64_t)?2:1;
        if(rebase==1){clock_resume_pending=NULL;clock_resume_since=0;}
    }
    pthread_mutex_unlock(&clock_resume_lock);
    if(rebase){
        // Paused wall time is not audio drift; rebuild the anchor inside its normal update callback.
        FIELD(state,0x39,unsigned char)=0;
    }
    return rebase;
}
static int64_t clock_estimate_time(void *state,int64_t now){
    pthread_mutex_lock(&clock_resume_lock);
    // Do not turn paused wall time into audio frames while waiting for a resume timestamp.
    if(state==clock_resume_pending){
        if(!clock_resume_paused&&now>=clock_resume_since&&
           now-clock_resume_since>FIELD(state,0x18,int64_t)){
            clock_resume_pending=NULL;clock_resume_since=0;
            FIELD(state,0x39,unsigned char)=0; // Use the original latency fallback if timestamps stay unavailable.
        }else{
            if(!clock_resume_paused)FIELD(state,0x58,int64_t)=0;
            if(FIELD(state,0x39,unsigned char))now=FIELD(state,0x48,int64_t);
        }
    }
    pthread_mutex_unlock(&clock_resume_lock);
    return now;
}
static int replace_clock_resume(int install){
    uintptr_t old[2]={base+0x185c7c,base+0x185e1c};
    uintptr_t next[2]={(uintptr_t)play_with_clock_resume,(uintptr_t)pause_with_clock_resume};
    clear_clock_resume();
    if(install){clock_play_original=(int (*)(void *))old[0];clock_pause_original=(int (*)(void *))old[1];}
    return patch_pair((uintptr_t *)(base+0x2b96b8),(uintptr_t *)(base+0x2b96c0),install?old:next,install?next:old,mprotect);
}

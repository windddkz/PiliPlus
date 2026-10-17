static void (*timeline_reset_original)(void *);
static int timeline_reset_error;
static void notify_backward_pts(void *source,int64_t previous,int64_t current){
    // Match the native 500ms forward-jump threshold without treating unknown PTS as a reset.
    if(previous<0||current<0||current>=previous||previous-current<=500000)return;
    void *callback=FIELD(source,0x170,void *);
    if(callback){
        ((void (*)(void *,const int64_t *))FIELD(FIELD(callback,0,uintptr_t),0x30,uintptr_t))(callback,&current);
        __android_log_print(ANDROID_LOG_INFO,"PiliTimeline","backward pts jump detected previous=%lld current=%lld",(long long)previous,(long long)current);
    }
}
static int (*timeline_flush_original)(void *,int64_t);
static int flush_timeline_checked(void *engine,int64_t serial){
    int rc=timeline_flush_original(engine,serial);
    if(rc){
        __atomic_store_n(&timeline_reset_error,rc<0?rc:AVERROR(EIO),__ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_ERROR,"PiliTimeline","engine flush failed rc=%d",rc);
    }
    return rc;
}
static void reset_timeline_quiesced(void *renderer){
    if(__atomic_load_n(&timeline_reset_error,__ATOMIC_ACQUIRE))return;
    void *engine=FIELD(renderer,0x68,void *);
    int pending=(__atomic_load_n((unsigned char *)renderer+0xe8,__ATOMIC_ACQUIRE)|
                 __atomic_load_n((unsigned char *)renderer+0xc8,__ATOMIC_ACQUIRE))&1;
    int resume=engine&&pending&&(FIELD(renderer,0x10,unsigned char)&1)&&FIELD(engine,0xb8,int)==2;
    int rc=0;
    if(resume){
        rc=((int (*)(void *))FIELD(FIELD(engine,0,uintptr_t),0x30,uintptr_t))(engine);
        if(rc)goto fail;
    }
    // Stop prerender before first-PTS and accumulated-time resets can become visible separately.
    timeline_reset_original(renderer);
    if(__atomic_load_n(&timeline_reset_error,__ATOMIC_ACQUIRE))return;
    if(resume){
        rc=((int (*)(void *))FIELD(FIELD(engine,0,uintptr_t),0x28,uintptr_t))(engine);
        if(rc)goto fail;
        __android_log_print(ANDROID_LOG_INFO,"PiliTimeline","quiesced reset completed");
    }
    return;
fail:
    __atomic_store_n(&timeline_reset_error,rc<0?rc:AVERROR(EIO),__ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_ERROR,"PiliTimeline","reset lifecycle failed rc=%d",rc);
}
static int replace_timeline_flush(int install){
    uintptr_t old=base+0x186094,next=(uintptr_t)flush_timeline_checked;
    if(install){
        if(FIELD((void *)base,0x186094,uint64_t)!=UINT64_C(0xa9055ffed10203ff))return -1;
        timeline_flush_original=(int (*)(void *,int64_t))old;timeline_reset_error=0;
    }
    return replace((uintptr_t *)(base+0x2b96d0),install?old:next,install?next:old);
}
static int replace_timeline_reset(int install){
    uintptr_t old=base+0x158fcc,next=(uintptr_t)reset_timeline_quiesced;
    if(install){
        if(FIELD((void *)base,0x158fcc,uint32_t)!=0x14000001||
           FIELD((void *)base,0x15a124,uint32_t)!=0xf940ba60||
           FIELD((void *)base,0x15a138,uint32_t)!=0xf9401908||
           FIELD((void *)base,0x2b96b8,uintptr_t)!=base+0x185c7c||
           FIELD((void *)base,0x2b96c0,uintptr_t)!=base+0x185e1c)return -1;
        timeline_reset_original=(void (*)(void *))old;timeline_reset_error=0;
    }
    return replace((uintptr_t *)(base+0x2b75c0),install?old:next,install?next:old);
}

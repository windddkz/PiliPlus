/* Observe the owned output objects; never change their route, clock or playback rate. */
static JavaVM *output_vm;
static pthread_mutex_t output_lock=PTHREAD_MUTEX_INITIALIZER;
static jobject output_track;
static int output_track_requested;
static void *output_engine;
static int output_rate_error;
static unsigned (*output_timestamp_original)(void *,int64_t *,int64_t *);
static int (*output_rate_original)(void *,double);
static int (*output_close_original)(void *);

static JNIEnv *output_env(void){
    JNIEnv *env=NULL;
    if(!output_vm||(*output_vm)->GetEnv(output_vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK)return NULL;
    return env;
}
static void clear_output_track(void){
    JNIEnv *env=output_env();
    pthread_mutex_lock(&output_lock);
    if(output_track&&env){(*env)->DeleteGlobalRef(env,output_track);output_track=NULL;}
    pthread_mutex_unlock(&output_lock);
}
static unsigned observe_output_timestamp(void *device,int64_t *frames,int64_t *stamp){
    unsigned result=output_timestamp_original(device,frames,stamp);
    if(!__atomic_exchange_n(&output_track_requested,0,__ATOMIC_ACQ_REL))return result;
    JNIEnv *env=output_env();if(!env||(*env)->ExceptionCheck(env))return result;
    jobject track=NULL;
    /* The original GetTimestamp uses this same device lock and Java wrapper chain. */
    ((void (*)(void *))(base+0x26aa10))((char *)device+0x120);
    if(FIELD(device,0x178,unsigned char)&1){
        void *wrapper=FIELD(device,0x20,void *);
        jobject borrowed=wrapper?FIELD(wrapper,0x10,jobject):NULL;
        if(borrowed)track=(*env)->NewGlobalRef(env,borrowed);
    }
    ((void (*)(void *))(base+0x26aa64))((char *)device+0x120);
    if((*env)->ExceptionCheck(env)){(*env)->ExceptionClear(env);return result;}
    pthread_mutex_lock(&output_lock);
    jobject previous=output_track;output_track=track;
    pthread_mutex_unlock(&output_lock);
    if(previous)(*env)->DeleteGlobalRef(env,previous);
    return result;
}
static int observe_output_rate(void *engine,double rate){
    int result=output_rate_original(engine,rate);
    pthread_mutex_lock(&output_lock);
    output_engine=engine;output_rate_error=result;
    pthread_mutex_unlock(&output_lock);
    return result;
}
static int observe_output_close(void *engine){
    pthread_mutex_lock(&output_lock);
    if(output_engine==engine){output_engine=NULL;output_rate_error=0;}
    pthread_mutex_unlock(&output_lock);
    clear_output_track();
    return output_close_original(engine);
}
static int replace_output_track(int install){
    uintptr_t old=base+0x1b9e6c,next=(uintptr_t)observe_output_timestamp;
    if(install){
        if(FIELD((void *)base,0x1bd4a4,uint32_t)!=0xf9400ac0 ||
           FIELD((void *)base,0x1cbabc,uint32_t)!=0xf9400800)return -1;
        output_timestamp_original=(unsigned (*)(void *,int64_t *,int64_t *))old;
    }
    int rc=replace((uintptr_t *)(base+0x2bca60),install?old:next,install?next:old);
    if(!rc&&!install)clear_output_track();
    return rc;
}
static int replace_output_engine(int install){
    uintptr_t old[2]={base+0x186318,base+0x185ad8};
    uintptr_t next[2]={(uintptr_t)observe_output_rate,(uintptr_t)observe_output_close};
    if(install){
        if(FIELD((void *)base,0x186318,uint32_t)!=0xd102c3ff ||
           FIELD((void *)base,0x185c14,uint32_t)!=0x3d803e60)return -1;
        output_rate_original=(int (*)(void *,double))old[0];
        output_close_original=(int (*)(void *))old[1];
    }
    int rc=patch_pair((uintptr_t *)(base+0x2b96d8),(uintptr_t *)(base+0x2b96b0),install?old:next,install?next:old,mprotect);
    if(!rc){pthread_mutex_lock(&output_lock);output_engine=NULL;output_rate_error=0;pthread_mutex_unlock(&output_lock);}
    return rc;
}
JNIEXPORT jobject JNICALL Java_dev_piliplus_dspbridge_DspBridge_getAudioTrack(JNIEnv *env,jclass cls){
    (void)cls;
    __atomic_store_n(&output_track_requested,1,__ATOMIC_RELEASE);
    pthread_mutex_lock(&output_lock);
    jobject track=output_track?(*env)->NewLocalRef(env,output_track):NULL;
    pthread_mutex_unlock(&output_lock);
    return track;
}
JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_getOutputState(JNIEnv *env,jclass cls){
    (void)cls;double rate=0,requested=0;int pending=0;
    pthread_mutex_lock(&output_lock);
    int error=output_rate_error;
    if(output_engine){
        ((void (*)(void *))(base+0x26aa10))((char *)output_engine+0xbc);
        rate=FIELD(output_engine,0xf0,double);requested=FIELD(output_engine,0xf8,double);
        pending=FIELD(output_engine,0x100,unsigned char)&1;
        ((void (*)(void *))(base+0x26aa64))((char *)output_engine+0xbc);
    }
    pthread_mutex_unlock(&output_lock);
    char out[160];snprintf(out,sizeof(out),"{\"rate\":%.9g,\"requested\":%.9g,\"pending\":%s,\"error\":%d}",
        isfinite(rate)?rate:0,isfinite(requested)?requested:0,pending?"true":"false",error);
    return (*env)->NewStringUTF(env,out);
}

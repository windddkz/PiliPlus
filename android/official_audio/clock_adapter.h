#include "clock_resume.h"
typedef void (*TimestampFn)(void *,int,int64_t,int64_t,int64_t);
static uint64_t zero_timestamp_rejections;
static int clock_diagnostics;
typedef struct {int64_t frames,now,drift;unsigned char flags[8];} ClockEstimate;
_Static_assert(sizeof(ClockEstimate)==32 && offsetof(ClockEstimate,flags)==24,"Original clock return ABI");
typedef ClockEstimate (*EstimateFn)(void *,int64_t,int64_t,int64_t,int64_t);
static ClockEstimate observed_estimate(void *state,int64_t first,int64_t second,int64_t latency,int64_t now){
    static _Thread_local void *last_state;
    static _Thread_local int64_t last_log;
    int anchored=FIELD(state,0x39,unsigned char);
    int64_t previous=FIELD(state,0x50,int64_t),anchor=FIELD(state,0x40,int64_t),anchor_time=FIELD(state,0x48,int64_t);
    ClockEstimate out=((EstimateFn)(base+0x18b4f4))(state,first,second,latency,clock_estimate_time(state,now));
    out.now=now;
    if(clock_diagnostics&&(state!=last_state || now<last_log || now-last_log>=100000 || anchored!=FIELD(state,0x39,unsigned char))){
        __android_log_print(ANDROID_LOG_INFO,"PiliClockEstimate",
            "state=%p caller=%llx now=%lld rate=%d first=%lld second=%lld latency=%lld anchored=%d anchor=%lld anchor_time=%lld previous=%lld out=%lld flags=%u,%u,%u",
            state,(unsigned long long)((uintptr_t)__builtin_return_address(0)-base),(long long)now,FIELD(state,8,int),
            (long long)first,(long long)second,(long long)latency,anchored,(long long)anchor,(long long)anchor_time,
            (long long)previous,(long long)out.frames,out.flags[0],out.flags[1],out.flags[2]);
        last_state=state;last_log=now;
    }
    return out;
}
static void corrected_timestamp(void *state,int valid,int64_t frames,int64_t stamp,int64_t now){
    int raw_valid=valid;
    int anchored=0;int64_t anchor=0,anchor_time=0,previous_errors=0;
    if(clock_diagnostics){
        anchored=FIELD(state,0x39,unsigned char);anchor=FIELD(state,0x40,int64_t);
        anchor_time=FIELD(state,0x48,int64_t);previous_errors=FIELD(state,0x70,int64_t);
    }
    if(valid&&frames==0){valid=0;__atomic_add_fetch(&zero_timestamp_rejections,1,__ATOMIC_RELAXED);}
    int rebased=resume_clock_timestamp(state,valid,frames,stamp,now);
    ((TimestampFn)(base+0x18b394))(state,valid,frames,stamp,now);
    if(rebased==1)__android_log_print(ANDROID_LOG_INFO,"PiliClockResume","fresh timestamp accepted after resume frames=%lld",(long long)frames);
    if(clock_diagnostics)__android_log_print(ANDROID_LOG_INFO,"PiliClockTrace",
        "state=%p raw_valid=%d valid=%d frames=%lld stamp=%lld now=%lld rate=%d before=%d,%lld,%lld,%lld after=%d,%lld,%lld,%lld last_out=%lld last_stamp_frame=%lld drift_limit=%lld drift_instruction=%08x",
        state,raw_valid,valid,(long long)frames,(long long)stamp,(long long)now,FIELD(state,8,int),
        anchored,(long long)anchor,(long long)anchor_time,(long long)previous_errors,
        FIELD(state,0x39,unsigned char),(long long)FIELD(state,0x40,int64_t),(long long)FIELD(state,0x48,int64_t),(long long)FIELD(state,0x70,int64_t),
        (long long)FIELD(state,0x50,int64_t),(long long)FIELD(state,0x60,int64_t),(long long)FIELD(state,0x30,int64_t),FIELD((void *)base,0x18b488,uint32_t));
}
static int replace_clock_timestamp(int install){
    uintptr_t old[2]={base+0x18b394,base+0x18b4f4},next[2]={(uintptr_t)corrected_timestamp,(uintptr_t)observed_estimate};
    return patch_pair((uintptr_t *)(base+0x2b9b58),(uintptr_t *)(base+0x2b9b60),install?old:next,install?next:old,mprotect);
}
static int player_instructions_ready(void){
    return FIELD((void *)base,0x14898c,uint32_t)==0xd503201f &&
        FIELD((void *)base,0x1a93c4,uint32_t)==0x9b0a7d29 &&
        FIELD((void *)base,0x1a93c8,uint32_t)==0x9ac80928 &&
        FIELD((void *)base,0x18b488,uint32_t)==0xd503201f &&
        FIELD((void *)base,0x139e98,uint32_t)==0xd503201f &&
        FIELD((void *)base,0x139ea4,uint32_t)==0xb90013f6;
}

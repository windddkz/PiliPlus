#include <assert.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <pthread.h>
#define FIELD(p,o,T) (*(T *)((char *)(p)+(o)))
static uintptr_t base=0x100000;
static uint64_t tick=2000000000;
static uint64_t monotonic_ns(void){return tick;}
static int replace(uintptr_t *slot,uintptr_t old,uintptr_t next){(void)slot;(void)old;(void)next;return 0;}
#include "../official_audio/clock_resume.h"
static _Alignas(16) unsigned char engine[0xc0],sink[0x70],tracker[0x30],clock_state[0x80],other[0x80];
static int error;
static int play(void *e){assert(e==engine);if(!error)FIELD(e,0xb8,int)=2;return error;}
static void setup(int paused){
 clear_clock_resume();memset(engine,0,sizeof engine);memset(clock_state,0,sizeof clock_state);error=0;clock_play_original=play;
 FIELD(engine,0xb8,int)=paused?3:1;FIELD(engine,0x88,void *)=sink;FIELD(sink,0x60,void *)=tracker;FIELD(tracker,0x20,void *)=clock_state;
 FIELD(clock_state,0,uintptr_t)=base+0x2b9b30;FIELD(clock_state,0x28,int64_t)=1000000;FIELD(clock_state,0x60,int64_t)=96000;
 FIELD(clock_state,0x39,unsigned char)=1;FIELD(clock_state,0x50,int64_t)=96128;
}
int main(void){
 (void)replace_clock_resume;
 setup(0);assert(play_with_clock_resume(engine)==0&&!clock_resume_pending);
 setup(1);error=-5;assert(play_with_clock_resume(engine)==-5&&!clock_resume_pending);
 setup(1);FIELD(clock_state,0,uintptr_t)=0;assert(play_with_clock_resume(engine)==0&&!clock_resume_pending);
 setup(1);assert(play_with_clock_resume(engine)==0&&clock_resume_pending==clock_state);
 assert(!resume_clock_timestamp(other,1,97000,2100000,2200000));
 assert(!resume_clock_timestamp(clock_state,0,97000,2100000,2200000));
 assert(!resume_clock_timestamp(clock_state,1,0,2100000,2200000));
 assert(!resume_clock_timestamp(clock_state,1,95000,2100000,2200000));
 assert(!resume_clock_timestamp(clock_state,1,97000,1900000,2200000));
 assert(!resume_clock_timestamp(clock_state,1,97000,2300000,2200000));
 assert(!resume_clock_timestamp(clock_state,1,97000,2100000,3200001));
 assert(clock_resume_pending==clock_state&&FIELD(clock_state,0x39,unsigned char)==1);
 assert(resume_clock_timestamp(clock_state,1,97000,2100000,2200000));
 assert(!clock_resume_pending&&!FIELD(clock_state,0x39,unsigned char));
 assert(FIELD(clock_state,0x50,int64_t)==96128); // Preserve the original monotonic output bound.
 FIELD(clock_state,0x39,unsigned char)=1;
 assert(!resume_clock_timestamp(clock_state,1,98000,2200000,2300000));
 setup(1);play_with_clock_resume(engine);clear_clock_resume();assert(!clock_resume_pending);
 puts("PASS: cold start, failed play, ABI mismatch, fresh resume, invalid/stale/regressing samples and one-shot reset");
}

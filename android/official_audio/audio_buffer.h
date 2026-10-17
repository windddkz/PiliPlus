#include <stdint.h>
#include <errno.h>

typedef struct {int type,eof;uint64_t count;int64_t duration;} AudioBufferQueue;
typedef struct {
    int buffering,reason,started,seeking,accurate,eof,audio_only,live;
    int64_t current;
    AudioBufferQueue packets[2],frames[2];
} AudioBufferState;

static int64_t audio_buffer_water(const AudioBufferState *s){
    if(!s->buffering||(s->reason!=1002&&s->reason!=1004)||!s->started||s->seeking||s->accurate||s->eof||s->audio_only||s->live)return 0;
    const AudioBufferQueue *a=NULL,*v=NULL,*f=NULL,*vf=NULL;
    for(unsigned i=0;i<2;i++){
        if(s->packets[i].type==1)a=&s->packets[i];else if(s->packets[i].type==0)v=&s->packets[i];else return 0;
        if(s->frames[i].type==1)f=&s->frames[i];else if(s->frames[i].type==0)vf=&s->frames[i];else return 0;
    }
    if(!a||!v||!f||!vf||!a->count||!v->count||!f->count||f->eof||a->duration<0||v->duration<0||f->duration<=0||f->duration>5000000000000LL)return 0;
    // This locked runtime multiplies microsecond frame durations by 1e6 in FrameQueue.
    int64_t decoded_ns=f->duration/1000;
    if(a->duration>INT64_MAX-decoded_ns)return 0;
    int64_t value=a->duration+decoded_ns;if(v->duration<value)value=v->duration;
    return value>s->current?value:0;
}

#ifndef AUDIO_BUFFER_TEST
static uint64_t audio_buffer_adjustments;
static void audio_buffer_lock(void *guard,void *mutex){((void (*)(void *,void *))(base+0x1f3800))(guard,mutex);}
static void audio_buffer_unlock(void *guard){((void (*)(void *))(base+0x1f3834))(guard);}
static int audio_buffer_queues(void *checker,AudioBufferState *s){
    if(FIELD(checker,0x30,uint64_t)!=2||FIELD(checker,0x60,uint64_t)!=2)return 0;
    for(int kind=0;kind<2;kind++){
        void *node=FIELD(checker,kind?0x58:0x28,void *);unsigned n=0;
        for(;node&&n<2;n++,node=FIELD(node,0,void *)){
            AudioBufferQueue *q=kind?&s->frames[n]:&s->packets[n];q->type=FIELD(node,0x10,int);
            void *queue=FIELD(node,0x18,void *);if(!queue)return 0;
            uint64_t guard[2];audio_buffer_lock(guard,FIELD(queue,kind?0x50:0x88,void *));
            q->count=FIELD(queue,0x40,uint64_t);q->duration=FIELD(queue,kind?0x48:0x58,int64_t);
            q->eof=kind?FIELD(queue,0x78,unsigned char):0;
            audio_buffer_unlock(guard);
        }
        if(node||n!=2)return 0;
    }
    return 1;
}
static void audio_buffer_read(void *checker,unsigned char eof){
    ((void (*)(void *,unsigned char))(base+0x13a884))(checker,eof);int saved=errno;
    uint64_t guard[2];audio_buffer_lock(guard,FIELD(checker,0x40,void *));
    AudioBufferState s={.buffering=FIELD(checker,0x10,unsigned char),.reason=FIELD(checker,0xe4,int),
        .started=FIELD(checker,0xf8,unsigned char),.seeking=FIELD(checker,0x11,unsigned char),
        .accurate=FIELD(checker,0x12,unsigned char),.eof=FIELD(checker,0x13,unsigned char),
        .audio_only=FIELD(checker,0x108,unsigned char),.live=FIELD(checker,0x109,unsigned char)};
    int64_t value=0;uint64_t changed=0;
    if(s.buffering&&(s.reason==1002||s.reason==1004)&&s.started&&!s.seeking&&!s.accurate&&!s.eof&&!s.audio_only&&!s.live){
        void *water=FIELD(checker,0x70,void *);
        if(water&&audio_buffer_queues(checker,&s)){
            uint64_t water_guard[2];audio_buffer_lock(water_guard,FIELD(water,0x30,void *));
            s.current=FIELD(water,0x28,int64_t);value=audio_buffer_water(&s);
            if(value){FIELD(water,0x28,int64_t)=value;changed=__atomic_add_fetch(&audio_buffer_adjustments,1,__ATOMIC_RELAXED);}
            audio_buffer_unlock(water_guard);
        }
    }
    audio_buffer_unlock(guard);
    if(changed==1)__android_log_print(ANDROID_LOG_INFO,"PiliBuffer","decoded audio accounted reason=%d current_ns=%lld available_ns=%lld",s.reason,(long long)s.current,(long long)value);
    errno=saved;
}
static int replace_audio_buffer(int install){
    uintptr_t original=base+0x13a884,next=(uintptr_t)audio_buffer_read;
    return replace((uintptr_t *)(base+0x2b6710),install?original:next,install?next:original);
}
#endif

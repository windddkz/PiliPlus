#include <stddef.h>
#include <assert.h>
#include <stdio.h>
#define AUDIO_BUFFER_TEST
#include "../official_audio/audio_buffer.h"

int main(void){
    AudioBufferState s={.buffering=1,.reason=1002,.started=1,.current=170000000,
        .packets={{.type=1,.count=3,.duration=170000000},{.type=0,.count=16,.duration=533000000}},
        .frames={{.type=1,.count=9,.duration=767997000000},{.type=0,.count=3}}};
    assert(audio_buffer_water(&s)==533000000);
    s.packets[1].duration=2000000000;assert(audio_buffer_water(&s)==937997000);
    s.reason=1004;assert(audio_buffer_water(&s)==937997000);
    s.reason=1003;assert(!audio_buffer_water(&s));s.reason=1002;
    int *flags[]={&s.seeking,&s.accurate,&s.eof,&s.audio_only,&s.live};
    for(unsigned i=0;i<sizeof(flags)/sizeof(*flags);i++){*flags[i]=1;assert(!audio_buffer_water(&s));*flags[i]=0;}
    s.started=0;assert(!audio_buffer_water(&s));s.started=1;
    s.buffering=0;assert(!audio_buffer_water(&s));s.buffering=1;
    s.frames[0].eof=1;assert(!audio_buffer_water(&s));s.frames[0].eof=0;
    s.packets[0].count=0;assert(!audio_buffer_water(&s));s.packets[0].count=3;
    s.frames[0].count=0;assert(!audio_buffer_water(&s));s.frames[0].count=9;
    s.packets[0].duration=INT64_MAX;assert(!audio_buffer_water(&s));s.packets[0].duration=170000000;
    s.frames[0].duration=INT64_MAX;assert(!audio_buffer_water(&s));s.frames[0].duration=767997000000;
    s.current=1000000000;assert(!audio_buffer_water(&s));s.current=170000000;
    s.packets[1].type=1;assert(!audio_buffer_water(&s));s.packets[1].type=0;
    s.frames[1].type=1;assert(!audio_buffer_water(&s));s.frames[1].type=0;
    AudioBufferQueue tmp=s.packets[0];s.packets[0]=s.packets[1];s.packets[1]=tmp;
    tmp=s.frames[0];s.frames[0]=s.frames[1];s.frames[1]=tmp;
    assert(audio_buffer_water(&s)==937997000);
    puts("PASS: audio buffer units, limiting stream, both rebuffer states, map order, unsafe-state and overflow exclusions");
    return 0;
}

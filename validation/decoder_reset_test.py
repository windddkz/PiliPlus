"""Compile the production reset with mock original lifecycle APIs and injected failures."""

from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
header = repo / "android/official_audio/decoder_reset.h"
include = repo.parent / "execution/toolchain/ffmpeg-source/ffmpeg-7.1.1"
harness = (
    r"""
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>
#include <libavcodec/avcodec.h>
#define FIELD(p,o,T) (*(T *)((char *)(p)+(o)))
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
static int mock_log(int level,const char *tag,const char *format,...){(void)level;(void)tag;(void)format;return 0;}
#define __android_log_print mock_log
static uintptr_t base=0x10000000;
static pthread_mutex_t lp_lock=PTHREAD_MUTEX_INITIALIZER;
static unsigned lp_flushes, resets, released, flushed, allocated;
static int mode, missing;
static void lp_reset(void){resets++;}
static void release_frame(void *p){
    assert(FIELD(p,0,uintptr_t)==base+0x2bbe10);
    for(size_t i=8;i<=0x20;i+=8)if(FIELD(p,i,void *)){free(FIELD(p,i,void *));allocated--;}
    free(p);released++;
}
"""
    + '#include "'
    + str(header)
    + '"\n'
    + r"""
void graph_make_decoder(void **out,uintptr_t address){
    assert(address==base+0x196854);
    *out=mode==1?NULL:calloc(1,56);
    if(*out){FIELD(*out,0,uintptr_t)=base+0x2bbe10;FIELD(*out,0x28,uintptr_t)=123;FIELD(*out,0x30,uintptr_t)=456;}
}
static int open_decoder(void *p,void **metadata){
    assert(metadata&&*metadata);
    for(int i=1;i<=4;i++)if(i!=missing){
        FIELD(p,i*8,void *)=calloc(1,i==1?sizeof(AVCodecContext):8);allocated++;
    }
    AVCodecContext *c=FIELD(p,8,AVCodecContext *);
    if(c){c->codec_id=mode==7?AV_CODEC_ID_FLAC:AV_CODEC_ID_AAC;c->profile=mode>=5?AV_PROFILE_UNKNOWN:AV_PROFILE_AAC_HE;c->sample_rate=mode==4?44100:mode>=5?24000:48000;c->ch_layout.nb_channels=(mode==6||mode==8)?1:2;}
    return mode==2?0x98a622:mode==3?AVERROR(ENOMEM):0;
}
static int flush_decoder(void *p){assert(p);flushed++;return 19;}
static void *make(void){
    void *p=NULL,*metadata=&mode;mode=missing=0;
    graph_make_decoder(&p,base+0x196854);assert(p);assert(!open_decoder(p,&metadata));return p;
}
int main(void){
    decoder_open_original=open_decoder;decoder_flush_original=flush_decoder;
    for(int type=0;type<4;type++){
        void *p=make();AVCodecContext *c=FIELD(p,8,AVCodecContext *);
        if(type==0)c->codec_id=AV_CODEC_ID_FLAC;
        if(type==1)c->profile=AV_PROFILE_AAC_LOW;
        if(type==2)c->codec_id=AV_CODEC_ID_H264;
        if(type==3){free(c);allocated--;FIELD(p,8,void *)=NULL;}
        assert(reset_decoder(p)==19);release_frame(p);assert(!allocated);
    }
    assert(flushed==4&&!resets&&!decoder_resets);
    for(int profile=0;profile<2;profile++){
        void *p=make();
        for(int i=0;i<20;i++){
            AVCodecContext *old=FIELD(p,8,AVCodecContext *);old->profile=profile?AV_PROFILE_AAC_HE_V2:AV_PROFILE_AAC_HE;
            old->sample_rate=48000;old->ch_layout.nb_channels=2;mode=i%2?(profile?6:5):0;
            unsigned before=released;assert(!reset_decoder(p));
            assert(FIELD(p,8,void *)!=old&&released==before+1&&allocated==4);
            assert(FIELD(p,0,uintptr_t)==base+0x2bbe10&&FIELD(p,0x28,uintptr_t)==123&&FIELD(p,0x30,uintptr_t)==456);
        }
        release_frame(p);assert(!allocated);
    }
    assert(resets==40&&lp_flushes==40&&decoder_resets==40&&!decoder_reset_error);
    for(int failure=1;failure<=11;failure++){
        void *p=make();decoder_reset_error=0;
        if(failure<=4)mode=failure;
        else if(failure<=8)missing=failure-4;
        else if(failure==9){free(FIELD(p,0x20,void *));FIELD(p,0x20,void *)=NULL;allocated--;}
        else mode=failure-3;
        unsigned count=allocated;unsigned char saved[56];memcpy(saved,p,56);
        assert(reset_decoder(p)<0&&decoder_reset_error<0);
        assert(!memcmp(saved,p,56)&&allocated==count&&resets==40&&flushed==4);
        release_frame(p);assert(!allocated);
    }
    puts("HE/HEv2 reset ownership, 40 repeated resets, other codecs passthrough, factory/Open/resource/format failures preserve live object: passed");
}
"""
)
with tempfile.TemporaryDirectory(prefix="piliplus-decoder-reset-") as directory:
    p = Path(directory)
    (p / "test.c").write_text(harness)
    subprocess.run(
        [
            "cc",
            "-O1",
            "-g",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-I",
            str(include),
            str(p / "test.c"),
            "-lpthread",
            "-o",
            str(p / "test"),
        ],
        check=True,
    )
    subprocess.run([str(p / "test")], check=True)

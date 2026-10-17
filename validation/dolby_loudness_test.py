"""Exercise the production decode callback's Dolby gate and error/video-frame handling."""

from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
s = (repo / "android/official_audio/graph_adapter.c").read_text()
code = s[
    s.index("static int decoded_audio_codec;") : s.index("static void flush_adapted(")
]
dsp = (repo / "android/official_audio/dsp_adapter.c").read_text()
lookup = dsp[dsp.index("static const AVCodec *routed_find(") : dsp.index("static uintptr_t original_slots")]
harness = (
    r"""#include <assert.h>
#include <string.h>
#include <stdatomic.h>
#include <libavcodec/avcodec.h>
static int next_rc;
static int receive(void *c,AVFrame *f){(void)c;(void)f;return next_rc;}
static int (*receive_original)(void *,AVFrame *)=receive;
"""
    + code
    + r"""
static const AVCodec *background_codec;
static const AVCodec *find(int id){(void)id;return background_codec;}
static const AVCodec *(*original_find)(int)=find;
static const AVCodec *dolby_ac3, *dolby_eac3;
static atomic_uint routed_ac3, routed_eac3, delegated;
static _Atomic(const char *) selected_audio_codec;
"""
    + lookup
    + r"""
int main(void){
 _Static_assert(AV_CODEC_ID_AC3==0x15003 && AV_CODEC_ID_EAC3==0x15028 && AV_CODEC_ID_AC4==0x15067,"Locked official codec IDs");
 AVCodecContext c={0};AVFrame f={0};
 f.sample_rate=48000;f.nb_samples=1536;
 int ids[]={AV_CODEC_ID_AAC,AV_CODEC_ID_EAC3,AV_CODEC_ID_FLAC,AV_CODEC_ID_AC3,AV_CODEC_ID_AC4,AV_CODEC_ID_MP3};
 int bypass[]={0,1,0,1,1,0};
 const char *names[]={"aac","ijkeac3","flac","ijkac3","ac4","mp3"};
 AVCodec codecs[6]={0};
 for(unsigned i=0;i<sizeof(ids)/sizeof(*ids);i++){
  codecs[i].name=names[i]; c.codec=&codecs[i];
  c.codec_id=ids[i];next_rc=0;assert(receive_adapted(&c,&f)==0);
  assert(!strcmp(decoded_audio_name, names[i]));
  AVCodec preload={.name="aac", .type=AVMEDIA_TYPE_AUDIO};
  background_codec=&preload;
  assert(routed_find(AV_CODEC_ID_AAC)==&preload);
  assert(!strcmp(atomic_load(&selected_audio_codec), "aac"));
  assert(!strcmp(decoded_audio_name, names[i]));
  assert(decoded_audio_codec==ids[i] && codec_bypasses_loudness(decoded_audio_codec)==bypass[i]);
 }
 c.codec=&codecs[1]; c.codec_id=AV_CODEC_ID_EAC3;next_rc=AVERROR(EAGAIN);
 assert(receive_adapted(&c,&f)==AVERROR(EAGAIN) && decoded_audio_codec==AV_CODEC_ID_MP3);
 next_rc=0;f.sample_rate=0;assert(receive_adapted(&c,&f)==0 && decoded_audio_codec==AV_CODEC_ID_MP3);
 f.sample_rate=48000;f.nb_samples=0;assert(receive_adapted(&c,&f)==0 && decoded_audio_codec==AV_CODEC_ID_MP3);
 assert(!strcmp(decoded_audio_name, "mp3"));
 return 0;
}
"""
)
with tempfile.TemporaryDirectory() as d:
    p = Path(d)
    (p / "check.c").write_text(harness)
    subprocess.run(
        [
            "cc",
            "-std=c11",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-I",
            str(repo.parent / "execution/toolchain/ffmpeg-source/ffmpeg-7.1.1"),
            str(p / "check.c"),
            "-o",
            str(p / "check"),
        ],
        check=True,
    )
    subprocess.run([str(p / "check")], check=True)
print("6 decoded codecs retain identity across background lookups; Dolby gate, failed receive and empty/video frames passed")

"""Exercise the production decode callback's Dolby gate and error/video-frame handling."""

from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
s = (repo / "android/official_audio/graph_adapter.c").read_text()
code = s[
    s.index("static int decoded_audio_codec;") : s.index("static void flush_adapted(")
]
harness = (
    r"""#include <assert.h>
#include <libavcodec/avcodec.h>
static int next_rc;
static int receive(void *c,AVFrame *f){(void)c;(void)f;return next_rc;}
static int (*receive_original)(void *,AVFrame *)=receive;
"""
    + code
    + r"""
int main(void){
 _Static_assert(AV_CODEC_ID_AC3==0x15003 && AV_CODEC_ID_EAC3==0x15028 && AV_CODEC_ID_AC4==0x15067,"Locked official codec IDs");
 AVCodecContext c={0};AVFrame f={0};
 f.sample_rate=48000;f.nb_samples=1536;
 int ids[]={AV_CODEC_ID_AAC,AV_CODEC_ID_EAC3,AV_CODEC_ID_FLAC,AV_CODEC_ID_AC3,AV_CODEC_ID_AC4,AV_CODEC_ID_MP3};
 int bypass[]={0,1,0,1,1,0};
 for(unsigned i=0;i<sizeof(ids)/sizeof(*ids);i++){
  c.codec_id=ids[i];next_rc=0;assert(receive_adapted(&c,&f)==0);
  assert(decoded_audio_codec==ids[i] && codec_bypasses_loudness(decoded_audio_codec)==bypass[i]);
 }
 c.codec_id=AV_CODEC_ID_EAC3;next_rc=AVERROR(EAGAIN);
 assert(receive_adapted(&c,&f)==AVERROR(EAGAIN) && decoded_audio_codec==AV_CODEC_ID_MP3);
 next_rc=0;f.sample_rate=0;assert(receive_adapted(&c,&f)==0 && decoded_audio_codec==AV_CODEC_ID_MP3);
 f.sample_rate=48000;f.nb_samples=0;assert(receive_adapted(&c,&f)==0 && decoded_audio_codec==AV_CODEC_ID_MP3);
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
print("Dolby skip gate: 6 codecs, failed receive and empty/video frames passed")

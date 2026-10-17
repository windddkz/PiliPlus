#include <android/log.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/frame.h>
#include <libavutil/samplefmt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
_Static_assert(offsetof(AVFrame,nb_samples)==0x70,"Locked original AVFrame sample count");
_Static_assert(offsetof(AVFrame,ch_layout)==0x198,"Locked original AVFrame channel layout");
static void *lp_lib;
static AVFilterGraph *lp_graph;
static AVFilterContext *lp_source,*lp_sink;
static AVFilterGraph *(*lp_alloc)(void);
static void (*lp_free)(AVFilterGraph **);
static const AVFilter *(*lp_find)(const char *);
static int (*lp_create)(AVFilterContext **,const AVFilter *,const char *,const char *,void *,AVFilterGraph *);
static int (*lp_link)(AVFilterContext *,unsigned,AVFilterContext *,unsigned);
static int (*lp_config)(AVFilterGraph *,void *);
static int (*lp_add)(AVFilterContext *,AVFrame *,int);
static int (*lp_get)(AVFilterContext *,AVFrame *);
static AVFrame *(*lp_frame_alloc)(void);
static void (*lp_frame_free)(AVFrame **);
static int (*lp_writable)(AVFrame *);
static AVFrame *(*lp_clone)(const AVFrame *);
static void (*lp_unref)(AVFrame *);
static void (*lp_move)(AVFrame *,AVFrame *);
static const char *(*lp_format_name)(enum AVSampleFormat);
static int lp_profile,lp_built_profile=-1,lp_format,lp_rate,lp_channels,lp_error;
static double lp_gain,lp_metadata_gain_db;
static uint64_t lp_layout,lp_frames,lp_samples,lp_rebuilds,lp_bypassed;
static pthread_mutex_t lp_lock=PTHREAD_MUTEX_INITIALIZER;
static uint64_t lp_flushes;
static void *lp_context;
static void lp_reset(void){if(lp_graph)lp_free(&lp_graph);lp_source=lp_sink=NULL;lp_built_profile=-1;}
static void lp_close(void){lp_reset();if(lp_lib){dlclose(lp_lib);lp_lib=NULL;}}
static int lp_open(void){
 if(lp_lib)return -1;
 lp_lib=dlopen("libijkffmpeg.so",RTLD_NOW|RTLD_LOCAL);if(!lp_lib)return -1;
 unsigned (*version)(void)=dlsym(lp_lib,"avutil_version");
 if(!version||version()!=0x3b2764||dlsym(lp_lib,"avcodec_find_decoder")!=*(void **)(base+0x2ca5f8))goto fail;
 #define RESOLVE(name,symbol) do {name=dlsym(lp_lib,symbol);if(!name)goto fail;}while(0)
 RESOLVE(lp_alloc,"avfilter_graph_alloc");RESOLVE(lp_free,"avfilter_graph_free");RESOLVE(lp_find,"avfilter_get_by_name");
 RESOLVE(lp_create,"avfilter_graph_create_filter");RESOLVE(lp_link,"avfilter_link");RESOLVE(lp_config,"avfilter_graph_config");
 RESOLVE(lp_add,"av_buffersrc_add_frame_flags");RESOLVE(lp_get,"av_buffersink_get_frame");
 RESOLVE(lp_clone,"av_frame_clone");RESOLVE(lp_unref,"av_frame_unref");RESOLVE(lp_move,"av_frame_move_ref");
 RESOLVE(lp_frame_alloc,"av_frame_alloc");RESOLVE(lp_frame_free,"av_frame_free");RESOLVE(lp_writable,"av_frame_make_writable");RESOLVE(lp_format_name,"av_get_sample_fmt_name");
 #undef RESOLVE
 lp_profile=0;lp_built_profile=-1;lp_frames=lp_samples=lp_rebuilds=lp_bypassed=0;lp_error=0;lp_flushes=0;lp_context=NULL;return 0;
fail:
 dlclose(lp_lib);lp_lib=NULL;return -1;
}
static int lp_build(AVFrame *in){
 char source[256];const char *format=lp_format_name(in->format);
 if(!format||in->ch_layout.order!=AV_CHANNEL_ORDER_NATIVE)return -1;
 lp_graph=lp_alloc();if(!lp_graph)return AVERROR(ENOMEM);
 snprintf(source,sizeof(source),"time_base=1/1000000:sample_rate=%d:sample_fmt=%s:channel_layout=0x%llx",in->sample_rate,format,(unsigned long long)in->ch_layout.u.mask);
 lp_gain=pow(10,lp_metadata_gain_db/20);
 AVFilterContext *format_out=NULL;int rc;
 const AVFilter *src=lp_find("abuffer"),*fmt=lp_find("aformat"),*sink=lp_find("abuffersink");
 if(!src||!fmt||!sink)return AVERROR(ENOENT);
 if((rc=lp_create(&lp_source,src,"owned_input",source,NULL,lp_graph))<0||
    (rc=lp_create(&format_out,fmt,"owned_double_output","sample_fmts=dbl",NULL,lp_graph))<0||(rc=lp_create(&lp_sink,sink,"owned_sink",NULL,NULL,lp_graph))<0||
    (rc=lp_link(lp_source,0,format_out,0))<0||(rc=lp_link(format_out,0,lp_sink,0))<0||(rc=lp_config(lp_graph,NULL))<0)return rc;
 lp_format=in->format;lp_rate=in->sample_rate;lp_channels=in->ch_layout.nb_channels;lp_layout=in->ch_layout.u.mask;lp_built_profile=lp_profile;lp_rebuilds++;return 0;
}
// shortcut: app.in fixed-gain, one-in/one-out only; intl buffering needs a draining queue.
static int lp_apply(AVFrame *decoded,void *context){
 AVFrame *in=lp_clone(decoded),*out=lp_frame_alloc(),*extra=NULL;int rc=-1,stage=1;
 if(!in||!out)goto fail;
 int count=in->nb_samples,channels=in->ch_layout.nb_channels,rate=in->sample_rate;
 uint64_t layout=in->ch_layout.u.mask;
 if(count<=0||count>262144||channels<=0||channels>8||rate<=0||rate>384000||in->ch_layout.order!=AV_CHANNEL_ORDER_NATIVE||!layout)goto fail;
 stage=2;if(lp_graph&&(lp_built_profile!=lp_profile||lp_context!=context||lp_format!=in->format||lp_rate!=rate||lp_channels!=channels||lp_layout!=layout))lp_reset();
 if(!lp_graph){rc=lp_build(in);if(rc<0)goto fail;lp_context=context;}
 // Internal filter timestamps are microseconds; restore the decoder's time domain before returning.
 in->pts=AV_NOPTS_VALUE;in->pkt_dts=AV_NOPTS_VALUE;in->duration=0;in->time_base=(AVRational){1,1000000};
 stage=3;if((rc=lp_writable(in))<0||(rc=lp_add(lp_source,in,0))<0||(rc=lp_get(lp_sink,out))<0)goto fail;
 stage=4;if(out->format!=AV_SAMPLE_FMT_DBL||out->nb_samples!=count||out->sample_rate!=rate||out->ch_layout.nb_channels!=channels||out->ch_layout.order!=AV_CHANNEL_ORDER_NATIVE||out->ch_layout.u.mask!=layout||!out->data[0])goto fail;
 // The original filter copies properties. Verify side data rather than silently dropping it.
 if(out->nb_side_data!=decoded->nb_side_data)goto fail;
 for(int i=0;i<decoded->nb_side_data;i++){
  AVFrameSideData *a=decoded->side_data[i],*b=out->side_data[i];
  if(a->type!=b->type||a->size!=b->size||memcmp(a->data,b->data,a->size))goto fail;
 }
 stage=5;extra=lp_frame_alloc();if(!extra)goto fail;
 rc=lp_get(lp_sink,extra);if(rc!=AVERROR(EAGAIN))goto fail;
 out->pts=decoded->pts;out->pkt_dts=decoded->pkt_dts;out->duration=decoded->duration;
 out->time_base=decoded->time_base;out->best_effort_timestamp=decoded->best_effort_timestamp;
 lp_unref(decoded);lp_move(decoded,out);
 lp_frame_free(&in);lp_frame_free(&out);lp_frame_free(&extra);lp_frames++;lp_samples+=count;return 0;
fail:
 if(!lp_error)__android_log_print(ANDROID_LOG_ERROR,"OwnedLoudness","decoder stage=%d rc=%d fmt=%d n=%d rate=%d channels=%d",stage,rc,decoded->format,decoded->nb_samples,decoded->sample_rate,decoded->ch_layout.nb_channels);
 lp_error=rc<0?rc:AVERROR(EINVAL);lp_frame_free(&in);lp_frame_free(&out);lp_frame_free(&extra);lp_reset();return lp_error;
}

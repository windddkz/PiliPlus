#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdatomic.h>
#include <libavcodec/codec.h>
#include <libavutil/opt.h>
#include "slot_pair.h"
static uintptr_t player_base;static int player_matches;
static int locate_player(struct dl_phdr_info *i,size_t n,void *p){(void)n;(void)p;const char *s=strrchr(i->dlpi_name,'/');s=s?s+1:i->dlpi_name;if(!strcmp(s,"libijk.so")){player_base=i->dlpi_addr;player_matches++;}return 0;}
static int matches_player(void *find){player_base=0;player_matches=0;dl_iterate_phdr(locate_player,NULL);return player_matches==1&&*(void **)(player_base+0x2ca600)==find;}
static void fail(JNIEnv *e,const char *s){(*e)->ThrowNew(e,(*e)->FindClass(e,"java/lang/IllegalStateException"),s);}
struct runtime {void *lib,*ext;};
static int owned_handles;
static void close_runtime(struct runtime *r){if(r->ext){dlclose(r->ext);r->ext=NULL;owned_handles--;}if(r->lib){dlclose(r->lib);r->lib=NULL;owned_handles--;}}
static int open_runtime(struct runtime *r){
 r->lib=dlopen("libijkffmpeg.so",RTLD_NOW|RTLD_LOCAL);if(r->lib)owned_handles++;
 r->ext=dlopen("libijkffext.so",RTLD_NOW|RTLD_LOCAL);if(r->ext)owned_handles++;
 if(!r->lib||!r->ext){close_runtime(r);return -1;}
 unsigned (*version)(void)=dlsym(r->lib,"avcodec_version");void *find=dlsym(r->lib,"avcodec_find_decoder");
 if(!version||version()!=0x3d1365||!find||!matches_player(find)){close_runtime(r);return -1;}return 0;
}
JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_registerDsp(JNIEnv *e,jclass cls){
 (void)cls;struct runtime runtime={0};if(open_runtime(&runtime)){fail(e,"Locked player runtime unavailable");return NULL;}
 const char *symbols[][3]={{"ijkav_register_ijkeac3_decoder","ijkimp_ff_ijkeac3_decoder","ff_ijkeac3_decoder"},{"ijkav_register_ijkac3_decoder","ijkimp_ff_ijkac3_decoder","ff_ijkac3_decoder"},{"ijkav_register_ijkloudnorm_filter","ijkimp_ff_af_ijkloudnorm_filter","ff_af_ijkloudnorm"}};
 int (*reg[3])(const void *,int);const void *impl[3];void *target[3];int sizes[3]={192,192,104};
 // Resolve and validate the whole set before mutating any original descriptor.
 for(int i=0;i<3;i++){
  reg[i]=dlsym(runtime.lib,symbols[i][0]);impl[i]=dlsym(runtime.ext,symbols[i][1]);target[i]=dlsym(runtime.lib,symbols[i][2]);
  if(!reg[i]||!impl[i]||!target[i]){close_runtime(&runtime);fail(e,"Original DSP registration symbols absent");return NULL;}
 }
 unsigned char saved[3][192];for(int i=0;i<3;i++)memcpy(saved[i],target[i],sizes[i]);
 for(int i=0;i<3;i++)if(reg[i](impl[i],sizes[i])||memcmp(target[i],impl[i],sizes[i])){
  int restored=1;for(int j=0;j<=i;j++)if(reg[j](saved[j],sizes[j])||memcmp(target[j],saved[j],sizes[j]))restored=0;
  if(!restored)(*e)->FatalError(e,"DSP descriptor rollback failed in owned process");
  close_runtime(&runtime);fail(e,"Original DSP registration failed and was rolled back");return NULL;
 }
 close_runtime(&runtime);return (*e)->NewStringUTF(e,"{\"decoder_registered_now\":true,\"filter_registered_now\":true,\"runtime_matches_player\":true}");
}

static const AVCodec *(*original_find)(int);
static const AVCodec *dolby_ac3,*dolby_eac3;
static atomic_uint routed_ac3,routed_eac3,delegated;
static int routing;
static _Atomic(const char *) selected_audio_codec;
static struct runtime route_runtime;
static int (*original_open)(void *,const AVCodec *,void **);
static int (*set_ptr)(void **,const char *,intptr_t,int);
static int (*set_int)(void **,const char *,int64_t,int);
static atomic_uint configured,open_failures;
// Borrowed until decoder release; controls change by reopening the single owned player.
static int32_t exec_params[9];
static int routed_open(void *ctx,const AVCodec *codec,void **options){
 if(codec==dolby_ac3||codec==dolby_eac3){
  if(!options||set_int(options,"ijkeac3type",0,0)<0||set_ptr(options,"ijkeac3par",(intptr_t)exec_params,0)<0){atomic_fetch_add(&open_failures,1);return -22;}
  atomic_fetch_add(&configured,1);
 }
 int rc=original_open(ctx,codec,options);if(rc<0)atomic_fetch_add(&open_failures,1);return rc;
}
static const AVCodec *routed_find(int id){
 const AVCodec *codec;
 if(id==86019){atomic_fetch_add(&routed_ac3,1);codec=dolby_ac3;}
 else if(id==86056){atomic_fetch_add(&routed_eac3,1);codec=dolby_eac3;}
 else {atomic_fetch_add(&delegated,1);codec=original_find(id);}
 if(codec&&codec->type==AVMEDIA_TYPE_AUDIO)atomic_store(&selected_audio_codec,codec->name);
 return codec;
}
static uintptr_t original_slots[2],routed_slots[2];
static int route_pair(int install,int (*protect)(void *,size_t,int)){
 return patch_pair((uintptr_t *)(player_base+0x2ca600),(uintptr_t *)(player_base+0x2ca5f0),install?original_slots:routed_slots,install?routed_slots:original_slots,protect);
}
static const char *prepare_route(int endpoint,int virtualizer){
 if(open_runtime(&route_runtime))return "Locked player runtime unavailable";
 void *lib=route_runtime.lib,*ext=route_runtime.ext;
 original_find=dlsym(lib,"avcodec_find_decoder");const AVCodec *(*named)(const char *)=dlsym(lib,"avcodec_find_decoder_by_name");
 const void *ac3=dlsym(ext,"ijkimp_ff_ijkac3_decoder"),*eac3=dlsym(ext,"ijkimp_ff_ijkeac3_decoder");
 original_open=dlsym(lib,"avcodec_open2");set_ptr=dlsym(lib,"av_dict_set_intptr");set_int=dlsym(lib,"av_dict_set_int");
 if(!named||!ac3||!eac3||!original_open||!set_ptr||!set_int)return "Original option bridge unavailable";
 dolby_ac3=named("ijkac3");dolby_eac3=named("ijkeac3");
 if(!dolby_ac3||!dolby_eac3||dolby_ac3->id!=86019||dolby_eac3->id!=86056||memcmp(dolby_ac3,ac3,192)||memcmp(dolby_eac3,eac3,192))return "Original Dolby decoders not registered";
 int32_t values[9]={endpoint,virtualizer,6,-14,65535,-32,0,0,0};memcpy(exec_params,values,sizeof(values));
 original_slots[0]=(uintptr_t)original_find;original_slots[1]=(uintptr_t)original_open;
 routed_slots[0]=(uintptr_t)routed_find;routed_slots[1]=(uintptr_t)routed_open;
 atomic_store(&selected_audio_codec,NULL);atomic_store(&configured,0);atomic_store(&open_failures,0);atomic_store(&routed_ac3,0);atomic_store(&routed_eac3,0);atomic_store(&delegated,0);
 return NULL;
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_startDspRoute(JNIEnv *e,jclass cls,jint endpoint,jint virtualizer){
 (void)cls;if((endpoint!=1&&endpoint!=2)||(virtualizer!=0&&virtualizer!=1)){fail(e,"Invalid owned Dolby test controls");return;}if(routing){fail(e,"DSP route already active");return;}
 const char *error=prepare_route(endpoint,virtualizer);
 if(error){close_runtime(&route_runtime);fail(e,error);return;}
 int rc=route_pair(1,mprotect);
 if(rc==-2)(*e)->FatalError(e,"DSP install page protection rollback failed in owned process");
 if(rc){close_runtime(&route_runtime);fail(e,"DSP pair install failed without pointer changes");return;}
 routing=1;
}
JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_finishDspRoute(JNIEnv *e,jclass cls){
 (void)cls;if(!routing){fail(e,"DSP route is not active");return NULL;}
 int rc=route_pair(0,mprotect);if(rc==-1)rc=route_pair(0,mprotect);
 if(rc)(*e)->FatalError(e,"DSP restore failed in owned process; refusing stale callbacks");
 routing=0;close_runtime(&route_runtime);char result[300];snprintf(result,sizeof(result),"{\"ac3_calls\":%u,\"eac3_calls\":%u,\"delegated_calls\":%u,\"slot_restored\":true,\"configured_decoders\":%u,\"open_failures\":%u,\"endpoint\":%d,\"virtualizer\":%d,\"owned_handles\":%d}",atomic_load(&routed_ac3),atomic_load(&routed_eac3),atomic_load(&delegated),atomic_load(&configured),atomic_load(&open_failures),exec_params[0],exec_params[1],owned_handles);
 return (*e)->NewStringUTF(e,result);
}

JNIEXPORT jstring JNICALL Java_dev_piliplus_dspbridge_DspBridge_getDspState(JNIEnv *e,jclass cls){
 (void)cls;if(!routing){fail(e,"DSP route is not active");return NULL;}
 const char *codec=atomic_load(&selected_audio_codec);char result[256];
 snprintf(result,sizeof(result),"{\"configured_decoders\":%u,\"open_failures\":%u,\"endpoint\":%d,\"audio_codec\":\"%.64s\"}",atomic_load(&configured),atomic_load(&open_failures),exec_params[0],codec?codec:"");
 return (*e)->NewStringUTF(e,result);
}

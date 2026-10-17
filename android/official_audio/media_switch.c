#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <limits.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include "media_switch_guards.h"
#define MS_FIELD(p,o,T) (*(T *)((char *)(p)+(o)))
static uintptr_t switch_base;
static int switch_installed;
static int (*switch_read)(void *,void *),(*switch_index)(void *,int64_t);
static int64_t (*switch_time)(void *,int),(*switch_rescale)(int64_t,uint64_t,uint64_t);
static int (*switch_seek)(void *,int,int64_t,int);
static void *(*switch_dict)(void *,const char *,const void *,int);
static int (*switch_lock)(void *),(*switch_unlock)(void *);
static void (*switch_handle)(void *,void *,unsigned);

static int switch_pending(void *state){
 void *mutex=MS_FIELD(state,0x68,void *);
 if(switch_lock(mutex))abort();
 int result=MS_FIELD(state,0,int)!=0||MS_FIELD(state,0x78,int)!=0;
 if(switch_unlock(mutex))abort();
 return result;
}
static void switch_consume(void *format,unsigned media){
 void *priv=MS_FIELD(format,0x18,void *),*state=(char *)priv+0x748+media*200;
 void *mutex=MS_FIELD(state,0x68,void *);
 unsigned char request[32] __attribute__((aligned(16)));
 if(switch_lock(mutex))abort();
 if(MS_FIELD(state,0,int)!=1){if(switch_unlock(mutex))abort();return;}
 memcpy(request,(char *)state+8,sizeof request);MS_FIELD(state,0,int)=2;
 if(switch_unlock(mutex))abort();
 switch_handle(format,request,media);
 if(switch_lock(mutex))abort();
 // A newer request replaces the dispatch marker; completing this request must not erase it.
 if(MS_FIELD(state,0,int)==2)MS_FIELD(state,0,int)=0;
 if(switch_unlock(mutex))abort();
}
static uintptr_t switch_dispatch(void *a,uintptr_t b,const void *c,int flags){
 uintptr_t caller=(uintptr_t)__builtin_return_address(0)-switch_base;
 if(caller==0x1b5314||caller==0x1b535c)return (uintptr_t)switch_pending(a);
 if(caller==0x1b91e4){if(b!=0)abort();switch_consume(a,0);return 0;}
 if(caller==0x1b5b9c||caller==0x1b6250){if(b!=1)abort();switch_consume(a,1);return 0;}
 return (uintptr_t)switch_dict(a,(const char *)b,c,flags);
}
static int switch_mov(void *context){
 void *format=context?MS_FIELD(context,8,void *):NULL;
 const char *name=format?MS_FIELD(format,0,const char *):NULL;
 return name&&!strcmp(name,"mov,mp4,m4a,3gp,3g2,mj2");
}
static int switch_packet(void *context,void *packet){
 uintptr_t caller=(uintptr_t)__builtin_return_address(0);
 int rc=switch_read(context,packet);
 if(caller!=switch_base+0x1b6220||rc<0||!packet||!switch_mov(context))return rc;
 int stream=MS_FIELD(packet,0x24,int);unsigned count=MS_FIELD(context,0x2c,unsigned);
 void **streams=MS_FIELD(context,0x30,void **);
 if(stream<0||(unsigned)stream>=count||!streams||!streams[stream])return rc;
 void *st=streams[stream],*codec=MS_FIELD(st,0x10,void *),*mov=MS_FIELD(context,0x18,void *);
 if(!codec||MS_FIELD(codec,0,int)!=0||!mov)return rc;
 int num=MS_FIELD(st,0x20,int),den=MS_FIELD(st,0x24,int),segments=MS_FIELD(mov,0x10c,int);
 int64_t raw_dts=MS_FIELD(packet,0x10,int64_t),raw_pts=MS_FIELD(packet,8,int64_t);
 if(num<=0||den<=0||raw_dts<0||raw_pts<0||segments<=0||segments>65535||!MS_FIELD(mov,0x110,void *))return rc;
 uint64_t tb=((uint64_t)(unsigned)den<<32)|(unsigned)num;
 int64_t dts=switch_rescale(raw_dts,tb,UINT64_C(0xf424000000001));if(dts<0)return rc;
 int index=switch_index(context,dts);int64_t before=MS_FIELD(packet,0x68,int64_t);
 if(index>=0&&index<segments-1&&before>=0&&before<segments&&before!=index){
  int64_t lo=switch_time(context,index),hi=switch_time(context,index+1);
  if(lo>=0&&lo<=dts&&dts<hi)MS_FIELD(packet,0x68,int64_t)=index;
 }
 return rc;
}
static int switch_seek_frame(void *context,int stream,int64_t timestamp,int flags){
 uintptr_t caller=(uintptr_t)__builtin_return_address(0);
 if(caller==switch_base+0x1b9ce4&&stream==-1&&flags==1&&timestamp>=1000000&&switch_mov(context)&&MS_FIELD(context,0x2c,unsigned)==1){
  void **streams=MS_FIELD(context,0x30,void **);void *st=streams?streams[0]:NULL;
  void *codec=st?MS_FIELD(st,0x10,void *):NULL,*sc=st?MS_FIELD(st,0x18,void *):NULL;
  int num=st?MS_FIELD(st,0x20,int):0,den=st?MS_FIELD(st,0x24,int):0;
  if(codec&&MS_FIELD(codec,0,int)==0&&sc&&num>0&&den>0){
   // Undo the guarded video's SAP+1s only when the original MOV table proves the exact boundary.
   int64_t sap=timestamp-1000000;int index=switch_index(context,sap);
   if(index>=0&&switch_time(context,index)==sap){
    int64_t minimum=MS_FIELD(sc,0xc8,int64_t),delta,adjusted;
    int shift=MS_FIELD(sc,0x130,int);
    uint64_t tb=((uint64_t)(unsigned)den<<32)|(unsigned)num;
    int64_t ticks=switch_rescale(sap,UINT64_C(0xf424000000001),tb);
    if(minimum!=INT64_MIN&&ticks!=INT64_MIN&&!__builtin_add_overflow(minimum,(int64_t)shift,&delta)&&!__builtin_add_overflow(ticks,delta,&adjusted)&&adjusted>=0){stream=0;timestamp=adjusted;}
   }
  }
 }
 return switch_seek(context,stream,timestamp,flags);
}
static int install_media_switch(void){
 if(switch_installed)return -1;
 void *ext=dlopen("libijkffext.so",RTLD_NOW|RTLD_LOCAL),*ff=dlopen("libijkffmpeg.so",RTLD_NOW|RTLD_LOCAL);
 Dl_info ei={0},fi={0};void *entry=ext?dlsym(ext,"ijkdash_switch_context"):NULL;
 switch_read=ff?dlsym(ff,"av_read_frame"):NULL;switch_seek=ff?dlsym(ff,"av_seek_frame"):NULL;
 switch_index=ff?dlsym(ff,"mov_frag_get_frag_index_with_timestamp"):NULL;switch_time=ff?dlsym(ff,"mov_frag_get_timestamp_with_index"):NULL;
 switch_rescale=ff?dlsym(ff,"av_rescale_q"):NULL;switch_dict=ff?dlsym(ff,"av_dict_get"):NULL;
 switch_lock=ext?dlsym(ext,"SDL_LockMutex"):NULL;switch_unlock=ext?dlsym(ext,"SDL_UnlockMutex"):NULL;
 if(!entry||!switch_read||!switch_seek||!switch_index||!switch_time||!switch_rescale||!switch_dict||!switch_lock||!switch_unlock||!dladdr(entry,&ei)||!dladdr((void *)switch_index,&fi))goto fail;
 switch_base=(uintptr_t)ei.dli_fbase;uintptr_t ffbase=(uintptr_t)fi.dli_fbase;
 if((uintptr_t)entry-switch_base!=0x1b9654||(uintptr_t)switch_index-ffbase!=0x9a2fc0)goto fail;
 for(unsigned i=0;i<sizeof switch_guards/sizeof switch_guards[0];i++)
  if(memcmp((void *)((switch_guards[i].ff?ffbase:switch_base)+switch_guards[i].offset),switch_guards[i].bytes,switch_guards[i].size))goto fail;
 uintptr_t *slots[]={(void *)(switch_base+0x426338),(void *)(switch_base+0x4263c0),(void *)(switch_base+0x426510)};
 uintptr_t before[]={(uintptr_t)switch_dict,(uintptr_t)switch_read,(uintptr_t)switch_seek};
 uintptr_t after[]={(uintptr_t)switch_dispatch,(uintptr_t)switch_packet,(uintptr_t)switch_seek_frame};
 long page=sysconf(_SC_PAGESIZE);if(page<=0)goto fail;void *address=(void *)((uintptr_t)slots[0]&~((uintptr_t)page-1));
 for(unsigned i=0;i<3;i++)if((void *)((uintptr_t)slots[i]&~((uintptr_t)page-1))!=address||__atomic_load_n(slots[i],__ATOMIC_ACQUIRE)!=before[i])goto fail;
 switch_handle=(void *)(switch_base+0x1b92b0);
 if(mprotect(address,(size_t)page,PROT_READ|PROT_WRITE))goto fail;
 for(unsigned i=0;i<3;i++)__atomic_store_n(slots[i],after[i],__ATOMIC_RELEASE);
 if(mprotect(address,(size_t)page,PROT_READ)){
  for(unsigned i=0;i<3;i++)__atomic_store_n(slots[i],before[i],__ATOMIC_RELEASE);
  if(mprotect(address,(size_t)page,PROT_READ))abort();
  goto fail;
 }
 // Imports and background preloads retain the bridge until the player process exits.
 switch_installed=1;__android_log_print(ANDROID_LOG_INFO,"PiliMediaSwitch","installed guarded packet/seek/pending adapters");return 0;
fail:
 if(ext)dlclose(ext);if(ff)dlclose(ff);return -1;
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installMediaSwitch(JNIEnv *env,jclass cls){
 (void)cls;
 if(getuid()%100000<10000||install_media_switch())
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original DASH switch runtime mismatch");
}

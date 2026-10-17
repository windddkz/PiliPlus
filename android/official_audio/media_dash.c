#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/mman.h>
#include <android/log.h>
#include "media_dash.h"
#include "media_dash_guards.h"
static uintptr_t dash_base;
static void (*dash_original_dump)(void *);
extern void media_dash_dump_entry(void *);
void media_dash_dump(void *source,uintptr_t stack,uintptr_t caller){
 if(caller==dash_base+0x1b3e58){
  // The guarded adapter retains this vector through its following clone and cleanup.
  int count=dash_add_av1(source,(void *)(stack+0x80),(DashCodec)(dash_base+0x180b54),(DashBind)(dash_base+0x1b2dd0));
  if(count<0){__android_log_print(ANDROID_LOG_ERROR,"PiliMediaDash","Invalid AV1 stream container");abort();}
  if(count)__android_log_print(ANDROID_LOG_INFO,"PiliMediaDash","AV1 streams=%d codec=2",count);
 }
 dash_original_dump(source);
}
static int install_media_dash(void){
 static int installed;
 if(installed)return -1;
 void *ijk=dlopen("libijk.so",RTLD_NOW|RTLD_LOCAL);Dl_info info={0};
 void *entry=ijk?dlsym(ijk,"JNI_OnLoad"):NULL;
 if(!entry||!dladdr(entry,&info))goto fail;
 uintptr_t base=(uintptr_t)info.dli_fbase;
 for(size_t i=0;i<sizeof guards/sizeof guards[0];++i)
  if(memcmp((void *)(base+guards[i].offset),guards[i].bytes,guards[i].length))goto fail;
 uintptr_t *slot=(void *)(base+0x2caa68);
 if(!dladdr((void *)*slot,&info)||!info.dli_sname||strcmp(info.dli_sname,"DashDataSource_dump")||
    !info.dli_fname||!strstr(info.dli_fname,"/libijkffext.so")||
    *slot!=(uintptr_t)info.dli_fbase+0x1b6f28||memcmp((void *)*slot,dump_guard,sizeof dump_guard))goto fail;
 long page=sysconf(_SC_PAGESIZE);if(page<=0)goto fail;
 void *address=(void *)((uintptr_t)slot&~((uintptr_t)page-1));
 if(mprotect(address,(size_t)page,PROT_READ|PROT_WRITE))goto fail;
 dash_base=base;dash_original_dump=(void *)*slot;
 __atomic_store_n(slot,(uintptr_t)media_dash_dump_entry,__ATOMIC_RELEASE);
 if(mprotect(address,(size_t)page,PROT_READ))abort();
 // Background preloads outlive individual graph sessions; retain imports until process exit.
 installed=1;return 0;
fail:
 if(ijk)dlclose(ijk);return -1;
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installMediaDash(JNIEnv *env,jclass cls){
 (void)cls;
 if(getuid()%100000<10000||install_media_dash())
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original DASH adapter runtime mismatch");
}

#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include "media_index_guards.h"
#define FIELD(p,o,T) (*(T *)((char *)(p)+(o)))
static uintptr_t ext_base;
static int (*original)(void *,void *,int *,int64_t *);
static int installed;
static int checked(void *context,void *options,int *skipped,int64_t *duration) {
 uintptr_t caller=(uintptr_t)__builtin_return_address(0);
 int rc=original(context,options,skipped,duration);
 if(caller!=ext_base+0x1b8b5c)return rc;
 if(rc<0)return rc;
 void *format=context?FIELD(context,8,void *):NULL;
 const char *name=format?FIELD(format,0,const char *):NULL;
 void *mov=context?FIELD(context,0x18,void *):NULL;
 int count=0;void *table=NULL;
 if(name&&!strcmp(name,"mov,mp4,m4a,3gp,3g2,mj2")&&mov){count=FIELD(mov,0x10c,int);table=FIELD(mov,0x110,void *);}
 
 if(count>0 && count<=65535 && table)return rc;
 return -(int)0x41444e49; // AVERROR_INVALIDDATA; original DASH caller propagates this failure.
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installMediaInspection(JNIEnv *env,jclass cls) {
 (void)cls;
 void *ext=dlopen("libijkffext.so",RTLD_NOW|RTLD_LOCAL),*ff=dlopen("libijkffmpeg.so",RTLD_NOW|RTLD_LOCAL);
 void *symbol=ext?dlsym(ext,"ijk_try_find_stream_info"):NULL;void *timestamp=ff?dlsym(ff,"mov_frag_get_timestamp_with_index"):NULL;
 Dl_info ei,fi;
 if(getuid()%100000<10000||installed||!symbol||!timestamp||!dladdr(symbol,&ei)||!dladdr(timestamp,&fi))goto fail;
 ext_base=(uintptr_t)ei.dli_fbase;
 for(unsigned i=0;i<sizeof guards/sizeof guards[0];i++){
  uintptr_t base=guards[i].ffmpeg?(uintptr_t)fi.dli_fbase:ext_base;
  if(memcmp((void *)(base+guards[i].offset),guards[i].bytes,guards[i].length))goto fail;
 }
 uintptr_t *slot=(uintptr_t *)(ext_base+0x4264c0);
 if(__atomic_load_n(slot,__ATOMIC_ACQUIRE)!=(uintptr_t)symbol)goto fail;
 long page=sysconf(_SC_PAGESIZE);if(page<=0)goto fail;
 void *address=(void *)((uintptr_t)slot&~((uintptr_t)page-1));
 original=(void *)symbol;
 if(mprotect(address,(size_t)page,PROT_READ|PROT_WRITE))goto fail;
 __atomic_store_n(slot,(uintptr_t)checked,__ATOMIC_RELEASE);
 if(mprotect(address,(size_t)page,PROT_READ)){
  __atomic_store_n(slot,(uintptr_t)symbol,__ATOMIC_RELEASE);
  if(mprotect(address,(size_t)page,PROT_READ))_exit(120);
  goto fail;
 }
 installed=1;return; // Keep the locked runtime loaded while foreground and preloaded items share this hook.
fail:
 if(ext)dlclose(ext);if(ff)dlclose(ff);
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original DASH runtime mismatch");
}

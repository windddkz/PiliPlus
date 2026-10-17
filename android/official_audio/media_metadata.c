#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include "media_metadata_guards.h"

JNIEXPORT jint JNICALL Java_dev_piliplus_dspbridge_DspBridge_classifyHdr(JNIEnv *env,jclass cls,jlong handle) {
 (void)cls;
 void *lib=dlopen("libijk.so",RTLD_NOW|RTLD_LOCAL);
 void *symbol=lib?dlsym(lib,"JNI_OnLoad"):NULL;Dl_info info;
 void *item=NULL,*director=NULL;int result=-1;
 if(getuid()%100000<10000||!symbol||!handle||!dladdr(symbol,&info))goto fail;
 uintptr_t base=(uintptr_t)info.dli_fbase;
 for(unsigned i=0;i<sizeof guards/sizeof guards[0];i++)
  if(memcmp((void *)(base+guards[i].offset),guards[i].bytes,guards[i].length))goto fail;
 void (*retain)(void *,void *)=(void *)(base+0x1f3848);
 void (*release)(void *,void *)=(void *)(base+0x1f38dc);
 void **(*getDirector)(void *)=(void *)(base+0x1604fc);
 void ***(*getTracks)(void *)=(void *)(base+0x192eb0);
 int (*getType)(void *)=(void *)(base+0x13cf30);
 unsigned char (*classify)(void *)=(void *)(base+0x126e20);
 item=*(void **)(uintptr_t)handle;
 if(!item)goto fail;
 retain(item,&item);
 director=*getDirector(item);
 if(!director)goto cleanup;
 retain(director,&director);
 // Match getPlayerMediaInfo's ready-state check before consulting the stable track list.
 int state=*(int *)((uintptr_t)director+0x14);
 if(state==2 || state==3) {
  void ***tracks=getTracks(director);
  if(!tracks || (uintptr_t)tracks[1]<(uintptr_t)tracks[0] ||
     ((uintptr_t)tracks[1]-(uintptr_t)tracks[0])%sizeof(void *) ||
     ((uintptr_t)tracks[1]-(uintptr_t)tracks[0])/sizeof(void *)>64)goto cleanup;
  for(void **cursor=tracks[0];cursor!=tracks[1];cursor++) {
   if(!*cursor)continue;
   void *parameters=(void *)((uintptr_t)*cursor+0x88);
   if(!*(void **)parameters)continue;
   if(getType(*(void **)parameters)==0) {
    if(result!=-1){result=-1;break;}
    result=classify(parameters);
   }
  }
 }
cleanup:
 if(director)release(director,&director);
 if(item)release(item,&item);
 dlclose(lib);return result;
fail:
 if(lib)dlclose(lib);
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original metadata runtime or handle mismatch");return -1;
}

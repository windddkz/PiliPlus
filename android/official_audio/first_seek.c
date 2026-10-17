#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <pthread.h>
#ifndef FIRST_SEEK_TEST
#include <jni.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdlib.h>
#include <android/log.h>
#include "first_seek_guards.h"
#endif

#define FIRST_SEEK_CAPACITY 64
static pthread_mutex_t fresh_lock=PTHREAD_MUTEX_INITIALIZER;
static void *fresh_contexts[FIRST_SEEK_CAPACITY];
// Only unread contexts are retained; the first read/seek or close consumes eligibility.
static int take_fresh(void *context) {
 int found=0;pthread_mutex_lock(&fresh_lock);
 for(unsigned i=0;i<FIRST_SEEK_CAPACITY;i++)if(context && fresh_contexts[i]==context){fresh_contexts[i]=NULL;found=1;break;}
 pthread_mutex_unlock(&fresh_lock);return found;
}
static int add_fresh(void *context) {
 int empty=-1,added=0;pthread_mutex_lock(&fresh_lock);
 for(unsigned i=0;i<FIRST_SEEK_CAPACITY;i++){
  if(context && fresh_contexts[i]==context){fresh_contexts[i]=NULL;goto done;}
  if(!fresh_contexts[i] && empty<0)empty=(int)i;
 }
 if(context && empty>=0){fresh_contexts[empty]=context;added=1;}
 done:pthread_mutex_unlock(&fresh_lock);return added;
}
static int zero_seek(int stream,int64_t min,int64_t position,int64_t max,int flags) {
 return stream==-1 && min<=0 && position==0 && max>=0 && flags==0;
}
static int (*original_open)(void **,const char *,void *,void **);
static void (*original_close)(void **);
static int (*original_read)(void *,void *);
static int (*original_seek)(void *,int,int64_t,int64_t,int64_t,int);
static void *(*dict_pointer)(const void *,const char *);
#ifndef FIRST_SEEK_TEST
static uintptr_t runtime_base;
#endif
static int plain_vod(const void *options) {
 // Official adapter 0x1b3b98 inserts the retained native MediaAsset pointer.
 struct Asset {void **vtable;int scheme,format;} *asset=dict_pointer(options,"media-asset");
 if(!asset || asset->scheme!=1 || asset->format!=1 || !asset->vtable || !asset->vtable[2])return 0;
#ifndef FIRST_SEEK_TEST
 Dl_info info;
 if(!dladdr(asset->vtable[2],&info) || (uintptr_t)info.dli_fbase!=runtime_base)return 0;
#endif
 // Same HasDrm virtual call used by the official ParseDrmOptions; no DRM processing.
 unsigned char (*has_drm)(void *)=(void *)asset->vtable[2];
 return !has_drm(asset);
}
static int first_open(void **context,const char *url,void *format,void **options) {
 if(context)take_fresh(*context);
 int allowed=options && *options && plain_vod(*options);
 int rc=original_open(context,url,format,options);
#ifdef FIRST_SEEK_RESEARCH
 __android_log_print(ANDROID_LOG_INFO,"PiliFirstSeek","open plain_vod=%d rc=%d ctx=%p",allowed,rc,context?*context:NULL);
#endif
 if(rc>=0 && allowed && context && *context){
  const char **input=*(const char ***)((char *)*context+8);
  if(input && *input && !strcmp(*input,"ijkdash"))add_fresh(*context);
 }
 return rc;
}
static void first_close(void **context){if(context)take_fresh(*context);original_close(context);}
static int first_read(void *context,void *packet){take_fresh(context);return original_read(context,packet);}
static int first_seek(void *context,int stream,int64_t min,int64_t position,int64_t max,int flags){
 if(take_fresh(context) && zero_seek(stream,min,position,max,flags)){
#ifndef FIRST_SEEK_TEST
  __android_log_print(ANDROID_LOG_INFO,"PiliFirstSeek","fresh VOD zero seek omitted ctx=%p",context);
#endif
  return 0;
 }
 return original_seek(context,stream,min,position,max,flags);
}

#ifndef FIRST_SEEK_TEST
static int installed;
static int install_first_seek(void){
 if(installed)return -1;
 void *ijk=dlopen("libijk.so",RTLD_NOW|RTLD_LOCAL),*ff=dlopen("libijkffmpeg.so",RTLD_NOW|RTLD_LOCAL);
 Dl_info info;void *entry=ijk?dlsym(ijk,"JNI_OnLoad"):NULL;
 if(!ijk||!ff||!entry||!dladdr(entry,&info))goto fail;
 uintptr_t base=(uintptr_t)info.dli_fbase;runtime_base=base;
 for(unsigned i=0;i<sizeof first_seek_guards/sizeof first_seek_guards[0];i++)
  if(memcmp((void *)(base+first_seek_guards[i].offset),first_seek_guards[i].bytes,first_seek_guards[i].length))goto fail;
 original_open=dlsym(ff,"avformat_open_input");original_close=dlsym(ff,"avformat_close_input");
 original_read=dlsym(ff,"av_read_frame");original_seek=dlsym(ff,"avformat_seek_file");
 dict_pointer=dlsym(ff,"av_dict_get_intptr");
 if(!original_open||!original_close||!original_read||!original_seek||!dict_pointer)goto fail;
 uintptr_t slots[]={base+0x2ca838,base+0x2ca658,base+0x2ca720,base+0x2ca848};
 void *expected[]={(void *)original_open,(void *)original_close,(void *)original_read,(void *)original_seek};
 void *next[]={(void *)first_open,(void *)first_close,(void *)first_read,(void *)first_seek};
 long page=sysconf(_SC_PAGESIZE);if(page<=0)goto fail;
 void *pages[4];unsigned count=0;
 for(unsigned i=0;i<4;i++){
  if(*(void **)slots[i]!=expected[i])goto fail;
  void *address=(void *)(slots[i]&~((uintptr_t)page-1));unsigned j=0;
  for(;j<count && pages[j]!=address;j++){}
  if(j==count)pages[count++]=address;
 }
 for(unsigned i=0;i<count;i++)if(mprotect(pages[i],(size_t)page,PROT_READ|PROT_WRITE)){
  for(unsigned j=0;j<i;j++)if(mprotect(pages[j],(size_t)page,PROT_READ))abort();
  goto fail;
 }
 for(unsigned i=0;i<4;i++)__atomic_store_n((void **)slots[i],next[i],__ATOMIC_RELEASE);
 // Installed imports retain this code and the locked runtime for the process lifetime.
 for(unsigned i=0;i<count;i++)if(mprotect(pages[i],(size_t)page,PROT_READ))abort();
 installed=1;return 0;
fail:
 if(ijk)dlclose(ijk);if(ff)dlclose(ff);return -1;
}
#ifdef FIRST_SEEK_RESEARCH
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){(void)vm;(void)reserved;return getuid()==2000 && !install_first_seek()?JNI_VERSION_1_6:JNI_ERR;}
#else
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installFirstSeek(JNIEnv *env,jclass cls){
 (void)cls;
 if(getuid()%100000<10000 || install_first_seek())
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original initial-seek runtime mismatch");
}
#endif
#endif

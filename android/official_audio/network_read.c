#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <unistd.h>
#include <sys/mman.h>
#ifndef NETWORK_READ_TEST
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#endif

static uintptr_t network_sdl;
static _Thread_local unsigned network_read_depth;
static int (*network_original_read)(void *,unsigned char *,int);
static int (*network_original_sleep)(useconds_t);
static atomic_int network_observed;

__attribute__((used,noinline,visibility("hidden")))
int network_sleep_detail(useconds_t delay,int error,uintptr_t caller){
 if(network_read_depth&&delay==1000&&error==0&&caller==network_sdl+0x1c7154){
  int saved=errno;
  if(!atomic_load_explicit(&network_observed,memory_order_relaxed)&&
     !atomic_exchange_explicit(&network_observed,1,memory_order_relaxed)){
#ifndef NETWORK_READ_TEST
   __android_log_print(ANDROID_LOG_INFO,"PiliNetworkRead","successful TLS read wait omitted");
#endif
  }
  errno=saved;return 0;
 }
 return network_original_sleep(delay);
}
extern int network_sleep_entry(useconds_t);
// The locked TLS caller retains SSL_get_error in w22 until after this call.
__asm__(".text\n.p2align 2\n.hidden network_sleep_entry\n.type network_sleep_entry,%function\nnetwork_sleep_entry:\nmov w1,w22\nmov x2,x30\nb network_sleep_detail\n.size network_sleep_entry,.-network_sleep_entry\n");
static int network_read(void *context,unsigned char *buffer,int size){
 network_read_depth++;
 int rc=network_original_read(context,buffer,size),saved=errno;
 network_read_depth--;errno=saved;return rc;
}
static int network_slot(uintptr_t *slot,uintptr_t expected,uintptr_t next,int (*protect)(void *,size_t,int)){
 long page=sysconf(_SC_PAGESIZE);
 if(page<=0||(uintptr_t)slot%sizeof(uintptr_t)||__atomic_load_n(slot,__ATOMIC_ACQUIRE)!=expected)return -1;
 void *address=(void *)((uintptr_t)slot&~((uintptr_t)page-1));
 if(protect(address,(size_t)page,PROT_READ|PROT_WRITE))return -1;
 __atomic_store_n(slot,next,__ATOMIC_RELEASE);
 if(!protect(address,(size_t)page,PROT_READ))return 0;
 __atomic_store_n(slot,expected,__ATOMIC_RELEASE);
 return protect(address,(size_t)page,PROT_READ)?-2:-1;
}
#ifndef NETWORK_READ_TEST
static int install_network_read(void){
 static int installed;
 if(installed)return -1;
 void *sdl=dlopen("libijksdl.so",RTLD_NOW|RTLD_LOCAL),*ext=dlopen("libijkffext.so",RTLD_NOW|RTLD_LOCAL);
 Dl_info si={0},ei={0};
 void *read=sdl?dlsym(sdl,"BilinetApi_Read"):NULL,*entry=ext?dlsym(ext,"ijknetwork_read"):NULL;
 if(!read||!entry||!dladdr(read,&si)||!dladdr(entry,&ei))goto fail;
 uintptr_t sb=(uintptr_t)si.dli_fbase,eb=(uintptr_t)ei.dli_fbase;
 const uint32_t call_guard[]={0x2a0003f6,0x52807d00,0x9402f894,0x121f7ac8};
 const uint32_t read_guard[]={0xa9bf4ffe,0xf9400013,0xb40001b3,0xaa1303e0};
 if((uintptr_t)read!=sb+0x1a3920||(uintptr_t)entry!=eb+0x1c2ac0||
    memcmp((void *)(sb+0x1c7148),call_guard,sizeof call_guard)||memcmp(read,read_guard,sizeof read_guard))goto fail;
 uintptr_t *read_slot=(void *)(eb+0x426970),*sleep_slot=(void *)(sb+0x2980f8);
 network_original_read=read;network_original_sleep=(void *)*sleep_slot;network_sdl=sb;
 if(!network_original_sleep||*read_slot!=(uintptr_t)read||
    (void *)network_original_sleep!=dlsym(RTLD_DEFAULT,"usleep"))goto fail;
 int rc=network_slot(read_slot,(uintptr_t)read,(uintptr_t)network_read,mprotect);
 if(rc==-2)abort();if(rc)goto fail;
 rc=network_slot(sleep_slot,(uintptr_t)network_original_sleep,(uintptr_t)network_sleep_entry,mprotect);
 if(rc==-2)abort();
 if(rc){
  if(network_slot(read_slot,(uintptr_t)network_read,(uintptr_t)read,mprotect))abort();
  goto fail;
 }
 // Preload readers outlive graph sessions; these imports remain until process exit.
 installed=1;return 0;
fail:
 if(sdl)dlclose(sdl);if(ext)dlclose(ext);return -1;
}
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installNetworkRead(JNIEnv *env,jclass cls,jboolean enabled){
 (void)cls;
 if(!enabled){__android_log_print(ANDROID_LOG_INFO,"PiliNetworkRead","enabled=0");return;}
 if(getuid()%100000<10000||install_network_read()){
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original network-read runtime mismatch");return;
 }
 __android_log_print(ANDROID_LOG_INFO,"PiliNetworkRead","enabled=1");
}
#endif

#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>
#include <limits.h>
#include "startup_choice_guards.h"
JNIEXPORT jint JNICALL Java_dev_piliplus_dspbridge_DspBridge_chooseStartup(JNIEnv *env,jclass type,jint origin,jint low,jint high,jintArray ids,jlongArray rates){
 (void)type;
 if(getuid()%100000<10000||origin<=0||low<=0||high<low||!ids||!rates)goto invalid;
 jsize count=(*env)->GetArrayLength(env,ids);
 if(count<1||count>20||(*env)->GetArrayLength(env,rates)!=count)goto invalid;
 jint qn[20];jlong bandwidth[20];
 (*env)->GetIntArrayRegion(env,ids,0,count,qn);if((*env)->ExceptionCheck(env))return -1;
 (*env)->GetLongArrayRegion(env,rates,0,count,bandwidth);if((*env)->ExceptionCheck(env))return -1;
 int has_origin=0;
 for(int i=0;i<count;i++){
  if(qn[i]<=0||bandwidth[i]<=0||bandwidth[i]>INT_MAX)goto invalid;
  for(int j=0;j<i;j++)if(qn[i]==qn[j])goto invalid;
  has_origin|=qn[i]==origin;
 }
 if(!has_origin)goto invalid;
 void *lib=dlopen("libijkffext.so",RTLD_NOW|RTLD_LOCAL);Dl_info info={0};
 void (*choose)(int,void *,size_t,void *,int)=lib?dlsym(lib,"GetAICenterOutput"):NULL;
 if(!choose||!dladdr((void *)choose,&info)||(uintptr_t)choose-(uintptr_t)info.dli_fbase!=0x1eb7e8)goto mismatch;
 for(unsigned i=0;i<sizeof startup_guards/sizeof startup_guards[0];i++)if(memcmp((char *)info.dli_fbase+startup_guards[i].offset,startup_guards[i].bytes,startup_guards[i].size))goto mismatch;
 // Use the original selector's exact input layout before creating the core's MediaAsset.
 uint64_t words[36]={0};unsigned char *input=(unsigned char *)words;
 memcpy(input+0xc,&high,4);memcpy(input+0x10,&low,4);memcpy(input+0x18,&count,4);
 memcpy(input+0x20,bandwidth,(size_t)count*8);memcpy(input+0xc0,qn,(size_t)count*4);memcpy(input+0x110,&origin,4);
 choose(4,input,sizeof words,NULL,0);
 int actual;memcpy(&actual,input,4);dlclose(lib);
 for(int i=0;i<count;i++)if(qn[i]==actual){
  __android_log_print(ANDROID_LOG_INFO,"PiliStartupChoice","original selector origin=%d actual=%d range=%d..%d candidates=%d",origin,actual,low,high,count);
  return actual;
 }
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original selector returned unavailable quality");return -1;
mismatch:
 if(lib)dlclose(lib);
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Startup selector runtime mismatch");return -1;
invalid:
 (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),"Invalid startup range or video candidates");return -1;
}

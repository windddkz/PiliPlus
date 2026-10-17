#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stdatomic.h>
#include <dlfcn.h>
#ifdef TLS_CRYPTO_TEST
#include <stdio.h>
#endif
#include <sys/mman.h>
#include <unistd.h>
#include <pthread.h>
#ifndef TLS_CRYPTO_TEST
#include <jni.h>
#include <android/log.h>
#endif

typedef int (*gcm_fn)(void *,unsigned char *,const unsigned char *,uint32_t,const unsigned char *,uint32_t,const unsigned char *,uint32_t,const unsigned char *,uint32_t);
static void *(*tls_ctx_new)(void);
static void (*tls_ctx_free)(void *);
static const void *(*tls_aes[3])(void);
static int (*tls_init)(void *,const void *,void *,const unsigned char *,const unsigned char *);
static int (*tls_update)(void *,unsigned char *,int *,const unsigned char *,int);
static int (*tls_final)(void *,unsigned char *,int *);
static int (*tls_ctrl)(void *,int,int,void *);
static void (*tls_cleanse)(void *,size_t);
static unsigned long (*tls_peek_error)(void);
static void (*tls_clear_error)(void);
#ifndef TLS_CRYPTO_TEST
static atomic_int tls_observed;
#endif
__attribute__((used,visibility("hidden"))) uintptr_t tls_gcm_resume,tls_gcm_entry,tls_ssl_read;
extern int tls_gcm_original(void *,unsigned char *,const unsigned char *,uint32_t,const unsigned char *,uint32_t,const unsigned char *,uint32_t,const unsigned char *,uint32_t);
extern void tls_crypto_dispatch(void);
// Tail dispatch preserves all ten GCM arguments; ordinary PLT calls put the dispatch address in x17.
__asm__(".text\n.p2align 2\n.hidden tls_crypto_dispatch\n.type tls_crypto_dispatch,%function\n"
 "tls_crypto_dispatch:\nadrp x16,tls_gcm_entry\nldr x16,[x16,:lo12:tls_gcm_entry]\ncmp x17,x16\nb.eq tls_gcm\n"
 "adrp x16,tls_ssl_read\nldr x16,[x16,:lo12:tls_ssl_read]\nbr x16\n.size tls_crypto_dispatch,.-tls_crypto_dispatch\n"
 ".hidden tls_gcm_original\n.type tls_gcm_original,%function\ntls_gcm_original:\n"
 "adrp x16,tls_gcm_resume\nldr x16,[x16,:lo12:tls_gcm_resume]\n"
 "sub sp,sp,#0x80\nstp x29,x30,[sp,#0x70]\nadd x29,sp,#0x70\nldr x9,[x29,#0x10]\nbr x16\n.size tls_gcm_original,.-tls_gcm_original\n");
#ifdef TLS_CRYPTO_TEST
static atomic_uint tls_test_fast,tls_test_fallback;
static atomic_int tls_test_alloc_failure;
#endif
static int tls_overlap(const void *a,const void *b,uint32_t len){
 uintptr_t x=(uintptr_t)a,y=(uintptr_t)b;return x!=y&&(x>y?x-y:y-x)<len;
}
__attribute__((used,noinline,visibility("hidden"))) int tls_gcm(void *aes,unsigned char *out,const unsigned char *in,uint32_t len,const unsigned char *iv,uint32_t ivlen,const unsigned char *tag,uint32_t taglen,const unsigned char *aad,uint32_t aadlen){
 int saved=errno,ok=0,n=0,tail=0,clean_errors=0;uint32_t rounds=0;unsigned char key[32]={0};unsigned char *scratch=NULL;void *ctx=NULL;
 // The fast path covers bounded TLS records; other contracts retain the original implementation.
 if(!aes||!out||!in||!iv||!tag||!len||len>16384||ivlen!=12||taglen!=16||aadlen>32||(!aad&&aadlen)||tls_overlap(out,in,len))goto fallback;
 memcpy(&rounds,(unsigned char *)aes+0xf0,sizeof rounds);
 if(rounds!=10&&rounds!=12&&rounds!=14)goto fallback;
 // Preserve any pre-existing errors belonging to the calling SSL implementation.
 if(tls_peek_error())goto fallback;
 clean_errors=1;
 unsigned keylen=(rounds-6)*4;
 for(unsigned i=0;i<keylen;i++)key[i]=((unsigned char *)aes)[i^3];
#ifdef TLS_CRYPTO_TEST
 if(tls_test_alloc_failure)goto fallback;
#endif
 scratch=malloc((size_t)len+16);ctx=tls_ctx_new();if(!scratch||!ctx)goto fallback;
 if(tls_init(ctx,tls_aes[(keylen-16)/8](),NULL,key,iv)!=1)goto fallback;
 if(aadlen&&tls_update(ctx,NULL,&n,aad,(int)aadlen)!=1)goto fallback;
 if(tls_update(ctx,scratch,&n,in,(int)len)!=1||n!=(int)len||tls_ctrl(ctx,0x11,(int)taglen,(void *)tag)!=1)goto fallback;
 if(tls_final(ctx,scratch+n,&tail)!=1||tail!=0)goto fallback;
 // EVP exposes unauthenticated plaintext before Final; never copy it to the caller.
 memcpy(out,scratch,len);ok=1;
fallback:
 tls_cleanse(key,sizeof key);
 if(scratch){tls_cleanse(scratch,(size_t)len+16);free(scratch);}
 if(ctx)tls_ctx_free(ctx);
 if(clean_errors)tls_clear_error();
 errno=saved;
 if(!ok){
#ifdef TLS_CRYPTO_TEST
  tls_test_fallback++;
#endif
  return tls_gcm_original(aes,out,in,len,iv,ivlen,tag,taglen,aad,aadlen);
 }
#ifdef TLS_CRYPTO_TEST
 tls_test_fast++;
#else
 if(!atomic_load_explicit(&tls_observed,memory_order_relaxed)&&!atomic_exchange_explicit(&tls_observed,1,memory_order_relaxed))
  __android_log_print(ANDROID_LOG_INFO,"PiliTlsCrypto","authenticated GCM fast path active");
#endif
 errno=saved;return 0;
}
static int install_tls_crypto(void){
 static pthread_mutex_t lock=PTHREAD_MUTEX_INITIALIZER;
 static int installed;int result=-1;void *sdl=NULL,*crypto=NULL,*ssl=NULL;
 pthread_mutex_lock(&lock);if(installed)goto done;
 sdl=dlopen("libijksdl.so",RTLD_NOW|RTLD_LOCAL);crypto=dlopen("libcrypto.so",RTLD_NOW|RTLD_LOCAL);ssl=dlopen("libssl.so",RTLD_NOW|RTLD_LOCAL);
 Dl_info si={0},ci={0};void *read=sdl?dlsym(sdl,"BilinetApi_Read"):NULL,*cipher=crypto?dlsym(crypto,"EVP_aes_128_gcm"):NULL;
 void *ssl_read=ssl?dlsym(ssl,"SSL_read"):NULL;
 if(!read||!cipher||!ssl_read||!dladdr(read,&si)||!dladdr(cipher,&ci))goto done;
 uintptr_t sb=(uintptr_t)si.dli_fbase,cb=(uintptr_t)ci.dli_fbase;
 const uint32_t guard[]={0x10000011,0x90000610,0xf9472210,0xd61f0200,0xb9401ba8,0xf81f03a0};
 const uint32_t plt[]={0x900000b0,0xf9472211,0x91390210,0xd61f0220};
 uint32_t *entry=(void *)(sb+0x1d664c);
 if((uintptr_t)read!=sb+0x1a3920||(uintptr_t)cipher!=cb+0xff97c||memcmp(entry,guard,sizeof guard)||memcmp((void *)(sb+0x282e30),plt,sizeof plt))goto done;
 uintptr_t *slot=(void *)(sb+0x296e40);
 if(__atomic_load_n(slot,__ATOMIC_ACQUIRE)!=(uintptr_t)ssl_read)goto done;
#define TLS_BIND(dst,name) do{*(void **)(&(dst))=dlsym(crypto,(name));if(!(dst))goto done;}while(0)
 TLS_BIND(tls_ctx_new,"EVP_CIPHER_CTX_new");TLS_BIND(tls_ctx_free,"EVP_CIPHER_CTX_free");
 TLS_BIND(tls_aes[0],"EVP_aes_128_gcm");TLS_BIND(tls_aes[1],"EVP_aes_192_gcm");TLS_BIND(tls_aes[2],"EVP_aes_256_gcm");
 TLS_BIND(tls_init,"EVP_DecryptInit_ex");TLS_BIND(tls_update,"EVP_DecryptUpdate");TLS_BIND(tls_final,"EVP_DecryptFinal_ex");TLS_BIND(tls_ctrl,"EVP_CIPHER_CTX_ctrl");TLS_BIND(tls_cleanse,"OPENSSL_cleanse");
 TLS_BIND(tls_peek_error,"ERR_peek_error");TLS_BIND(tls_clear_error,"ERR_clear_error");
#undef TLS_BIND
 long page=sysconf(_SC_PAGESIZE);if(page<=0)goto done;
 void *address=(void *)((uintptr_t)slot&~((uintptr_t)page-1));
 tls_gcm_resume=(uintptr_t)entry+16;tls_gcm_entry=(uintptr_t)entry;tls_ssl_read=(uintptr_t)ssl_read;
 if(mprotect(address,(size_t)page,PROT_READ|PROT_WRITE))goto done;
 __atomic_store_n(slot,(uintptr_t)tls_crypto_dispatch,__ATOMIC_RELEASE);
 if(mprotect(address,(size_t)page,PROT_READ)){
  __atomic_store_n(slot,(uintptr_t)ssl_read,__ATOMIC_RELEASE);
  if(mprotect(address,(size_t)page,PROT_READ))abort();
  goto done;
 }
 // Installation precedes all player/preloader creation; keep runtime handles until process exit.
 installed=1;result=0;sdl=NULL;crypto=NULL;ssl=NULL;
done:
 if(sdl)dlclose(sdl);if(crypto)dlclose(crypto);if(ssl)dlclose(ssl);
 pthread_mutex_unlock(&lock);return result;
}
#ifndef TLS_CRYPTO_TEST
JNIEXPORT void JNICALL Java_dev_piliplus_dspbridge_DspBridge_installTlsCrypto(JNIEnv *env,jclass cls){
 (void)cls;
 if(getuid()%100000<10000||install_tls_crypto())
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),"Original TLS runtime mismatch");
 else __android_log_print(ANDROID_LOG_INFO,"PiliTlsCrypto","installed authenticated GCM adapter");
}
#endif

#ifdef TLS_CRYPTO_TEST
int tls_candidate_install(void){return install_tls_crypto();}
int tls_candidate_original(void *a,unsigned char *o,const unsigned char *i,uint32_t n,const unsigned char *v,uint32_t vn,const unsigned char *t,uint32_t tn,const unsigned char *d,uint32_t dn){return tls_gcm_original(a,o,i,n,v,vn,t,tn,d,dn);}
unsigned tls_candidate_count(int fast){return atomic_load(fast?&tls_test_fast:&tls_test_fallback);}
void tls_candidate_alloc_fail(int fail){tls_test_alloc_failure=fail;}
#endif

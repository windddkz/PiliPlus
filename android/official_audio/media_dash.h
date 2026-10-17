#ifndef PILIPLUS_MEDIA_DASH_H
#define PILIPLUS_MEDIA_DASH_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>

typedef int (*DashCodec)(uintptr_t);
typedef void (*DashBind)(int,int,void *,uintptr_t *);
static int dash_add_av1(void *source,const uintptr_t *all,DashCodec codec,DashBind bind){
 if(all[0]>all[1]||all[1]>all[2]||(all[1]-all[0])%sizeof(uintptr_t)||
    (all[1]-all[0])/sizeof(uintptr_t)>80||(!all[0]&&all[1])||all[0]%sizeof(uintptr_t))return -1;
 uintptr_t selected[20],n=0;
 for(uintptr_t *s=(void *)all[0];s!=(void *)all[1];++s){
  if(!*s)return -1;
  if(codec(*s)==2){if(n==20)return -1;selected[n++]=*s;}
 }
 if(!n)return 0;
 if(*(int *)((char *)source+12)!=0)return -1;
 _Alignas(16) unsigned char temp[0x2968]={0};
 uintptr_t vector[3]={(uintptr_t)selected,(uintptr_t)(selected+n),(uintptr_t)(selected+n)};
 // This parameter selects the helper's destination array, never the media codec identity.
 bind(0,0,temp,vector);
 if(*(int *)(temp+4)!=(int)n)return -1;
 memcpy((char *)source+0xdd8,temp+0x18,n*0x58);
 *(int *)((char *)source+12)=(int)n;
 // Transfer URL ownership to the AV1 array for the original clone/clean lifecycle.
 memset(temp+0x18,0,n*0x58);*(int *)(temp+4)=0;
 return (int)n;
}
#endif

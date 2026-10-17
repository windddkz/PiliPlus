"""Exercise the production single-slot transaction and its protection failure paths."""
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
source = (repo/'android/official_audio/graph_adapter.c').read_text()
function = source[source.index('static int replace(uintptr_t *slot,'):source.index('static void fail(')]
assert function.count('static int replace(') == 1
stretcher = source[source.index('static void (*soundtouch_constructor)'):source.index('static int replace_stretcher(')]
harness = r'''
#define _GNU_SOURCE
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>
static int step, failed_step, persistent;
static int injected(void *p,size_t n,int prot) {
    step++;
    if(step==failed_step || (persistent && step>=failed_step))return -1;
    return mprotect(p,n,prot);
}
#define mprotect injected
''' + function + r'''
#undef mprotect
#define __android_log_print(...) ((void)0)
''' + stretcher + r'''
static int phase, setting_ok;
static char object;
static void ctor(void *p) { assert(p==&object && phase==0);phase=1; }
static int setting(void *p,int id,int value) {
    assert(p==&object && phase==1 && id==5 && value==16);phase=2;
    return setting_ok;
}
static void test_stretcher(void) {
    soundtouch_constructor=ctor;soundtouch_setting=setting;
    setting_ok=1;soundtouch_adjusted(&object);assert(phase==2 && stretch_error==0);
    phase=0;setting_ok=0;soundtouch_adjusted(&object);assert(phase==2 && stretch_error==1);
}
static void readonly(uintptr_t *p) {
    pid_t pid=fork();assert(pid>=0);
    if(!pid){*p=99;_exit(0);}
    int status;assert(waitpid(pid,&status,0)==pid);
    assert(WIFSIGNALED(status) && WTERMSIG(status)==SIGSEGV);
}
int main(void) {
    test_stretcher();
    struct rlimit core={0,0};assert(!setrlimit(RLIMIT_CORE,&core));
    long n=sysconf(_SC_PAGESIZE);assert(n>0);
    uintptr_t *p=mmap(NULL,(size_t)n,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(p!=MAP_FAILED);*p=11;assert(!mprotect(p,(size_t)n,PROT_READ));
    assert(replace(p,12,33)==-1 && *p==11 && step==0);readonly(p);
    for(int failure=1;failure<=2;failure++) {
        step=0;failed_step=failure;
        assert(replace(p,11,33)==-1 && *p==11);readonly(p);
    }
    step=failed_step=0;
    assert(!replace(p,11,33) && *p==33);readonly(p);
    assert(!replace(p,33,11) && *p==11);readonly(p);
    pid_t pid=fork();assert(pid>=0);
    if(!pid){step=0;failed_step=2;persistent=1;replace(p,11,33);_exit(1);}
    int status;assert(waitpid(pid,&status,0)==pid);
    assert(WIFEXITED(status) && WEXITSTATUS(status)==120);
    assert(*p==11);readonly(p);assert(!munmap(p,(size_t)n));
    puts("constructor order, setting failure, slot mismatch, install, restore, write denial, rollback, fatal protection failure passed");
}
'''
with tempfile.TemporaryDirectory(prefix='piliplus-slot-test-') as directory:
    path = Path(directory)
    (path/'test.c').write_text(harness)
    subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', str(path/'test.c'), '-o', str(path/'test')], check=True)
    subprocess.run([str(path/'test')], check=True)

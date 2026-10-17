"""Exercise the production buffering gate with known and unrelated native objects."""
from pathlib import Path
import subprocess
import tempfile

source = (Path(__file__).resolve().parents[1] / 'android/official_audio/graph_adapter.c').read_text()
gate = source.split('static SourceResult adapted(void *context){', 1)[1].split('    SourceResult result=original(context);', 1)[0]
gate = gate.replace('((int (*)(void *))(base+0x139d80))(checker)', 'is_buffering(checker)')
gate = gate.replace('((int (*)(void *))(base+0x139d88))(checker)', 'check(checker)')
harness = r'''
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#define FIELD(p,o,T) (*(T *)((char *)(p)+(o)))
static uintptr_t base=0x10000000;
static unsigned queries,checks;
static int waiting;
static uintptr_t context[16],listener[16],checker[16],listener_vtable[16],checker_vtable[16];
static int is_buffering(void *p){assert(p==checker);queries++;return waiting;}
static int check(void *p){assert(p==checker);checks++;return 0;}
static void recheck(void *context){
''' + gate + r'''
}
int main(void){
    recheck(context);assert(!queries&&!checks);
    FIELD(context,0x30,void *)=listener;FIELD(listener,0,void *)=listener_vtable;
    recheck(context);assert(!queries&&!checks);
    FIELD(listener_vtable,0x70,uintptr_t)=base+0x13b168;
    recheck(context);assert(!queries&&!checks);
    FIELD(listener,0x10,void *)=checker;FIELD(checker,0,void *)=checker_vtable;
    recheck(context);assert(!queries&&!checks);
    FIELD(checker_vtable,0x70,uintptr_t)=base+0x139d80;
    recheck(context);assert(queries==1&&!checks);
    waiting=1;recheck(context);assert(queries==2&&checks==1);
    waiting=0;recheck(context);assert(queries==3&&checks==1);
    puts("Production buffering gate: null/foreign objects, ready/waiting transitions passed");
}
'''
with tempfile.TemporaryDirectory(prefix='piliplus-buffering-') as directory:
    p = Path(directory)
    (p/'test.c').write_text(harness)
    subprocess.run(['cc', '-Wall', '-Wextra', '-Werror', str(p/'test.c'), '-o', str(p/'test')], check=True)
    subprocess.run([str(p/'test')], check=True)

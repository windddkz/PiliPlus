"""Check rate rejection and close synchronization in the production observer."""

from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
source = (repo / "android/official_audio/output_observer.h").read_text()
functions = source[
    source.index("static int observe_output_rate(") : source.index(
        "static int replace_output_track("
    )
]
harness = (
    r"""
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <sched.h>
static pthread_mutex_t output_lock=PTHREAD_MUTEX_INITIALIZER;
static void *output_engine;
static int output_rate_error, result_code, cleared;
static atomic_int closing, closed;
static char engine;
static int rate(void *p,double r){assert(p==&engine && r==4.0);return result_code;}
static int close_engine(void *p){assert(p==&engine && output_engine==0);atomic_store(&closed,1);return 17;}
static int (*output_rate_original)(void *,double)=rate;
static int (*output_close_original)(void *)=close_engine;
static void clear_output_track(void){cleared++;}
"""
    + functions
    + r"""
static void *close_thread(void *p){atomic_store(&closing,1);assert(observe_output_close(p)==17);return 0;}
int main(void){
    result_code=-22;
    assert(observe_output_rate(&engine,4.0)==-22);
    assert(output_engine==&engine && output_rate_error==-22);
    result_code=0;
    assert(observe_output_rate(&engine,4.0)==0 && output_rate_error==0);
    pthread_mutex_lock(&output_lock);
    pthread_t thread;assert(!pthread_create(&thread,0,close_thread,&engine));
    while(!atomic_load(&closing))sched_yield();
    assert(!atomic_load(&closed) && output_engine==&engine);
    pthread_mutex_unlock(&output_lock);
    assert(!pthread_join(thread,0));
    assert(atomic_load(&closed) && !output_engine && output_rate_error==0 && cleared==1);
    return 0;
}
"""
)
with tempfile.TemporaryDirectory(prefix="pili-output-test-") as directory:
    c = Path(directory) / "test.c"
    binary = Path(directory) / "test"
    c.write_text(harness)
    subprocess.run(
        [
            "cc",
            "-std=c11",
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-pthread",
            str(c),
            "-o",
            str(binary),
        ],
        check=True,
    )
    subprocess.run([str(binary)], check=True)
print("Native rate errors preserved; output observer invalidated before original close")

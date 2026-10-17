package com.example.piliplus.official;

import java.util.ArrayList;
import java.util.List;

public final class OfficialPreloadCacheTest {
    static final class Fake implements OfficialPreloadCache.Backend<String>, OfficialPreloadCache.Scheduler {
        final List<String> calls=new ArrayList<>();
        final List<Runnable> timers=new ArrayList<>();
        long request,task,now;
        int failures;
        public void add(long r,long t,String i){request=r;task=t;calls.add("add:"+i);}
        public void detach(long r,long t,String i){request=r;task=t;calls.add("detach:"+i);}
        public void interrupt(String i){calls.add("interrupt:"+i);}
        public void remove(long r,long t,String i){request=r;task=t;calls.add("remove:"+i);}
        public void release(String i){calls.add("release:"+i);}
        public void failed(Exception e){failures++;}
        public void after(long ms,Runnable action){timers.add(action);}
        OfficialPreloadCache<String> cache(){return new OfficialPreloadCache<>(this,this,()->now);}
    }
    static void check(boolean yes){if(!yes)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            check(c.add("a","item-a"));long add=f.request,task=f.task;
            c.event(add,task,"READY",-22);check(!c.isBroken() && c.peek("a").equals("item-a"));
            c.event(0,task+100,"READY",-22);check(!c.isBroken() && c.peek("a").equals("item-a"));
            c.event(add,task,"ADDED",0);check(c.peek("a").equals("item-a"));
            c.event(0,task,"READY",0);check(c.peek("a").equals("item-a"));
            check(c.take("a",consumed::add));long detach=f.request;
            c.event(0,task,"READY",0);check(c.peek("a")==null);
            check(consumed.isEmpty());check(!c.take("a",consumed::add));
            c.event(add,task,"DETACHED",0);check(consumed.isEmpty());
            c.event(detach,task,"DETACHED",0);check(consumed.equals(List.of("item-a")));
            c.event(detach,task,"DETACHED",0);check(consumed.size()==1 && c.size()==0);
            check(!f.calls.contains("release:item-a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","item-a");long add=f.request,task=f.task;
            c.discard("a");
            check(!c.take("a",i->{throw new AssertionError("Removing item transferred");}));
            long remove=f.request;
            check(f.calls.equals(List.of("add:item-a","interrupt:item-a","remove:item-a")));
            c.event(0,task,"READY",0);check(c.peek("a")==null && c.size()==1);
            c.event(remove,task,"REMOVED",0);check(c.size()==0 && f.calls.get(3).equals("release:item-a"));
            c.event(remove,task,"REMOVED",0);check(f.calls.size()==4);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            for(int i=0;i<3;i++)c.add("k"+i,"v"+i);
            check(!c.add("next","extra"));check(c.size()==3);
            check(!f.calls.contains("add:extra") && f.calls.contains("release:extra"));
            check(!f.calls.contains("release:v0"));
            c.event(f.request,f.task,"REMOVED",0);check(c.size()==2);
            check(c.add("next","next-item"));check(c.size()==3);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long add=f.request,task=f.task;c.event(0,task,"READY",0);
            f.now=30000;check(c.peek("a")==null);f.timers.get(0).run();
            check(!f.calls.contains("release:a"));f.timers.get(1).run();
            check(c.isBroken() && f.failures==1);
            check(!c.add("b","b") && f.calls.contains("release:b"));
            c.event(f.request,task,"REMOVED",0);check(c.size()==0 && f.calls.contains("release:a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");c.event(f.request,f.task,"ADDED",-22);
            check(c.isBroken() && !f.calls.contains("release:a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long ar=f.request,at=f.task;c.event(0,at,"READY",0);
            c.add("b","b");long br=f.request,bt=f.task;c.event(0,bt,"READY",0);
            c.discardExcept("a");long remove=f.request;
            check(c.peek("a").equals("a") && c.peek("b")==null && c.size()==2);
            check(f.calls.contains("interrupt:b") && !f.calls.contains("interrupt:a") && !f.calls.contains("release:b"));
            c.event(remove,bt,"REMOVED",0);check(c.size()==1);
            c.discardExcept(null);check(c.peek("a")==null && c.size()==1);
            c.event(f.request,at,"REMOVED",0);check(c.size()==0);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("pending","item");long add=f.request,task=f.task;
            check(c.peek("pending").equals("item"));
            check(c.take("pending",consumed::add));long detach=f.request;
            check(c.peek("pending")==null && consumed.isEmpty());
            c.event(add,task,"ADDED",0);
            c.event(0,task,"READY",0);check(consumed.isEmpty());
            c.event(add,task,"DETACHED",0);check(consumed.isEmpty());
            c.event(detach,task,"DETACHED",0);check(consumed.equals(List.of("item")));
            c.event(0,task,"READY",0);c.event(detach,task,"DETACHED",0);
            check(consumed.size()==1 && c.size()==0 && !f.calls.contains("release:item"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("pending","item");
            check(c.take("pending",consumed::add));
            f.timers.get(1).run();
            check(c.isBroken() && f.failures==1 && consumed.isEmpty());
            check(!f.calls.contains("release:item") && c.peek("pending")==null);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","a");long add=f.request,task=f.task;
            check(c.takeReady("a",consumed::add));check(c.peek("a")==null);
            check(!c.takeReady("a",consumed::add) && !c.take("a",consumed::add));
            check(!f.calls.contains("detach:a"));
            c.event(add,task,"READY",0);check(!f.calls.contains("detach:a"));
            c.event(0,task,"READY",0);long detach=f.request;check(f.calls.contains("detach:a") && consumed.isEmpty());
            c.event(add,task,"DETACHED",0);check(consumed.isEmpty());
            c.event(detach,task,"DETACHED",0);check(consumed.equals(List.of("a")));
            for(Runnable timer:f.timers)timer.run();check(!c.isBroken());
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","a");long task=f.task;c.event(0,task,"READY",0);
            check(c.takeReady("a",consumed::add));check(f.calls.contains("detach:a"));
            c.event(f.request,task,"DETACHED",0);check(consumed.equals(List.of("a")));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","a");long task=f.task;check(c.takeReady("a",consumed::add));
            c.discardExcept(null);long remove=f.request;
            c.event(0,task,"READY",0);check(!f.calls.contains("detach:a") && !f.calls.contains("release:a"));
            c.event(remove,task,"REMOVED",0);check(f.calls.contains("release:a") && consumed.isEmpty());
            for(Runnable timer:f.timers)timer.run();check(!c.isBroken());
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long task=f.task;check(c.takeReady("a",i->{throw new AssertionError();}));
            f.now=30000;f.timers.get(0).run();check(!f.calls.contains("remove:a"));
            f.timers.get(1).run();check(c.isBroken() && f.failures==1 && f.calls.contains("interrupt:a"));
            check(!f.calls.contains("release:a"));c.event(f.request,task,"REMOVED",0);check(f.calls.contains("release:a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long task=f.task;check(c.takeReady("a",i->{throw new AssertionError();}));
            c.event(0,task,"READY",-22);check(c.isBroken() && f.failures==1 && !f.calls.contains("release:a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long task=f.task;c.event(0,task,"READY",0);
            f.now=20000;check(c.retain("a"));check(f.timers.size()==1);
            f.now=30000;f.timers.get(0).run();check(f.timers.size()==2);
            check(c.peek("a").equals("a") && !f.calls.contains("remove:a"));
            f.now=50000;check(!c.retain("a"));f.timers.get(1).run();
            check(f.calls.contains("remove:a") && !f.calls.contains("release:a"));
            c.event(f.request,task,"REMOVED",0);check(f.calls.contains("release:a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");long task=f.task;
            f.now=10000;check(c.retain("a"));check(c.takeReady("a",i->{}));
            check(!c.retain("a"));c.event(0,task,"READY",0);check(!c.retain("a"));
            c.event(f.request,task,"DETACHED",0);check(!c.retain("a"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","old");c.discard("a");long removal=f.request,old=f.task;
            c.add("a","new");f.now=20000;check(c.retain("a"));
            c.event(removal,old,"REMOVED",0);
            f.now=30000;check(c.peek("a").equals("new"));
            check(f.calls.contains("release:old") && !f.calls.contains("release:new"));
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");f.now=1;c.add("b","b");f.now=2;c.add("c","c");
            f.now=3;check(c.retain("a"));c.makeRoom();long removal=f.request,task=f.task;
            check(f.calls.contains("remove:b") && !f.calls.contains("remove:a") && c.size()==3);
            c.makeRoom();check(!f.calls.contains("remove:c"));
            c.event(removal+99,task,"REMOVED",0);check(c.size()==3);
            c.event(removal,task,"REMOVED",0);check(c.size()==2 && f.calls.contains("release:b"));
            check(c.add("d","d") && c.size()==3);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");check(c.takeReady("a",i->{}));
            c.add("b","b");long bt=f.task;check(c.take("b",i->{}));
            c.add("c","c");c.makeRoom();
            check(f.calls.contains("remove:c") && !f.calls.contains("interrupt:a") && !f.calls.contains("interrupt:b"));
            c.event(f.request,f.task,"REMOVED",0);int calls=f.calls.size();
            c.makeRoom();check(f.calls.size()==calls && c.size()==2);
            c.event(0,bt,"READY",0);check(c.size()==2);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();
            c.add("a","a");c.event(f.request,f.task,"ADDED",-22);int calls=f.calls.size();
            c.makeRoom();check(f.calls.size()==calls && c.isBroken());
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","old");c.discard("a");long removal=f.request,old=f.task;
            c.add("a","new");long task=f.task;
            check(c.takeReady("a",consumed::add));
            c.event(0,task,"READY",0);long detach=f.request;
            c.event(removal,old,"REMOVED",0);check(consumed.isEmpty());
            c.event(detach,task,"DETACHED",0);check(consumed.equals(List.of("new")) && c.size()==0);
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","same-item");long old=f.task;
            check(c.take("a",consumed::add));long detached=f.request;
            c.event(detached,old,"DETACHED",0);check(consumed.equals(List.of("same-item")) && c.size()==0);
            consumed.clear();check(c.add("a","same-item"));long retry=f.task;
            check(c.takeReady("a",consumed::add));int calls=f.calls.size();
            c.event(0,old,"READY",0);c.event(detached,old,"DETACHED",0);
            check(f.calls.size()==calls && consumed.isEmpty() && c.size()==1);
            c.event(0,retry,"READY",0);c.event(f.request,retry,"DETACHED",0);
            check(consumed.equals(List.of("same-item")) && c.size()==0);
            check(!f.calls.contains("release:same-item"));
            for(Runnable timer:f.timers)timer.run();check(!c.isBroken());
        }
        {
            Fake f=new Fake();OfficialPreloadCache<String> c=f.cache();List<String> consumed=new ArrayList<>();
            c.add("a","same-item");long old=f.task;check(c.take("a",consumed::add));
            c.event(f.request,old,"DETACHED",0);consumed.clear();
            check(c.add("a","same-item"));long retry=f.task;check(c.takeReady("a",consumed::add));
            c.discardExcept(null);long removal=f.request;
            c.event(0,old,"READY",0);c.event(0,retry,"READY",0);check(consumed.isEmpty());
            c.event(removal,retry,"REMOVED",0);check(c.size()==0);
            check(f.calls.stream().filter("release:same-item"::equals).count()==1);
            for(Runnable timer:f.timers)timer.run();check(!c.isBroken());
        }
        System.out.println("22 ownership scenarios passed");
    }
}

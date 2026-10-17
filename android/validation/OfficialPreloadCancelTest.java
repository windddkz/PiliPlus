package com.example.piliplus.official;

import java.util.*;

/** A queued native open can reset an earlier IO interrupt before REMOVED arrives. */
public final class OfficialPreloadCancelTest {
    static final class Item {
        boolean opened, interrupted, released;
        int interrupts;
    }
    static final class Timer implements Comparable<Timer> {
        final long at, serial;
        final Runnable action;
        Timer(long at, long serial, Runnable action) {
            this.at = at;
            this.serial = serial;
            this.action = action;
        }
        public int compareTo(Timer other) {
            int order = Long.compare(at, other.at);
            return order != 0 ? order : Long.compare(serial, other.serial);
        }
    }
    static final class World
            implements OfficialPreloadCache.Backend<Item>, OfficialPreloadCache.Scheduler {
        final PriorityQueue<Timer> timers = new PriorityQueue<>();
        final Map<Item, long[]> removals = new IdentityHashMap<>();
        long now, serial;
        int failures;
        boolean throwInterrupt;
        final OfficialPreloadCache<Item> cache = new OfficialPreloadCache<>(this, this, () -> now);
        public void add(long r, long t, Item item) {}
        public void detach(long r, long t, Item item) {}
        public void interrupt(Item item) throws Exception {
            if (item.released)
                throw new AssertionError("Interrupt after release");
            item.interrupts++;
            if (throwInterrupt)
                throw new Exception("IO interrupt failed");
            if (item.opened)
                item.interrupted = true;
        }
        public void remove(long r, long t, Item item) {
            if (removals.put(item, new long[] {r, t}) != null)
                throw new AssertionError("Duplicate removal");
        }
        public void release(Item item) {
            if (item.released)
                throw new AssertionError("Double release");
            item.released = true;
        }
        public void failed(Exception e) {
            failures++;
        }
        public void after(long delay, Runnable action) {
            timers.add(new Timer(now + delay, ++serial, action));
        }
        void advance(long to) {
            while (!timers.isEmpty() && timers.peek().at <= to) {
                Timer next = timers.remove();
                now = next.at;
                next.action.run();
            }
            now = to;
        }
        void removed(Item item) {
            long[] event = removals.get(item);
            cache.event(event[0], event[1], "REMOVED", 0);
        }
    }
    static void check(boolean yes) {
        if (!yes)
            throw new AssertionError();
    }
    public static void main(String[] args) throws Exception {
        World w = new World();
        Item selected = new Item(), cancelled = new Item();
        w.cache.add("selected", selected);
        w.cache.add("cancelled", cancelled);
        check(w.cache.take("selected", item -> {}));
        w.cache.discardExcept("selected");
        check(cancelled.interrupts == 1 && !cancelled.interrupted && !cancelled.released);
        w.advance(7);
        cancelled.opened = true;
        w.advance(40);
        check(cancelled.interrupted);
        check(selected.interrupts == 0);
        check(w.removals.size() == 1);
        w.removed(cancelled);
        int calls = cancelled.interrupts;
        w.advance(100);
        check(cancelled.interrupts == calls && cancelled.released);
        World failed = new World();
        Item pending = new Item();
        failed.cache.add("a", pending);
        failed.cache.discard("a");
        failed.throwInterrupt = true;
        failed.advance(40);
        check(failed.cache.isBroken() && failed.failures == 1);
        int stopped = pending.interrupts;
        failed.advance(6000);
        check(pending.interrupts == stopped && !pending.released);
        failed.removed(pending);
        check(pending.released);
        World timeout = new World();
        Item missing = new Item();
        timeout.cache.add("a", missing);
        timeout.cache.discard("a");
        timeout.advance(5000);
        check(timeout.cache.isBroken() && timeout.failures == 1 && !missing.released);
        stopped = missing.interrupts;
        timeout.advance(6000);
        check(missing.interrupts == stopped);
        System.out.println(
                "PASS late-open cancellation, selected isolation, exact ACK, no double remove/release, failure and timeout stop");
    }
}

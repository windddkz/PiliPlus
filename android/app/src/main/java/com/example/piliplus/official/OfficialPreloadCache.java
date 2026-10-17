package com.example.piliplus.official;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Worker-thread ownership of original native preload tasks, including pending removals. */
final class OfficialPreloadCache<T> {
    static final int MAX_ENTRIES = 30;
    interface Backend<T> {
        void add(long request, long task, T item) throws Exception;
        void detach(long request, long task, T item) throws Exception;
        void interrupt(T item) throws Exception;
        void remove(long request, long task, T item) throws Exception;
        void release(T item) throws Exception;
        void failed(Exception error);
    }
    interface Scheduler {
        void after(long millis, Runnable action);
    }
    private enum State { ADDING, READY, DETACHING, REMOVING }
    private final class Entry {
        final long task, added;
        long renewed;
        final String key;
        final T item;
        long movement;
        State state = State.ADDING;
        Consumer<T> consumer;
        Entry(String key, T item) {
            this.key = key;
            this.item = item;
            task = ++serial;
            added = ++serial;
            renewed = now.getAsLong();
        }
    }
    private final Backend<T> backend;
    private final Scheduler scheduler;
    private final LongSupplier now;
    private final int capacity;
    private final long lifetime;
    private final ToIntFunction<T> priority;
    private final Map<Long, Entry> entries = new LinkedHashMap<>();
    private long serial;
    private boolean broken;

    OfficialPreloadCache(Backend<T> backend, Scheduler scheduler, LongSupplier now) {
        this(backend, scheduler, now, 3);
    }
    OfficialPreloadCache(Backend<T> backend, Scheduler scheduler, LongSupplier now, int capacity) {
        this(backend, scheduler, now, capacity, 30000);
    }
    OfficialPreloadCache(Backend<T> backend, Scheduler scheduler, LongSupplier now, int capacity,
            long lifetime) {
        this(backend, scheduler, now, capacity, lifetime, item -> 0);
    }
    OfficialPreloadCache(Backend<T> backend, Scheduler scheduler, LongSupplier now, int capacity,
            long lifetime, ToIntFunction<T> priority) {
        if (capacity < 1 || capacity > MAX_ENTRIES || lifetime <= 0)
            throw new IllegalArgumentException("Invalid preload capacity");
        this.priority = priority;
        this.backend = backend;
        this.scheduler = scheduler;
        this.now = now;
        this.capacity = capacity;
        this.lifetime = lifetime;
    }
    boolean isBroken() {
        return broken;
    }
    int size() {
        return entries.size();
    }
    int preparing() {
        int count = 0;
        for (Entry entry : entries.values())
            if (entry.state == State.ADDING)
                count++;
        return count;
    }
    T ready(long task) {
        Entry entry = entries.get(task);
        return entry != null && entry.state == State.READY ? entry.item : null;
    }
    boolean ready(String key) {
        for (Entry entry : entries.values())
            if (entry.key.equals(key) && entry.state == State.READY)
                return true;
        return false;
    }
    void makeRoom(int incomingPriority) {
        if (broken)
            return;
        Entry oldest = null;
        for (Entry entry : entries.values()) {
            // One outstanding removal is enough; its acknowledgement will free the slot.
            if (entry.state == State.REMOVING)
                return;
            int order = oldest == null ? 0
                                       : Integer.compare(priority.applyAsInt(entry.item),
                                                 priority.applyAsInt(oldest.item));
            if (entry.consumer == null
                    && (entry.state == State.ADDING || entry.state == State.READY)
                    && (oldest == null || order > 0
                            || (order == 0
                                    && ((entry.state == State.READY && oldest.state != State.READY)
                                            || (entry.state == oldest.state
                                                    && entry.renewed < oldest.renewed)))))
                oldest = entry;
        }
        // Let preparation finish before a same/lower-priority candidate rotates into this slot.
        if (oldest != null
                && (oldest.state == State.READY
                        || priority.applyAsInt(oldest.item) > incomingPriority))
            remove(oldest);
    }
    void discardExcept(String key) {
        for (Entry entry : entries.values()) {
            if (!entry.key.equals(key)
                    && (entry.state == State.ADDING || entry.state == State.READY))
                remove(entry);
        }
    }
    T peek(String key) {
        if (broken || key == null)
            return null;
        for (Entry entry : entries.values()) {
            if (entry.key.equals(key) && entry.consumer == null
                    && (entry.state == State.ADDING || entry.state == State.READY)
                    && now.getAsLong() - entry.renewed < lifetime)
                return entry.item;
        }
        return null;
    }
    boolean retain(String key) {
        if (broken || key == null)
            return false;
        for (Entry entry : entries.values()) {
            if (entry.key.equals(key) && entry.consumer == null
                    && (entry.state == State.ADDING || entry.state == State.READY)
                    && now.getAsLong() - entry.renewed < lifetime) {
                entry.renewed = now.getAsLong();
                return true;
            }
        }
        return false;
    }
    boolean discardMatching(Predicate<T> matches) {
        boolean owned = false;
        for (Entry entry : entries.values()) {
            if (!matches.test(entry.item))
                continue;
            owned = true;
            if (entry.consumer == null
                    && (entry.state == State.ADDING || entry.state == State.READY))
                remove(entry);
        }
        return owned;
    }

    void discard(String key) {
        if (key == null)
            return;
        for (Entry entry : entries.values()) {
            if (entry.key.equals(key)
                    && (entry.state == State.ADDING || entry.state == State.READY)) {
                remove(entry);
                return;
            }
        }
    }

    boolean add(String key, T item) throws Exception {
        if (broken) {
            backend.release(item);
            return false;
        }
        for (Entry entry : entries.values()) {
            if (entry.key.equals(key) && entry.state != State.REMOVING) {
                backend.release(item);
                return false;
            }
        }
        if (entries.size() >= capacity) {
            makeRoom(priority.applyAsInt(item));
            // Pending removals still own native handles and count against the bound.
            backend.release(item);
            return false;
        }
        Entry entry = new Entry(key, item);
        entries.put(entry.task, entry);
        try {
            backend.add(entry.added, entry.task, item);
        } catch (Exception error) {
            fail(error);
            return false;
        }
        expire(entry, lifetime);
        return true;
    }

    private void expire(Entry entry, long delay) {
        scheduler.after(delay, () -> {
            if (entries.get(entry.task) != entry || entry.consumer != null
                    || (entry.state != State.ADDING && entry.state != State.READY))
                return;
            long remaining = lifetime - (now.getAsLong() - entry.renewed);
            if (remaining > 0)
                expire(entry, remaining);
            else
                remove(entry);
        });
    }

    boolean take(String key, Consumer<T> consumer) {
        return take(key, consumer, false);
    }
    boolean takeReady(String key, Consumer<T> consumer) {
        return take(key, consumer, true);
    }
    private boolean take(String key, Consumer<T> consumer, boolean waitReady) {
        if (broken)
            return false;
        for (Entry entry : entries.values()) {
            if (!entry.key.equals(key))
                continue;
            if (entry.consumer != null)
                return false;
            if ((entry.state != State.ADDING && entry.state != State.READY)
                    || now.getAsLong() - entry.renewed >= lifetime) {
                if (entry.state == State.ADDING || entry.state == State.READY)
                    remove(entry);
                continue;
            }
            entry.consumer = consumer;
            if (waitReady && entry.state == State.ADDING) {
                scheduler.after(20000, () -> {
                    if (entries.get(entry.task) == entry && entry.state == State.ADDING
                            && entry.consumer != null) {
                        remove(entry);
                        fail(new IllegalStateException("preload_ready_timeout"));
                    }
                });
            } else
                detach(entry);
            return true;
        }
        return false;
    }

    private void detach(Entry entry) {
        // READY metadata still belongs to the preloader until its exact DETACHED acknowledgement.
        entry.state = State.DETACHING;
        entry.movement = ++serial;
        try {
            backend.detach(entry.movement, entry.task, entry.item);
        } catch (Exception error) {
            fail(error);
        }
        watch(entry);
    }

    private void remove(Entry entry) {
        entry.state = State.REMOVING;
        entry.consumer = null;
        entry.movement = ++serial;
        try {
            backend.interrupt(entry.item);
            backend.remove(entry.movement, entry.task, entry.item);
        } catch (Exception error) {
            fail(error);
        }
        watch(entry);
        maintainInterrupt(entry);
    }
    private void maintainInterrupt(Entry entry) {
        // Native OpenInner resets IO interrupt; a queued open may start after cancellation.
        scheduler.after(20, () -> {
            if (broken || entries.get(entry.task) != entry || entry.state != State.REMOVING)
                return;
            try {
                backend.interrupt(entry.item);
            } catch (Exception error) {
                fail(error);
                return;
            }
            maintainInterrupt(entry);
        });
    }
    private void watch(Entry entry) {
        long movement = entry.movement;
        scheduler.after(5000, () -> {
            if (entries.get(entry.task) == entry && entry.movement == movement)
                fail(new IllegalStateException("preload_stop_unconfirmed"));
        });
    }
    private void fail(Exception error) {
        if (!broken) {
            broken = true;
            backend.failed(error);
        }
    }

    void event(long request, long task, String type, int error) {
        Entry entry = entries.get(task);
        if (entry == null)
            return;
        boolean added = request == entry.added && type.equals("ADDED");
        // Native READY is an unsolicited task event with request 0, not the add acknowledgement.
        boolean ready = request == 0 && type.equals("READY") && entry.state == State.ADDING;
        boolean moved = request == entry.movement
                && ((entry.state == State.DETACHING && type.equals("DETACHED"))
                        || (entry.state == State.REMOVING && type.equals("REMOVED")));
        if (!added && !ready && !moved)
            return;
        if (error != 0) {
            fail(new IllegalStateException("preload_native_" + error));
            return;
        }
        if (moved) {
            entries.remove(task);
            if (type.equals("DETACHED"))
                entry.consumer.accept(entry.item);
            else
                try {
                    backend.release(entry.item);
                } catch (Exception failure) {
                    fail(failure);
                }
        } else if (ready) {
            entry.state = State.READY;
            if (entry.consumer != null && !broken)
                detach(entry);
        }
    }
}

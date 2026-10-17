package com.example.piliplus.official;

import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/** One pending resource request; cancellation must not wait for native release. */
final class OfficialResourceRefresh<T> {
    private long nextId;
    private Ticket<T> pending;
    private boolean closed = true;
    private static final class Ticket<T> {
        final long id;
        boolean done;
        T result;
        Ticket(long id) {
            this.id = id;
        }
    }
    synchronized void open() {
        cancel();
        closed = false;
    }
    synchronized boolean isOpen() {
        return !closed;
    }
    synchronized void cancel() {
        closed = true;
        if (pending != null) {
            pending.done = true;
            pending.result = null;
            pending = null;
        }
        notifyAll();
    }
    synchronized boolean complete(long id, T result) {
        if (closed || pending == null || pending.id != id || pending.done)
            return false;
        pending.result = result;
        pending.done = true;
        notifyAll();
        return true;
    }
    synchronized T await(long timeoutMs, LongConsumer publish) throws InterruptedException {
        if (closed)
            return null;
        Ticket<T> ticket = pending;
        if (ticket == null) {
            ticket = new Ticket<>(++nextId);
            pending = ticket;
            try {
                publish.accept(ticket.id);
            } catch (RuntimeException error) {
                pending = null;
                throw error;
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!ticket.done && !closed) {
            long left = deadline - System.nanoTime();
            if (left <= 0)
                break;
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        if (pending == ticket)
            pending = null;
        return ticket.done ? ticket.result : null;
    }
}

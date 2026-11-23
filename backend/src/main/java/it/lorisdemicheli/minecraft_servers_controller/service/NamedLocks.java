package it.lorisdemicheli.minecraft_servers_controller.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per server name, so that two operations on the same server (two creates, a delete and
 * a start...) never interleave, while different servers proceed in parallel. Entries are removed
 * when nobody uses them. Only coordinates threads of this process (one controller replica).
 */
final class NamedLocks {

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock();
    int users;
  }

  private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

  <T> T with(String name, Supplier<T> action) {
    Entry entry = acquire(name);
    entry.lock.lock();
    try {
      return action.get();
    } finally {
      entry.lock.unlock();
      release(name);
    }
  }

  /** Runs {@code action} only if nobody holds the lock right now. @return true if it ran */
  boolean tryWith(String name, Runnable action) {
    Entry entry = acquire(name);
    try {
      if (!entry.lock.tryLock()) {
        return false;
      }
      try {
        action.run();
        return true;
      } finally {
        entry.lock.unlock();
      }
    } finally {
      release(name);
    }
  }

  int size() {
    return locks.size();
  }

  private Entry acquire(String name) {
    return locks.compute(name, (k, v) -> {
      Entry e = v == null ? new Entry() : v;
      e.users++;
      return e;
    });
  }

  private void release(String name) {
    locks.computeIfPresent(name, (k, v) -> --v.users == 0 ? null : v);
  }
}

package dev.mapaimine.bridge.undo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mapaimine.bridge.BridgeConfig;
import dev.mapaimine.bridge.api.ApiException;
import dev.mapaimine.bridge.api.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded undo history.
 *
 * <p>History is capped by {@code limits.maxUndoHistory} entries; the oldest snapshot is evicted
 * when a new one is committed. Individual snapshots additionally cap themselves by block count and
 * memory (see {@link UndoSnapshot}).
 *
 * <p><b>Threading.</b> {@link #requestRestore} is called from an HTTP worker and only enqueues
 * work; the actual block writes happen in {@link #tick} on the main thread under the same
 * per-tick budget as normal jobs, so a 2M-block rollback does not freeze the server. The HTTP
 * worker awaits the returned future.
 */
public final class UndoManager {

    private final BridgeConfig config;
    private final Deque<UndoSnapshot> history = new ArrayDeque<>();
    private final Queue<RestoreTask> restoreQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger idCounter = new AtomicInteger();

    private RestoreTask active;

    public UndoManager(BridgeConfig config) {
        this.config = config;
    }

    public boolean enabled() {
        return config.has("undo") && config.maxUndoHistory > 0;
    }

    /** Creates a snapshot for a job. Returns {@code null} when undo is off. */
    public UndoSnapshot createSnapshot(String worldName, String label) {
        if (!enabled()) return null;
        String id = "u_" + idCounter.incrementAndGet();
        return new UndoSnapshot(id, worldName, label, config.maxUndoBlocks,
                (long) config.maxUndoMemoryMb * 1024L * 1024L);
    }

    /** Publishes a finished snapshot into the history, evicting the oldest entries. */
    public synchronized void commit(UndoSnapshot snapshot) {
        if (snapshot == null || snapshot.isDisabled() || snapshot.size() == 0) return;
        history.addLast(snapshot);
        while (history.size() > config.maxUndoHistory) {
            history.removeFirst();
        }
    }

    public synchronized UndoSnapshot find(String undoId) {
        for (UndoSnapshot s : history) {
            if (s.undoId().equals(undoId)) return s;
        }
        return null;
    }

    public synchronized int historySize() {
        return history.size();
    }

    /** {@code GET /undo} — newest first. */
    public synchronized JsonArray historyJson() {
        JsonArray arr = new JsonArray();
        List<UndoSnapshot> list = new ArrayList<>(history);
        for (int i = list.size() - 1; i >= 0; i--) {
            UndoSnapshot s = list.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("undoId", s.undoId());
            o.addProperty("label", s.label());
            o.addProperty("blocks", s.size() - s.restoredCount());
            o.addProperty("at", s.createdAt());
            o.addProperty("world", s.worldName());
            arr.add(o);
        }
        return arr;
    }

    /**
     * Schedules a rollback. Without an id the most recent snapshot is used (PROTOCOL.md §2).
     *
     * @throws ApiException {@code UNDO_EMPTY} when there is nothing to roll back
     */
    public CompletableFuture<Integer> requestRestore(String undoId) {
        UndoSnapshot snapshot;
        synchronized (this) {
            if (!enabled()) throw ApiException.disabled("undo");
            if (undoId == null || undoId.isEmpty()) {
                snapshot = history.peekLast();
                if (snapshot == null) {
                    throw new ApiException(ErrorCode.UNDO_EMPTY, "Undo history is empty");
                }
            } else {
                snapshot = find(undoId);
                if (snapshot == null) {
                    throw new ApiException(ErrorCode.UNDO_EMPTY, "Unknown undoId: " + undoId,
                            "call GET /api/v1/undo for the current history");
                }
            }
            history.remove(snapshot);
        }
        RestoreTask task = new RestoreTask(snapshot);
        restoreQueue.add(task);
        return task.future;
    }

    /**
     * Main-thread pump. Restores at most {@code budget} blocks per call.
     *
     * @return the number of blocks restored this tick
     */
    public int tick(int budget) {
        if (active == null) {
            active = restoreQueue.poll();
            if (active == null) return 0;
        }
        int done = active.snapshot.restore(budget);
        if (done < 0) {
            active.future.completeExceptionally(
                    ApiException.noWorld(active.snapshot.worldName()));
            active = null;
            return 0;
        }
        active.restored += done;
        if (active.snapshot.isFullyRestored() || done == 0) {
            active.future.complete(active.restored);
            active = null;
        }
        return done;
    }

    public synchronized void clear() {
        history.clear();
    }

    private static final class RestoreTask {
        final UndoSnapshot snapshot;
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        int restored;

        RestoreTask(UndoSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }
}

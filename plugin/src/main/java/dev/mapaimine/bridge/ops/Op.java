package dev.mapaimine.bridge.ops;

/**
 * One entry of the {@code ops} array of {@code POST /ops} (PROTOCOL.md §2).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><b>Construction / parse</b> — on an HTTP worker thread. Must not touch the world.</li>
 *   <li>{@link #validate} — on an HTTP worker thread. Enforces limits so the client gets a 4xx
 *       before a job is created. Must not touch the world.</li>
 *   <li>{@link #estimate} — on an HTTP worker thread. Feeds {@code estimatedBlocks} and
 *       {@code dryRun}. Must be cheap and must not touch the world.</li>
 *   <li>{@link #execute} — on the <b>main thread</b>, possibly many times: it is called once per
 *       tick and must return {@code false} while more work remains, resuming where it left off.</li>
 * </ol>
 */
public interface Op {

    /** The protocol {@code type} discriminator, e.g. {@code "fill"}. */
    String type();

    /**
     * Upper-bound estimate of how many block positions this op will consider. Used for
     * {@code estimatedBlocks}, progress percentages and the {@code async:false} guard.
     */
    long estimate();

    /** Off-thread validation. Throws {@link dev.mapaimine.bridge.api.ApiException} on bad input. */
    default void validate(dev.mapaimine.bridge.BridgeConfig config) {
    }

    /**
     * Performs work until {@link ExecutionContext#hasBudget()} is exhausted.
     *
     * @return {@code true} when the op is complete and the runner should advance
     */
    boolean execute(ExecutionContext ctx);

    /** Optional phase label surfaced through job progress ({@code checkpoint} op). */
    default String checkpointName() {
        return null;
    }
}

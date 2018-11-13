package org.corfudb.runtime.view.replication;

import javax.annotation.Nonnull;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.wireprotocol.ILogData;
import org.corfudb.protocols.wireprotocol.LogicalSequenceNumber;
import org.corfudb.runtime.exceptions.HoleFillRequiredException;
import org.corfudb.runtime.view.RuntimeLayout;

/**
 * Created by mwei on 4/6/17.
 */
@Slf4j
public abstract class AbstractReplicationProtocol implements IReplicationProtocol {

    /** The hole fill policy to apply. */
    protected final IHoleFillPolicy holeFillPolicy;

    /** Build the replication protocol using the given hole filling policy.
     *
     * @param holeFillPolicy    The hole filling policy to be applied when
     *                          a read returns uncommitted data.
     */
    public AbstractReplicationProtocol(IHoleFillPolicy holeFillPolicy) {
        this.holeFillPolicy = holeFillPolicy;
    }

    /** {@inheritDoc}
     *
     *  <p>In the base implementation, we attempt to read data
     *  using the peek method. If data is returned by peek,
     *  we use it. Otherwise, we invoke the hole filling
     *  protocol.
     *
     **/
    @Nonnull
    @Override
    public ILogData read(RuntimeLayout runtimeLayout, LogicalSequenceNumber globalAddress) {
        try {
            return holeFillPolicy
                .peekUntilHoleFillRequired(globalAddress,
                        a -> peek(runtimeLayout, a));
        } catch (HoleFillRequiredException e) {
            log.debug("HoleFill[{}] due to {}", globalAddress, e.getMessage());
            holeFill(runtimeLayout, globalAddress);
            return peek(runtimeLayout, globalAddress);
        }
    }

    /**
     * Write a special hole filling entry using the
     * given address. When this call returns, either
     * the hole filling write has been committed
     * or the result of another client's write
     * has committed (you WILL need to -read- the
     * address to find out which write was adopted).
     *
     * @param globalAddress  The address to hole fill.
     */
    protected abstract void holeFill(RuntimeLayout runtimeLayout, LogicalSequenceNumber globalAddress);
}

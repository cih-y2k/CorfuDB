package org.corfudb.infrastructure.management;

import org.corfudb.protocols.wireprotocol.ClusterState;
import org.corfudb.runtime.view.Layout;

import java.util.List;

/**
 * {@link ClusterRecommendationEngine} provides methods to decide the status of Corfu servers
 * (failed or healed) in a given {@link Layout} and for a specific view of the cluster
 * captured in a {@link ClusterState}. Decisions are dependant on the concrete underlying algorithm
 * corresponding to a {@link ClusterRecommendationStrategy}.
 *
 * Created by Sam Behnam on 10/19/18.
 */
public interface ClusterRecommendationEngine {

    /**
     * Get the corresponding {@link ClusterRecommendationStrategy} used in the current instance of
     * {@link ClusterRecommendationEngine}. This strategy represents the characteristics of the
     * underlying algorithm used for making a decision about the failed or healed status of
     * Corfu servers.
     *
     * @return a concrete instance of {@link ClusterRecommendationStrategy}
     */
    ClusterRecommendationStrategy getClusterRecommendationStrategy();

    /**
     * Provide a list of servers in the Corfu cluster which according to the underlying algorithm
     * for {@link ClusterRecommendationStrategy} have failed. The decision is made based on the
     * given view of the cluster captured in {@link ClusterState} along with the expected
     * {@link Layout}.
     *
     * @param clusterStatus view of the Corfu server cluster from a client node's
     *                      perspective.
     * @param layout expected layout of the cluster.
     * @return a {@link List} of Corfu servers considered to have been failed according to the
     * underlying {@link ClusterRecommendationStrategy}.
     */
    List<String> failedServers(final ClusterState clusterStatus, final Layout layout);

    /**
     * Provide a list of servers in the Corfu cluster which according to the underlying algorithm
     * for {@link ClusterRecommendationStrategy} have healed. The decision is made based on the
     * given view of the cluster captured in {@link ClusterState} along with the expected
     * {@link Layout}.
     *
     * @param clusterStatus view of the Corfu server cluster from a client node's perspective.
     * @param layout expected layout of the cluster.
     * @return a {@link List} of servers considered to have been healed according to the underlying
     * {@link ClusterRecommendationStrategy}.
     */
    List<String> healedServers(final ClusterState clusterStatus, final Layout layout);
}

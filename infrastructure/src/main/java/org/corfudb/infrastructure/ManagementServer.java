package org.corfudb.infrastructure;

import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.infrastructure.management.ClusterStateContext;
import org.corfudb.infrastructure.management.ReconfigurationEventHandler;
import org.corfudb.infrastructure.orchestrator.Orchestrator;
import org.corfudb.protocols.wireprotocol.CorfuMsg;
import org.corfudb.protocols.wireprotocol.CorfuMsgType;
import org.corfudb.protocols.wireprotocol.CorfuPayloadMsg;
import org.corfudb.protocols.wireprotocol.DetectorMsg;
import org.corfudb.protocols.wireprotocol.orchestrator.OrchestratorMsg;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.UnreachableClusterException;
import org.corfudb.runtime.view.IReconfigurationHandlerPolicy;
import org.corfudb.runtime.view.Layout;
import org.corfudb.util.concurrent.SingletonResource;

import javax.annotation.Nonnull;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Handles reconfiguration and workflow requests to the Management Server.
 * Spawns the following services:
 * - Management Agent.
 * - Orchestrator.
 *
 * <p>Created by zlokhandwala on 9/28/16.
 */
@Slf4j
public class ManagementServer extends AbstractServer {

    /**
     * The options map.
     */
    private final Map<String, Object> opts;
    private final ServerContext serverContext;

    /**
     * A {@link SingletonResource} which provides a {@link CorfuRuntime}.
     */
    private final SingletonResource<CorfuRuntime> corfuRuntime;
    /**
     * Policy to be used to handle failures/healing.
     */
    private IReconfigurationHandlerPolicy failureHandlerPolicy;
    private IReconfigurationHandlerPolicy healingPolicy;

    @Getter
    private final ManagementAgent managementAgent;

    @Getter
    private final String localEndpoint;
    private final ClusterStateContext clusterStateContext;

    private Orchestrator orchestrator;

    /**
     * Returns new ManagementServer.
     *
     * @param serverContext context object providing parameters and objects
     */
    public ManagementServer(ServerContext serverContext, RemoteMonitoringService rms, SingletonResource<CorfuRuntime> corfuRuntime) {

        this.opts = serverContext.getServerConfig();
        this.localEndpoint = this.opts.get("--address") + ":" + this.opts.get("<port>");
        this.serverContext = serverContext;

        this.failureHandlerPolicy = serverContext.getFailureHandlerPolicy();
        this.healingPolicy = serverContext.getHealingHandlerPolicy();

        // Creating a management agent.
        this.corfuRuntime = corfuRuntime;
        this.managementAgent = new ManagementAgent(corfuRuntime, serverContext, rms);
        this.clusterStateContext = rms.getClusterStateContext();
        // Creating an orchestrator.
        this.orchestrator = new Orchestrator(corfuRuntime, serverContext);
    }

    /**
     * Handler for this server.
     */
    @Getter
    private final CorfuMsgHandler handler =
            CorfuMsgHandler.generateHandler(MethodHandles.lookup(), this);

    private boolean checkBootstrap(CorfuMsg msg) {
        if (serverContext.getManagementLayout() == null) {
            log.warn("Received message but not bootstrapped! Message={}", msg);
            return false;
        }
        return true;
    }

    /**
     * Forward an orchestrator request to the orchestrator service.
     *
     * @param msg corfu message containing ORCHESTRATOR_REQUEST
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.ORCHESTRATOR_REQUEST)
    public synchronized void handleOrchestratorMsg(@Nonnull CorfuPayloadMsg<OrchestratorMsg> msg,
                                                   @Nonnull ChannelHandlerContext ctx,
                                                   @Nonnull IServerRouter r) {
        log.debug("Received an orchestrator message {}", msg);
        orchestrator.handle(msg, ctx, r);
    }

    /**
     * Bootstraps the management server.
     * The msg contains the layout to be bootstrapped.
     *
     * @param msg corfu message containing MANAGEMENT_BOOTSTRAP_REQUEST
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.MANAGEMENT_BOOTSTRAP_REQUEST)
    public synchronized void handleManagementBootstrap(CorfuPayloadMsg<Layout> msg,
                                                       ChannelHandlerContext ctx, IServerRouter r) {
        if (serverContext.getManagementLayout() != null) {
            // We are already bootstrapped, bootstrap again is not allowed.
            log.warn("Got a request to bootstrap a server which is already bootstrapped, "
                    + "rejecting!");
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.MANAGEMENT_ALREADY_BOOTSTRAP_ERROR));
        } else {
            log.info("Received Bootstrap Layout : {}", msg.getPayload());
            serverContext.saveManagementLayout(msg.getPayload());
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.ACK));

        }
    }

    /**
     * Triggers the failure handler.
     * The msg contains the failed/defected nodes.
     *
     * @param msg corfu message containing MANAGEMENT_FAILURE_DETECTED
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.MANAGEMENT_FAILURE_DETECTED)
    public void handleFailureDetectedMsg(CorfuPayloadMsg<DetectorMsg> msg,
                                         ChannelHandlerContext ctx, IServerRouter r) {

        // This server has not been bootstrapped yet, ignore all requests.
        if (!checkBootstrap(msg)) {
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.MANAGEMENT_NOBOOTSTRAP_ERROR));
            return;
        }

        log.info("handleFailureDetectedMsg: Received DetectorMsg : {}", msg.getPayload());

        DetectorMsg detectorMsg = msg.getPayload();
        Layout layout = serverContext.copyManagementLayout();

        // If this message is stamped with an older epoch which indicates the polling was
        // conducted in an old epoch. This message cannot be considered and is discarded.
        if (!detectorMsg.getDetectorEpoch().equals(layout.getEpoch())) {
            log.error("handleFailureDetectedMsg: Discarding stale detector message received. "
                            + "detectorEpoch:{} latestLayout epoch:{}",
                    detectorMsg.getDetectorEpoch(), layout.getEpoch());
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.NACK));
            return;
        }

        // Collecting the failed nodes in the message that are amongst the responsive nodes in the
        // layout
        final Set<String> allActiveServers = layout.getAllActiveServers();
        final Set<String> responsiveFailedNodes = detectorMsg.getFailedNodes()
                .stream()
                .filter(allActiveServers::contains)
                .collect(Collectors.toSet());

        // If it is not an out of phase and there is no need to update the layout, return without
        // any reconfiguration
        if (!detectorMsg.getFailedNodes().isEmpty() && responsiveFailedNodes.isEmpty()) {
            log.warn("handleFailureDetectedMsg: No action is taken as none of the failed nodes " +
                            "are responsive. failedNodes:{} responsive layout nodes: {}, " +
                            "latestLayout epoch:{} ",
                    detectorMsg.getFailedNodes(), allActiveServers, layout.getEpoch());
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.ACK));
            return;
        }

        boolean result = ReconfigurationEventHandler.handleFailure(
                failureHandlerPolicy,
                layout,
                managementAgent.getCorfuRuntime(),
                responsiveFailedNodes);

        if (result) {
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.ACK));
        } else {
            log.error("handleFailureDetectedMsg: failure handling unsuccessful.");
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.NACK));
        }
    }

    /**
     * Triggers the healing handler.
     * The msg contains the healed nodes.
     *
     * @param msg corfu message containing MANAGEMENT_HEALING_DETECTED
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.MANAGEMENT_HEALING_DETECTED)
    public void handleHealingDetectedMsg(CorfuPayloadMsg<DetectorMsg> msg,
                                         ChannelHandlerContext ctx, IServerRouter r) {

        // This server has not been bootstrapped yet, ignore all requests.
        if (!checkBootstrap(msg)) {
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.MANAGEMENT_NOBOOTSTRAP_ERROR));
            return;
        }

        log.info("handleHealingDetectedMsg: Received DetectorMsg : {}", msg.getPayload());

        DetectorMsg detectorMsg = msg.getPayload();
        Layout layout = serverContext.copyManagementLayout();

        // If this message is stamped with an older epoch which indicates the polling was
        // conducted in an old epoch. This message cannot be considered and is discarded.
        if (!detectorMsg.getDetectorEpoch().equals(layout.getEpoch())) {
            log.error("handleHealingDetectedMsg: Discarding stale detector message received. "
                            + "detectorEpoch:{} latestLayout epoch:{}",
                    detectorMsg.getDetectorEpoch(), layout.getEpoch());
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.NACK));
            return;
        }

        // Collecting the healed nodes in the message that are amongst the unresponsive nodes in the
        // layout
        final List<String> unresponsiveServers = layout.getUnresponsiveServers();
        final Set<String> unresponsiveHealedNodes = detectorMsg.getHealedNodes()
                .stream()
                .filter(unresponsiveServers::contains)
                .collect(Collectors.toSet());

        // If it is not an out of phase and there is no need to update the layout, return without
        // any reconfiguration
        if (!detectorMsg.getHealedNodes().isEmpty() && unresponsiveHealedNodes.isEmpty()) {
            log.warn("handleHealingDetectedMsg: No action is taken as none of the healedNodes are" +
                            " unresponsive. healedNodes:{}, unresponsive layout nodes: {}, " +
                            "latestLayout epoch:{} ",
                    detectorMsg.getHealedNodes(), unresponsiveServers, layout.getEpoch());
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.ACK));
            return;
        }


        final Duration retryWorkflowQueryTimeout = Duration.ofSeconds(1L);
        boolean result = ReconfigurationEventHandler.handleHealing(
                managementAgent.getCorfuRuntime(),
                unresponsiveHealedNodes,
                retryWorkflowQueryTimeout);

        if (result) {
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.ACK));
        } else {
            log.error("handleHealingDetectedMsg: healing handling unsuccessful.");
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.NACK));
        }
    }

    /**
     * Handles the heartbeat request.
     * It accumulates the metrics required to build
     * and send the response.
     * The response comprises of the local nodeMetrics and
     * this node's view of the cluster (ClusterView).
     *
     * @param msg corfu message containing HEARTBEAT_REQUEST
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.HEARTBEAT_REQUEST)
    public void handleHeartbeatRequest(CorfuMsg msg, ChannelHandlerContext ctx, IServerRouter r) {
        r.sendResponse(ctx, msg, CorfuMsgType.HEARTBEAT_RESPONSE
                .payloadMsg(clusterStateContext.getClusterState()));
    }

    /**
     * Handles the Management layout request.
     *
     * @param msg corfu message containing MANAGEMENT_LAYOUT_REQUEST
     * @param ctx netty ChannelHandlerContext
     * @param r   server router
     */
    @ServerHandler(type = CorfuMsgType.MANAGEMENT_LAYOUT_REQUEST)
    public void handleLayoutRequest(CorfuMsg msg, ChannelHandlerContext ctx, IServerRouter r) {
        // This server has not been bootstrapped yet, ignore all requests.
        if (!checkBootstrap(msg)) {
            r.sendResponse(ctx, msg, new CorfuMsg(CorfuMsgType.MANAGEMENT_NOBOOTSTRAP_ERROR));
            return;
        }
        r.sendResponse(ctx, msg,
                CorfuMsgType.LAYOUT_RESPONSE.payloadMsg(serverContext.getManagementLayout()));
    }

    /**
     * Management Server shutdown:
     * Shuts down the fault detector service.
     */
    @Override
    public void shutdown() {
        super.shutdown();
        orchestrator.shutdown();
        managementAgent.shutdown();

        // Shut down the Corfu Runtime.
        corfuRuntime.cleanup(CorfuRuntime::shutdown);
    }
}

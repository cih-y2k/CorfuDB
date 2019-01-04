package org.corfudb.universe.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.universe.scenario.ScenarioUtils.waitForLayoutChange;
import static org.corfudb.universe.scenario.ScenarioUtils.waitForUnresponsiveServersChange;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_STREAM_NAME;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_TABLE_ITER;

import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.view.ClusterStatusReport;
import org.corfudb.runtime.view.Layout;
import org.corfudb.universe.GenericIntegrationTest;
import org.corfudb.universe.group.cluster.CorfuCluster;
import org.corfudb.universe.node.client.CorfuClient;
import org.corfudb.universe.node.server.CorfuServer;
import org.corfudb.util.Sleep;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;

public class NodeUpAndPartitionedIT extends GenericIntegrationTest {

    @Test
    public void testFailureDetector() {
        final int times = 30;

        for (int i = 0; i < times; i++) {
            getScenario().describe((fixture, testCase) -> {
                CorfuCluster corfuCluster = universe.getGroup(fixture.getCorfuCluster().getName());

                CorfuClient corfuClient = corfuCluster.getLocalCorfuClient();

                CorfuTable<String, String> table = corfuClient.createDefaultCorfuTable(DEFAULT_STREAM_NAME);
                for (int j = 0; j < DEFAULT_TABLE_ITER; j++) {
                    table.put(String.valueOf(j), String.valueOf(j));
                }

                testCase.it("Should fail the node with most link failures to unresponsive set", data -> {
                    // Deploy and bootstrap three nodes
                    CorfuServer server1 = corfuCluster.getServerByIndex(1);

                    Layout layoutBeforeStop = corfuClient.getLayout();

                    // Stop server1
                    server1.stop(Duration.ofSeconds(10));
                    waitForUnresponsiveServersChange(size -> size == 1, corfuClient);

                    Layout layoutAfterStop = corfuClient.getLayout();

                    String errorMessage = String.format(
                            "Layout before stop: %s, layout after stop: %s",
                            layoutBeforeStop.asJSONString(), layoutAfterStop.asJSONString()
                    );

                    assertThat(layoutAfterStop.getUnresponsiveServers())
                            .as(errorMessage)
                            .containsExactly(server1.getEndpoint());
                });
            });

            tearDown();
        }
    }


    /**
     * Test cluster behavior after an unresponsive node becomes available (up) and at the same
     * time a previously responsive node starts to have two link failures. One of which to a
     * responsive node and the other to an unresponsive. This tests asserts that regardless of
     * equal number of observed link failures for each of nodes in the responsive set towards the
     * other responsive nodes, the node which also has the most number of link failures to the
     * unresponsive set (potentially healed) will be taken out. In other word, it makes sure that
     * we don't remove a responsive node in a way that eliminates the possibility of future healing
     * of unresponsive nodes.
     * <p>
     * Steps taken in the test:
     * 1) Deploy and bootstrap a three nodes cluster
     * 2) Stop one node
     * 3) Create two link failures between a responsive node with smaller endpoint name and the
     * rest of the cluster AND restart the unresponsive node.
     * <p>
     * 4) Verify that responsive node mentioned in step 3 becomes unresponsive
     * 5) Verify that the restarted unresponsive node in step 3 gets healed
     * 6) Verify cluster status and data path
     */
    @Test(timeout = 300000)
    public void nodeUpAndPartitionedTest() {
        getScenario().describe((fixture, testCase) -> {
            CorfuCluster corfuCluster = universe.getGroup(fixture.getCorfuCluster().getName());

            CorfuClient corfuClient = corfuCluster.getLocalCorfuClient();

            CorfuTable<String, String> table = corfuClient.createDefaultCorfuTable(DEFAULT_STREAM_NAME);
            for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                table.put(String.valueOf(i), String.valueOf(i));
            }

            testCase.it("Should fail the node with most link failures to unresponsive set", data -> {
                // Deploy and bootstrap three nodes
                CorfuServer server0 = corfuCluster.getServerByIndex(0);
                CorfuServer server1 = corfuCluster.getServerByIndex(1);
                CorfuServer server2 = corfuCluster.getServerByIndex(2);

                // Stop server1
                server1.stop(Duration.ofSeconds(10));
                waitForUnresponsiveServersChange(size -> size == 1, corfuClient);

                assertThat(corfuClient.getLayout().getUnresponsiveServers())
                        .containsExactly(server1.getEndpoint());

                // Partition the responsive server0 from both unresponsive server1
                // and responsive server2 and reconnect server 1. Wait for layout's unresponsive
                // servers to change After this, cluster becomes unavailable.
                // NOTE: cannot use waitForClusterDown() since the partition only happens on server side, client
                // can still connect to two nodes, write to table so system down handler will not be triggered.
                server0.disconnect(Arrays.asList(server1, server2));
                server1.start();
                waitForLayoutChange(layout -> layout.getUnresponsiveServers()
                                .contains(server0.getEndpoint()),
                        corfuClient);

                // Verify server0 is unresponsive
                assertThat(corfuClient.getLayout().getUnresponsiveServers())
                        .contains(server0.getEndpoint());

                // Verify unresponsive server1 gets healed
                waitForUnresponsiveServersChange(size -> size == 1, corfuClient);
                assertThat(corfuClient.getLayout().getUnresponsiveServers())
                        .containsExactly(server0.getEndpoint());
                assertThat(corfuClient.getLayout().getAllActiveServers())
                        .contains(server1.getEndpoint());

                // Verify cluster status. Cluster status should be DEGRADED after one node is
                // marked unresponsive
                ClusterStatusReport clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                // TODO: uncomment the following line after ClusterStatus API is fixed for partial partition
                // assertThat(clusterStatusReport.getClusterStatus()).isEqualTo(ClusterStatus.DEGRADED);
                // TODO: add node status check after we redefine NodeStatus semantics

                // Heal all the link failures
                server0.reconnect(Arrays.asList(server1, server2));
                waitForUnresponsiveServersChange(size -> size == 0, corfuClient);

                final Duration sleepDuration = Duration.ofSeconds(1);
                // Verify cluster status is STABLE
                clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                while (!clusterStatusReport.getClusterStatus().equals(ClusterStatusReport.ClusterStatus.STABLE)) {
                    clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                    Sleep.sleepUninterruptibly(sleepDuration);
                }
                assertThat(clusterStatusReport.getClusterStatus()).isEqualTo(ClusterStatusReport.ClusterStatus.STABLE);

                // Verify data path is working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }
            });
        });
    }
}

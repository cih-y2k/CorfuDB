package org.corfudb.universe.scenario;

import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.view.ClusterStatusReport;
import org.corfudb.runtime.view.ClusterStatusReport.ClusterStatus;
import org.corfudb.runtime.view.Layout;
import org.corfudb.universe.GenericIntegrationTest;
import org.corfudb.universe.group.cluster.CorfuCluster;
import org.corfudb.universe.node.client.CorfuClient;
import org.corfudb.universe.node.server.CorfuServer;
import org.corfudb.util.Sleep;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.corfudb.universe.scenario.ScenarioUtils.waitForLayoutChange;
import static org.corfudb.universe.scenario.ScenarioUtils.waitForUnresponsiveServersChange;
import static org.corfudb.universe.scenario.ScenarioUtils.waitUninterruptibly;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_STREAM_NAME;
import static org.corfudb.universe.scenario.fixture.Fixtures.TestFixtureConst.DEFAULT_TABLE_ITER;

public class RotateLinkFailureIT extends GenericIntegrationTest {

    /**
     * Test cluster behavior when rotating link failure among nodes
     * <p>
     * 1) Deploy and bootstrap a three nodes cluster
     * 2) Create a link failure between node0 and node1
     * 3) Create a link failure between node1 and node2
     *    and heal previous link failure
     * 4) Create a link failure between node2 and node0
     *    and heal previous link failure
     * 5) Reverse rotation direction, create a link failure
     *    between node1 and node2 and heal previous link failure
     * 6) Verify layout and data path after each rotation
     * 7) Recover cluster by removing all link failures
     * 8) Verify layout, cluster status and data path
     */
    @Test(timeout = 300000)
    public void rotateLinkFailureTest() {
        getScenario().describe((fixture, testCase) -> {
            CorfuCluster corfuCluster = universe.getGroup(fixture.getCorfuCluster().getName());

            CorfuClient corfuClient = corfuCluster.getLocalCorfuClient();

            CorfuTable<String, String> table = corfuClient.createDefaultCorfuTable(DEFAULT_STREAM_NAME);
            for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                table.put(String.valueOf(i), String.valueOf(i));
            }

            testCase.it("Should rotate link failures among cluster", data -> {
                CorfuServer server0 = corfuCluster.getServerByIndex(0);
                CorfuServer server1 = corfuCluster.getServerByIndex(1);
                CorfuServer server2 = corfuCluster.getServerByIndex(2);

                // 1st link failure rotation, disconnect between server0 and server1
                server0.disconnect(Collections.singletonList(server1));
                waitForUnresponsiveServersChange(size -> size == 1, corfuClient);

                String firstServerToKick = Collections.max(Arrays.asList(server0.getEndpoint(), server1.getEndpoint()));
                assertThat(corfuClient.getLayout().getUnresponsiveServers()).containsExactly(firstServerToKick);

                Layout lastLayout = corfuClient.getLayout();

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }

                // 2nd link failure rotation, disconnect between server1 and server2
                // and heal previous link failure between server0 and server1
                server1.disconnect(Collections.singletonList(server2));
                server0.reconnect(Collections.singletonList(server1));

                // Wait for some time to ensure cluster stabilizes
                // Server1 should stay in unresponsive set, no layout change
                waitUninterruptibly(Duration.ofSeconds(20));
                assertThat(corfuClient.getLayout()).isEqualTo(lastLayout);

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }

                // 3rd link failure rotation, disconnect between server2 and server0
                // and heal previous link failure between server1 and server2
                server2.disconnect(Collections.singletonList(server0));
                server1.reconnect(Collections.singletonList(server2));

                // Server0 and server2 has same number of link failure ie. 1, the one with
                // larger endpoint should be marked as unresponsive.
                String secondServerToKick = Collections.max(Arrays.asList(server0.getEndpoint(), server2.getEndpoint()));
                waitForLayoutChange(layout -> layout.getUnresponsiveServers()
                        .equals(Collections.singletonList(secondServerToKick)), corfuClient);

                lastLayout = corfuClient.getLayout();

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }

                // 4th link failure rotation, reverse the rotating direction, disconnect between
                // server1 and server2 and heal previous link failure between server1 and server2
                server1.disconnect(Collections.singletonList(server2));
                server2.reconnect(Collections.singletonList(server0));

                // Wait for some time to ensure cluster stabilizes
                // Server1 should stay in unresponsive set, no layout change
                waitUninterruptibly(Duration.ofSeconds(20));
                assertThat(corfuClient.getLayout()).isEqualTo(lastLayout);

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }

                // Finally stop rotation and heal all link failures.
                server1.reconnect(Collections.singletonList(server2));
                waitForUnresponsiveServersChange(size -> size == 0, corfuClient);

                final Duration sleepDuration = Duration.ofSeconds(1);
                // Verify cluster status is STABLE
                ClusterStatusReport clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                while (!clusterStatusReport.getClusterStatus().equals(ClusterStatus.STABLE)) {
                    clusterStatusReport = corfuClient.getManagementView().getClusterStatus();
                    Sleep.sleepUninterruptibly(sleepDuration);
                }
                assertThat(clusterStatusReport.getClusterStatus()).isEqualTo(ClusterStatus.STABLE);

                // Verify data path working fine
                for (int i = 0; i < DEFAULT_TABLE_ITER; i++) {
                    assertThat(table.get(String.valueOf(i))).isEqualTo(String.valueOf(i));
                }
            });
        });
    }
}

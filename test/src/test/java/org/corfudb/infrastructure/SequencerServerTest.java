package org.corfudb.infrastructure;

import org.corfudb.protocols.wireprotocol.*;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Created by mwei on 12/13/15.
 */
public class SequencerServerTest extends AbstractServerTest {

    public SequencerServerTest() {
        super();
    }

    SequencerServer server;

    @Override
    public AbstractServer getDefaultServer() {
        server = new SequencerServer(ServerContextBuilder.emptyContext());
        return server;
    }

    @Before
    public void bootstrapSequencer() {
        server.setSequencerEpoch(0L);
    }

    /**
     * Verifies that the SEQUENCER_METRICS_REQUEST is responded by the SEQUENCER_METRICS_RESPONSE
     */
    @Test
    public void sequencerMetricsRequest() {
        sendMessage(CorfuMsgType.SEQUENCER_METRICS_REQUEST.msg());
        assertThat(getLastMessage().getMsgType())
                .isEqualTo(CorfuMsgType.SEQUENCER_METRICS_RESPONSE);
    }

    @Test
    public void responseForEachRequest() {
        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_LOW; i++) {
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ, new TokenRequest(1L, Collections.emptyList())));
            assertThat(getResponseMessages().size())
                    .isEqualTo(i + 1);
        }
    }

    @Test
    public void tokensAreIncreasing() {
        long lastTokenValue = -1;
        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_LOW; i++) {
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ, new TokenRequest(1L, Collections.emptyList())));
            LogicalSequenceNumber thisLogicalSequenceNumber = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();
            assertThat(thisLogicalSequenceNumber.getSequenceNumber())
                    .isGreaterThan(lastTokenValue);
            lastTokenValue = thisLogicalSequenceNumber.getSequenceNumber();
        }
    }

    @Test
    public void checkTokenPositionWorks() {
        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_LOW; i++) {
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ, new TokenRequest(1L, Collections.emptyList())));
            LogicalSequenceNumber thisLogicalSequenceNumber = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(0L, Collections.emptyList())));
            LogicalSequenceNumber checkLogicalSequenceNumber = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            assertThat(thisLogicalSequenceNumber)
                    .isEqualTo(checkLogicalSequenceNumber);
        }
    }

    @Test
    public void perStreamCheckTokenPositionWorks() {
        UUID streamA = UUID.nameUUIDFromBytes("streamA".getBytes());
        UUID streamB = UUID.nameUUIDFromBytes("streamB".getBytes());

        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_LOW; i++) {
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamA))));
            LogicalSequenceNumber thisLogicalSequenceNumberA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(0L, Collections.singletonList(streamA))));
            LogicalSequenceNumber checkLogicalSequenceNumberA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            assertThat(thisLogicalSequenceNumberA)
                    .isEqualTo(checkLogicalSequenceNumberA);

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamB))));
            LogicalSequenceNumber thisLogicalSequenceNumberB = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(0L, Collections.singletonList(streamB))));
            LogicalSequenceNumber checkLogicalSequenceNumberB = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            assertThat(thisLogicalSequenceNumberB)
                    .isEqualTo(checkLogicalSequenceNumberB);

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(0L, Collections.singletonList(streamA))));
            LogicalSequenceNumber checkLogicalSequenceNumberA2 = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            assertThat(checkLogicalSequenceNumberA2)
                    .isEqualTo(checkLogicalSequenceNumberA);

            assertThat(thisLogicalSequenceNumberB.getSequenceNumber())
                    .isGreaterThan(checkLogicalSequenceNumberA2.getSequenceNumber());
        }
    }

    @Test
    public void checkBackpointersWork() {
        UUID streamA = UUID.nameUUIDFromBytes("streamA".getBytes());
        UUID streamB = UUID.nameUUIDFromBytes("streamB".getBytes());

        for (int i = 0; i < PARAMETERS.NUM_ITERATIONS_LOW; i++) {
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamA))));
            LogicalSequenceNumber thisLogicalSequenceNumberA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamA))));
            long checkTokenAValue = getLastPayloadMessageAs(LSNResponse.class).getBackpointerMap().get(streamA);

            assertThat(thisLogicalSequenceNumberA.getSequenceNumber())
                    .isEqualTo(checkTokenAValue);

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamB))));
            LogicalSequenceNumber thisLogicalSequenceNumberB = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamB))));
            long checkTokenBValue = getLastPayloadMessageAs(LSNResponse.class).getBackpointerMap().get(streamB);

            assertThat(thisLogicalSequenceNumberB.getSequenceNumber())
                    .isEqualTo(checkTokenBValue);

            final long MULTI_TOKEN = 5L;

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(MULTI_TOKEN, Collections.singletonList(streamA))));
            thisLogicalSequenceNumberA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamA))));
            checkTokenAValue = getLastPayloadMessageAs(LSNResponse.class).getBackpointerMap().get(streamA);

            assertThat(thisLogicalSequenceNumberA.getSequenceNumber() + MULTI_TOKEN - 1)
                    .isEqualTo(checkTokenAValue);

            // check the requesting multiple tokens does not break the back-pointer for the multi-entry
            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(1L, Collections.singletonList(streamA))));
            thisLogicalSequenceNumberA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber();

            sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                    new TokenRequest(MULTI_TOKEN, Collections.singletonList(streamA))));
            checkTokenAValue = getLastPayloadMessageAs(LSNResponse.class).getBackpointerMap().get(streamA);

            assertThat(thisLogicalSequenceNumberA.getSequenceNumber())
                    .isEqualTo(checkTokenAValue);

        }
    }

    @Test
    public void SequencerWillResetTails() throws Exception {
        UUID streamA = UUID.nameUUIDFromBytes("streamA".getBytes());
        UUID streamB = UUID.nameUUIDFromBytes("streamB".getBytes());
        UUID streamC = UUID.nameUUIDFromBytes("streamC".getBytes());

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(1L, Collections.singletonList(streamA))));
        long tailA = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber();

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(1L, Collections.singletonList(streamB))));
        long tailB = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber();

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(1L, Collections.singletonList(streamC))));
        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(1L, Collections.singletonList(streamC))));

        long tailC = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber();

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(0L, Collections.emptyList())));
        long globalTail = getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber();

        // Construct new tails
        Map<UUID, Long> tailMap = new HashMap<>();
        long newTailA = tailA + 2;
        long newTailB = tailB + 1;
        // This one should not be updated
        long newTailC = tailC - 1;

        tailMap.put(streamA, newTailA);
        tailMap.put(streamB, newTailB);
        tailMap.put(streamC, newTailC);

        // Modifying the sequencerEpoch to simulate sequencer reset.
        server.setSequencerEpoch(-1L);
        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.BOOTSTRAP_SEQUENCER,
                new SequencerTailsRecoveryMsg(globalTail + 2, tailMap, 0L, false)));

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(0L, Collections.singletonList(streamA))));
        assertThat(getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber()).isEqualTo(newTailA);

        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(0L, Collections.singletonList(streamB))));
        assertThat(getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber()).isEqualTo(newTailB);

        // We should have the same value than before
        sendMessage(new CorfuPayloadMsg<>(CorfuMsgType.TOKEN_REQ,
                new TokenRequest(0L, Collections.singletonList(streamC))));
        assertThat(getLastPayloadMessageAs(LSNResponse.class).getLogicalSequenceNumber().getSequenceNumber()).isEqualTo(newTailC);
    }
}

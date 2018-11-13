package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;

import java.util.Collections;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

/**
 * A request to read multiple addresses.
 *
 * Created by maithem on 7/28/17.
 */
@Data
@AllArgsConstructor
public class MultipleReadRequest implements ICorfuPayload<MultipleReadRequest> {
    @Getter
    final List<LogicalSequenceNumber> addresses;

    /**
     * Deserialization Constructor from ByteBuf to ReadRequest.
     *
     * @param buf The buffer to deserialize
     */
    public MultipleReadRequest(ByteBuf buf) {
        addresses = ICorfuPayload.listFromBuffer(buf, LogicalSequenceNumber.class);
    }

    public MultipleReadRequest(LogicalSequenceNumber address) {
        addresses = Collections.singletonList(address);
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        ICorfuPayload.serialize(buf, addresses);
    }
}

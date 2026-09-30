package io.mycat.protocol.codec;

public abstract class SendOkWritePacket extends AbstractWritePacket {
    @Override
    public Class<? extends AbstractWritePacket> javaClass() {
        return SendOkWritePacket.class;
    }
}

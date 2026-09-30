package io.mycat.protocol.codec;

public abstract class RollbackWritePacket extends AbstractWritePacket {
    @Override
    public Class<? extends AbstractWritePacket> javaClass() {
        return RollbackWritePacket.class;
    }
}

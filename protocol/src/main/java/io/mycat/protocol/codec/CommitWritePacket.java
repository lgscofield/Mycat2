package io.mycat.protocol.codec;

public abstract class CommitWritePacket extends AbstractWritePacket {
    @Override
    public Class<? extends AbstractWritePacket> javaClass() {
        return CommitWritePacket.class;
    }
}

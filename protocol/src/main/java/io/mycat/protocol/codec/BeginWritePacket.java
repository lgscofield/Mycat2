package io.mycat.protocol.codec;

public abstract class BeginWritePacket extends AbstractWritePacket {

    @Override
    public Class<? extends AbstractWritePacket> javaClass() {
        return BeginWritePacket.class;
    }
}

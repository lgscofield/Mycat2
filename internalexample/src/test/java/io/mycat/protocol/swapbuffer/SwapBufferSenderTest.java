package io.mycat.protocol.swapbuffer;

// MYCAT CHANGE (版本归一轮 2026-10-15)：全 reactor 重建暴露存量断链——swapbuffer 实现类早已
// 迁到本包（protocol 模块），本测试此前停留在旧包 io.mycat.swapbuffer，靠陈旧本地 jar 才
// “碰巧编译得过”。测试访问包私有字段 sender/recycler，故整体搬入同包，回归当前源码现实。
import io.reactivex.rxjava3.core.Emitter;
import io.vertx.core.Future;
import org.junit.Assert;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class SwapBufferSenderTest {

    @Test
    public void baseTest() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        FixSwapBufferSenderImpl fixSwapBufferSender = new FixSwapBufferSenderImpl(byteBuffer) {
            @Override
            public void subscribe(SwapBufferSender observer) {
                observer.onComplete();
            }
        };
        Future<Void> future = SwapBufferUtil.consume(new PacketMessageConsumer() {

            @Override
            public void onNext(PacketRequest packetMessage, Emitter<PacketResponse> emitter) {
                if (packetMessage == PacketRequest.END){
                    emitter.onComplete();
                    return;
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public Future<Void> onComplete() {
                return Future.succeededFuture();
            }
        }, fixSwapBufferSender.sender, fixSwapBufferSender.recycler);

        Assert.assertTrue(future.succeeded());
        System.out.println();
    }
    @Test
    public void baseTest2() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        ArrayList<Byte> byteArrayList = new ArrayList<>();
        FixSwapBufferSenderImpl fixSwapBufferSender = new FixSwapBufferSenderImpl(byteBuffer) {
            @Override
            public void subscribe(SwapBufferSender observer) {
                observer.onNext((byte) (1));
                observer.onComplete();
                System.out.println();
            }
        };
        Future<Void> future = SwapBufferUtil.consume(new PacketMessageConsumer() {

            @Override
            public void onNext(PacketRequest packetMessage, Emitter<PacketResponse> emitter) {
                if (packetMessage == PacketRequest.END){
                    emitter.onComplete();
                    return;
                }
                ByteBuffer body = packetMessage.asJavaByteBuffer();
                int offset = packetMessage.offset();
                int length = packetMessage.length();
                for (int i = offset; i < offset + length; i++) {
                    byteArrayList.add(body.get(i));
                }
                emitter.onNext(packetMessage.response(length));
            }

            @Override
            public Future<Void> onComplete() {
                return Future.succeededFuture();
            }
        }, fixSwapBufferSender.sender, fixSwapBufferSender.recycler);

        Assert.assertTrue(future.succeeded());
        Assert.assertEquals("[1]",byteArrayList.toString());
        System.out.println();
    }
    public static void main(String[] args) {


    }
}

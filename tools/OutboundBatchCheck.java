import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import sun.misc.Unsafe;

/** Real Netty local transport: FIFO batching, listeners, unflushed writes and barriers. */
public class OutboundBatchCheck {
    static void await(CountDownLatch latch) throws Exception {
        if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Network operation stalled");
    }
    static void set(Object o,String name,Object value) throws Exception {
        Field f=Connection.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var group=new MultiThreadIoEventLoopGroup(2,task->{Thread t=new Thread(task,"Batch check IO");t.setDaemon(true);return t;},LocalIoHandler.newFactory());
        Channel listener=null,client=null,child=null;
        try {
            var childRef=new AtomicReference<Channel>();var connected=new CountDownLatch(1);
            var received=Collections.synchronizedList(new ArrayList<Integer>());var flushes=new AtomicInteger();var callbacks=new AtomicInteger();
            listener=new ServerBootstrap().group(group).channel(LocalServerChannel.class).childHandler(new ChannelInitializer<LocalChannel>() {
                public void initChannel(LocalChannel channel) {
                    channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                        public void flush(ChannelHandlerContext ctx) throws Exception {flushes.incrementAndGet();super.flush(ctx);}
                    });
                    channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        public void channelActive(ChannelHandlerContext ctx) {childRef.set(channel);connected.countDown();}
                    });
                }
            }).bind(new LocalAddress("batch-"+System.nanoTime())).syncUninterruptibly().channel();
            client=new Bootstrap().group(group).channel(LocalChannel.class).handler(new ChannelInboundHandlerAdapter() {
                public void channelRead(ChannelHandlerContext ctx,Object message) {
                    received.add(((ClientboundBlockChangedAckPacket)message).sequence());
                }
            }).connect(listener.localAddress()).syncUninterruptibly().channel();await(connected);child=childRef.get();
            var connection=new Connection(PacketFlow.SERVERBOUND);set(connection,"channel",child);
            Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);Unsafe unsafe=(Unsafe)f.get(null);
            set(connection,"packetListener",unsafe.allocateInstance(ServerGamePacketListenerImpl.class));
            var blocked=new CountDownLatch(1);var release=new CountDownLatch(1);
            child.eventLoop().execute(()->{blocked.countDown();try{await(release);}catch(Exception e){throw new AssertionError(e);}});await(blocked);
            for(int i=0;i<600;i++)connection.send(new ClientboundBlockChangedAckPacket(i),future->{
                if(!future.isSuccess())throw new AssertionError("Packet failed",future.cause());callbacks.incrementAndGet();
            },true);
            if(!received.isEmpty()||callbacks.get()!=0)throw new AssertionError("Sent outside event loop");
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(received.size()<600&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=600||callbacks.get()!=600)throw new AssertionError("Lost packets or callbacks");
            for(int i=0;i<600;i++)if(received.get(i)!=i)throw new AssertionError("Reordered batch packet");
            if(flushes.get()>4)throw new AssertionError("Flush-per-packet batching failed: "+flushes);
            connection.send(new ClientboundBlockChangedAckPacket(600),future->callbacks.incrementAndGet(),false);
            child.eventLoop().submit(()->{}).syncUninterruptibly();Thread.sleep(20);
            if(received.size()!=600||callbacks.get()!=600)throw new AssertionError("Flushed a flush=false write");
            connection.flushChannel();child.eventLoop().submit(()->{}).syncUninterruptibly();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(received.size()<601&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=601||callbacks.get()!=601)throw new AssertionError("Explicit flush lost buffered packet");
            // Worker submissions made during a running event-loop callback precede its direct send.
            var inCallback=new CountDownLatch(1);var directReady=new CountDownLatch(1);
            child.eventLoop().execute(()->{inCallback.countDown();try{await(directReady);}catch(Exception e){throw new AssertionError(e);}connection.send(new ClientboundBlockChangedAckPacket(603));});await(inCallback);
            connection.send(new ClientboundBlockChangedAckPacket(601));connection.send(new ClientboundBlockChangedAckPacket(602));directReady.countDown();
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<604&&System.nanoTime()<deadline)Thread.sleep(1);
            for(int i=0;i<604;i++)if(received.get(i)!=i)throw new AssertionError("Direct send overtook queued data");
            // Exercise the exact barrier used before changing protocol codecs.
            connection.send(new ClientboundBlockChangedAckPacket(604));
            var barrier=Connection.class.getDeclaredMethod("flushOutboundBeforeProtocolChange");barrier.setAccessible(true);barrier.invoke(connection);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<605&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=605)throw new AssertionError("Protocol barrier left queued gameplay data");
            var preconnect=new Connection(PacketFlow.SERVERBOUND);
            set(preconnect,"packetListener",unsafe.allocateInstance(ServerGamePacketListenerImpl.class));
            preconnect.send(new ClientboundBlockChangedAckPacket(605));
            if(received.size()!=605)throw new AssertionError("Sent before connection");
            set(preconnect,"channel",child);preconnect.send(new ClientboundBlockChangedAckPacket(606));
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<607&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=607||received.get(605)!=605||received.get(606)!=606)throw new AssertionError("Pre-connect queue reordered");
            Field pending=Connection.class.getDeclaredField("pendingActions");pending.setAccessible(true);
            var sent=new CountDownLatch(1);var error=new AtomicReference<Throwable>();
            synchronized(pending.get(connection)) {
                Thread.ofVirtual().start(()->{try{connection.send(new ClientboundBlockChangedAckPacket(607));}catch(Throwable t){error.set(t);}finally{sent.countDown();}});
                await(sent);
            }
            if(error.get()!=null)throw new AssertionError("Empty queue send failed",error.get());
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<608&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=608||received.get(607)!=607)throw new AssertionError("Empty-queue fast path lost packet");
            var producers=new CountDownLatch(32);var start=new CountDownLatch(1);
            for(int writer=0;writer<32;writer++) {
                final int index=writer;
                Thread.ofVirtual().start(()->{
                    try {await(start);for(int i=0;i<100;i++)connection.send(new ClientboundBlockChangedAckPacket(10000+index*100+i));}
                    catch(Throwable t){error.set(t);}finally{producers.countDown();}
                });
            }
            start.countDown();await(producers);
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<3808&&System.nanoTime()<deadline)Thread.sleep(1);
            if(received.size()!=3808||error.get()!=null)throw new AssertionError("Lost concurrent packet",error.get());
            int[] next=new int[32];
            for(int i=608;i<3808;i++) {
                int id=received.get(i)-10000;int writer=id/100;int sequence=id%100;
                if(writer<0||writer>=32||sequence!=next[writer]++)throw new AssertionError("Duplicate or reordered producer packet");
            }
            for(int count:next)if(count!=100)throw new AssertionError("Incomplete producer");
            var rejected=new CountDownLatch(1);var failedCallbacks=new AtomicInteger();
            child.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) throws Exception {
                    if(message instanceof ClientboundBlockChangedAckPacket packet && packet.sequence()==-1) {
                        promise.setFailure(new IllegalStateException("intentional test write failure"));
                    } else super.write(ctx,message,promise);
                }
            });
            connection.send(new ClientboundBlockChangedAckPacket(-1),future->{
                if(future.isSuccess())error.set(new AssertionError("Failed write reported success"));
                failedCallbacks.incrementAndGet();rejected.countDown();
            });await(rejected);
            connection.send(new ClientboundBlockChangedAckPacket(3808));
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(received.size()<3809&&System.nanoTime()<deadline)Thread.sleep(1);
            if(failedCallbacks.get()!=1||error.get()!=null||received.size()!=3809||received.get(3808)!=3808)throw new AssertionError("Write failure lost callback or stalled drain",error.get());
            System.out.println("PASS real Netty packet batching, FIFO, callbacks, flush=false, explicit flush and protocol barrier");
        } finally {
            if(child!=null)child.close().syncUninterruptibly();if(client!=null)client.close().syncUninterruptibly();if(listener!=null)listener.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();net.minecraft.util.Util.shutdownExecutors();
        }
    }
}

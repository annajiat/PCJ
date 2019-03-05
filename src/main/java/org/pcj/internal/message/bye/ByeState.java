package org.pcj.internal.message.bye;

import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.pcj.internal.InternalGroup;
import org.pcj.internal.InternalNodesDescription;
import org.pcj.internal.InternalPCJ;
import org.pcj.internal.Networker;
import org.pcj.internal.NodeData;
import org.pcj.internal.futures.InternalFuture;

public class ByeState {

    private static final Logger LOGGER = Logger.getLogger(ByeState.class.getName());
    private final ByeFuture future;
    private final AtomicInteger notificationCount;

    public ByeState(int childCount) {
        this.future = new ByeFuture();

        this.notificationCount = new AtomicInteger(childCount + 1);
    }

    public void await() throws InterruptedException {
        future.get();
    }

    public void signalDone() {
        future.signalDone();
    }

    public void nodeProcessed() {
        int leftPhysical = notificationCount.decrementAndGet();
        if (leftPhysical == 0) {
            NodeData nodeData = InternalPCJ.getNodeData();
            Networker networker = InternalPCJ.getNetworker();

            int currentPhysicalId = nodeData.getCurrentNodePhysicalId();
            if (currentPhysicalId == 0) {
                SocketChannel node0Socket = nodeData.getNode0Socket();

                MessageByeCompleted messageByeCompleted = new MessageByeCompleted();
                networker.send(node0Socket, messageByeCompleted);
            } else {
                SocketChannel parentSocketChannel = nodeData.getSocketChannelByPhysicalId((currentPhysicalId - 1) / 2);

                MessageBye messageBye = new MessageBye();
                networker.send(parentSocketChannel, messageBye);
            }
        }
    }

    public static class ByeFuture extends InternalFuture<InternalGroup> {
        protected void signalDone() {
            super.signal();
        }

        private void get() throws InterruptedException {
            super.await();
        }
    }
}
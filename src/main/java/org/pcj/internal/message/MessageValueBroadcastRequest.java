/*
 * This file is the internal part of the PCJ Library
 */
package org.pcj.internal.message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.logging.Level;
import org.pcj.internal.Configuration;
import org.pcj.internal.InternalCommonGroup;
import org.pcj.internal.InternalPCJ;
import org.pcj.internal.InternalStorage;
import org.pcj.internal.NodeData;
import org.pcj.internal.PcjThread;
import org.pcj.internal.LargeByteArray;
import org.pcj.internal.futures.BroadcastState;
import org.pcj.internal.network.LargeByteArrayInputStream;
import org.pcj.internal.network.MessageDataInputStream;
import org.pcj.internal.network.MessageDataOutputStream;

/**
 * ....
 *
 * @author Marek Nowicki (faramir@mat.umk.pl)
 */
final public class MessageValueBroadcastRequest extends Message {

    private static final int BYTES_CHUNK_SIZE = Configuration.CHUNK_SIZE;
    private int groupId;
    private int requestNum;
    private int requesterThreadId;
    private String storageName;
    private String name;
    private Object newValue;

    public MessageValueBroadcastRequest() {
        super(MessageType.VALUE_BROADCAST_REQUEST);
    }

    public MessageValueBroadcastRequest(int groupId, int requestNum, int requesterThreadId, String storageName, String name, Object newValue) {
        this();

        this.groupId = groupId;
        this.requestNum = requestNum;
        this.requesterThreadId = requesterThreadId;
        this.storageName = storageName;
        this.name = name;
        this.newValue = newValue;
    }

    @Override
    public void write(MessageDataOutputStream out) throws IOException {
        out.writeInt(groupId);
        out.writeInt(requestNum);
        out.writeInt(requesterThreadId);
        out.writeString(storageName);
        out.writeString(name);
        out.writeObject(newValue);
    }

    @Override
    public void execute(SocketChannel sender, MessageDataInputStream in) throws IOException {
        groupId = in.readInt();
        requestNum = in.readInt();
        requesterThreadId = in.readInt();

        storageName = in.readString();
        name = in.readString();

        LargeByteArray clonedData = readTillEnd(in);

        NodeData nodeData = InternalPCJ.getNodeData();
        InternalCommonGroup group = nodeData.getGroupById(groupId);

        List<Integer> children = group.getChildrenNodes();

        MessageValueBroadcastBytes message
                = new MessageValueBroadcastBytes(requestNum,
                        groupId, requesterThreadId, storageName, name, clonedData);

        children.stream().map(nodeData.getSocketChannelByPhysicalId()::get)
                .forEach(socket -> InternalPCJ.getNetworker().send(socket, message));

        int[] threadsId = group.getLocalThreadsId();
        for (int i = 0; i < threadsId.length; ++i) {
            int threadId = threadsId[i];
            try {
                int globalThreadId = group.getGlobalThreadId(threadId);
                PcjThread pcjThread = nodeData.getPcjThreads().get(globalThreadId);
                InternalStorage storage = (InternalStorage) pcjThread.getThreadData().getStorage();

                newValue = new ObjectInputStream(new LargeByteArrayInputStream(clonedData)).readObject();

                storage.put0(storageName, name, newValue);
            } catch (ClassNotFoundException ex) {
                LOGGER.log(Level.SEVERE, "ClassCastException...", ex);
            }
        }

        BroadcastState broadcastState = group.getBroadcastState(requestNum, requesterThreadId);
        broadcastState.processPhysical(nodeData.getPhysicalId());
    }

    private LargeByteArray readTillEnd(MessageDataInputStream in) throws IOException {
        LargeByteArray largeByteArray = new LargeByteArray();

        byte[] bytes = new byte[BYTES_CHUNK_SIZE];
        int b;
        int index = 0;
        long l = 0;
        while ((b = in.read()) != -1) {
            bytes[index++] = (byte) b;
            ++l;
            if (index == bytes.length) {
                largeByteArray.addBytes(bytes);

                bytes = new byte[BYTES_CHUNK_SIZE];
                index = 0;
            }
        }
        if (index > 0) {
            byte[] dest = new byte[index];
            System.arraycopy(bytes, 0, dest, 0, index);
            largeByteArray.addBytes(dest);
        }

        return largeByteArray;
    }

}
/*
 * Copyright (c) 2011-2019, PCJ Library, Marek Nowicki
 * All rights reserved.
 *
 * Licensed under New BSD License (3-clause license).
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package org.pcj.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import org.pcj.internal.message.barrier.BarrierStates;
import org.pcj.internal.message.broadcast.BroadcastStates;
import org.pcj.internal.message.collect.CollectStates;
import org.pcj.internal.message.join.GroupJoinStates;
import org.pcj.internal.message.reduce.ReduceStates;

/**
 * Internal (with common ClassLoader) representation of Group. It contains
 * common data for groups.
 *
 * @author Marek Nowicki (faramir@mat.umk.pl)
 */
public class InternalCommonGroup {

    public static final int GLOBAL_GROUP_ID = 0;
    public static final String GLOBAL_GROUP_NAME = "";

    private final int groupId;
    private final String groupName;
    private final ConcurrentHashMap<Integer, Integer> threadsMap; // groupThreadId, globalThreadId
    private final AtomicInteger threadsCounter;
    private final Set<Integer> localIds;
    private final CommunicationTree communicationTree;
    private final BarrierStates barrierStates;
    private final BroadcastStates broadcastStates;
    private final CollectStates collectStates;
    private final ReduceStates reduceStates;
    private final GroupJoinStates groupJoinStates;

    public InternalCommonGroup(InternalCommonGroup g) {
        this.groupId = g.groupId;
        this.groupName = g.groupName;
        this.communicationTree = g.communicationTree;

        this.threadsMap = g.threadsMap;
        this.threadsCounter = g.threadsCounter;
        this.localIds = g.localIds;

        this.barrierStates = g.barrierStates;
        this.broadcastStates = g.broadcastStates;
        this.collectStates = g.collectStates;
        this.reduceStates = g.reduceStates;
        this.groupJoinStates = g.groupJoinStates;
    }

    public InternalCommonGroup(int groupMasterNode, int groupId, String groupName) {
        this.groupId = groupId;
        this.groupName = groupName;
        this.communicationTree = new CommunicationTree(groupMasterNode);

        this.threadsMap = new ConcurrentHashMap<>();
        this.threadsCounter = new AtomicInteger(0);
        this.localIds = ConcurrentHashMap.newKeySet();

        this.barrierStates = new BarrierStates();
        this.broadcastStates = new BroadcastStates();
        this.collectStates = new CollectStates();
        this.reduceStates = new ReduceStates();
        this.groupJoinStates = new GroupJoinStates();
    }

    public final int getGroupId() {
        return groupId;
    }

    public final String getName() {
        return groupName;
    }

    public final int threadCount() {
        return threadsMap.size();
    }

    public final Set<Integer> getLocalThreadsId() {
        return Collections.unmodifiableSet(localIds);
    }

    public final int getGlobalThreadId(int groupThreadId) throws NoSuchElementException {
        Integer globalThreadId = threadsMap.get(groupThreadId);
        if (globalThreadId == null) {
            throw new NoSuchElementException("Group threadId not found: " + groupThreadId);
        }
        return globalThreadId;
    }

    public final int getGroupThreadId(int globalThreadId) throws NoSuchElementException {
        return threadsMap.entrySet().stream()
                       .filter(entry -> entry.getValue() == globalThreadId)
                       .mapToInt(Map.Entry::getKey)
                       .findFirst()
                       .orElseThrow(() -> new NoSuchElementException("Global threadId not found: " + globalThreadId));
    }

    public final void addNewThread(int globalThreadId) {
        int groupThreadId;
        do {
            groupThreadId = threadsCounter.getAndIncrement();
        } while (threadsMap.putIfAbsent(groupThreadId, globalThreadId) != null);

        updateLocalThreads();
        communicationTree.update(threadsMap);
    }

    public final void updateThreadsMap(Map<Integer, Integer> newThreadsMap) { // groupId, globalId
        threadsMap.putAll(newThreadsMap);

        updateLocalThreads();
        communicationTree.update(this.threadsMap);
    }

    private void updateLocalThreads() {
        NodeData nodeData = InternalPCJ.getNodeData();
        int currentPhysicalId = nodeData.getCurrentNodePhysicalId();
        threadsMap.entrySet()
                .stream()
                .filter(entry -> nodeData.getPhysicalId(entry.getValue()) == currentPhysicalId)
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(localIds::add);
    }

    public Map<Integer, Integer> getThreadsMap() {
        return Collections.unmodifiableMap(threadsMap);
    }

    public BarrierStates getBarrierStates() {
        return barrierStates;
    }

    public final BroadcastStates getBroadcastStates() {
        return broadcastStates;
    }

    public final CollectStates getCollectStates() {
        return collectStates;
    }

    public final ReduceStates getReduceStates() {
        return reduceStates;
    }

    public GroupJoinStates getGroupJoinStates() {
        return groupJoinStates;
    }

    public CommunicationTree getCommunicationTree() {
        return communicationTree;
    }

    public static class CommunicationTree {

        private final int masterNode;
        private int parentNode;
        private final Set<Integer> childrenNodes;

        private CommunicationTree(int masterNode) {
            this.masterNode = masterNode;
            this.parentNode = -1;
            this.childrenNodes = new CopyOnWriteArraySet<>();
        }

        public final int getMasterNode() {
            return masterNode;
        }

        public final int getParentNode() {
            return parentNode;
        }

        public final Set<Integer> getChildrenNodes() {
            return Collections.unmodifiableSet(childrenNodes);
        }

        private void update(Map<Integer, Integer> threadsMapping) {
            NodeData nodeData = InternalPCJ.getNodeData();

            Set<Integer> physicalIdsSet = new LinkedHashSet<>();
            physicalIdsSet.add(masterNode);
            threadsMapping.keySet().stream()
                    .sorted()
                    .map(threadsMapping::get)
                    .map(nodeData::getPhysicalId)
                    .forEach(physicalIdsSet::add);
            List<Integer> physicalIds = new ArrayList<>(physicalIdsSet);

            int currentPhysicalId = InternalPCJ.getNodeData().getCurrentNodePhysicalId();
            int currentIndex = physicalIds.indexOf(currentPhysicalId);
            if (currentIndex < 0) {
                return;
            }

            if (currentIndex > 0) {
                parentNode = physicalIds.get((currentIndex - 1) / 2);
            }
            if (currentIndex * 2 + 1 < physicalIds.size()) {
                childrenNodes.add(physicalIds.get(currentIndex * 2 + 1));
            }
            if (currentIndex * 2 + 2 < physicalIds.size()) {
                childrenNodes.add(physicalIds.get(currentIndex * 2 + 2));
            }
        }

        public void setParentNode(int parentNode) {
            this.parentNode = parentNode;
        }
    }
}

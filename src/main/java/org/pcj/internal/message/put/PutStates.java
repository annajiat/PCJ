/*
 * Copyright (c) 2011-2019, PCJ Library, Marek Nowicki
 * All rights reserved.
 *
 * Licensed under New BSD License (3-clause license).
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package org.pcj.internal.message.put;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.pcj.PcjFuture;
import org.pcj.PcjRuntimeException;

/**
 * @author Marek Nowicki (faramir@mat.umk.pl)
 */
public class PutStates {
    private final AtomicInteger counter;
    private final ConcurrentMap<Integer, State> stateMap;

    public PutStates() {
        counter = new AtomicInteger(0);
        stateMap = new ConcurrentHashMap<>();
    }

    public State create() {
        int requestNum = counter.incrementAndGet();

        PutFuture future = new PutFuture();
        State state = new State(requestNum, future);

        stateMap.put(requestNum, state);

        return state;
    }

    public State remove(int requestNum) {
        return stateMap.remove(requestNum);
    }

    public class State {

        private final int requestNum;
        private final PutFuture future;

        public State(int requestNum, PutFuture future) {
            this.requestNum = requestNum;

            this.future = future;
        }

        public int getRequestNum() {
            return requestNum;
        }

        public PcjFuture<Void> getFuture() {
            return future;
        }

        public void signal(Exception exception) {
            if (exception == null) {
                future.signalDone();
            } else {
                PcjRuntimeException ex = new PcjRuntimeException("Putting value failed");
                ex.addSuppressed(exception);
                future.signalException(ex);
            }
        }
    }
}

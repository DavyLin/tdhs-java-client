/*
 * Copyright(C) 2011-2012 Alibaba Group Holding Limited
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2 as
 *  published by the Free Software Foundation.
 *
 *  Authors:
 *    wentong <wentong@taobao.com>
 */

package com.taobao.tdhs.client.net;

import com.taobao.tdhs.client.packet.BasePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author <a href="mailto:wentong@taobao.com">文通</a>
 * @since 11-12-26 下午5:31
 */
public abstract class AbstractTDHSNet<T> implements TDHSNet {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    protected AtomicInteger needConnectionNumber = new AtomicInteger(0);

    protected ConnectionPool<T> connectionPool;

    private final Lock lock = new ReentrantLock();

    private final Condition condition = lock.newCondition();

    private NetParameters parameters;

    private boolean shutdown = false;

    private Thread reconnectThread = new Thread(new Runnable() {
        /**
         * Method run ...
         */
        public void run() {
            while (!shutdown) {
                int num = needConnectionNumber.get();
                if (num > 0 && connectionPool.size() < parameters.getConnectionNumber()) {
                    for (int i = 0; i < num; i++) {
                        connect();
                    }
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.error("sleep error!", e);
                }
            }
        }
    }, "TDHS reconnect Thread"
    );


    public boolean awaitForConnected(long timeout, TimeUnit unit) {
        if (connectionPool.isEmpty()) {
            lock.lock();
            try {
                return condition.await(timeout, unit);
            } catch (InterruptedException e) {
                logger.error("thread Interrupted", e);
            } finally {
                lock.unlock();

            }
        }
        return true;
    }

    public void addConnectedConnectionToPool(T t, ConnectionPool.Handler<T> handler) {
        connectionPool.add(t, handler);
        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void initNet(NetParameters parameters, BasePacket shakeHandPacket,
                        Map<Long, ArrayBlockingQueue<BasePacket>> responses) {
        parameters.isVaild();
        this.parameters = parameters;
        _initNet(parameters, shakeHandPacket, responses);
        needConnectionNumber.set(parameters.getConnectionNumber());
        connectionPool = new ConnectionPool<T>(parameters.getConnectionNumber());
        for (int i = 0; i < parameters.getConnectionNumber(); i++) {
            connect();
        }
        if (parameters.isNeedReconnect()) {
            reconnectThread.start();
        }
    }

    private boolean connect() {
        needConnectionNumber.decrementAndGet();
        if (_connect(parameters.getAddress()) == null) {
            needConnectionNumber.incrementAndGet();
            return false;
        }
        return true;
    }


    protected abstract void _initNet(NetParameters parameters, BasePacket shakeHandPacket,
                                     Map<Long, ArrayBlockingQueue<BasePacket>> responses);

    protected abstract T _connect(InetSocketAddress address);

    public void release() {
        shutdown = true;
        _release();
    }

    protected abstract void _release();
}

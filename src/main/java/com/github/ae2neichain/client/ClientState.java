package com.github.ae2neichain.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.ae2neichain.network.PacketChainCraftResult;

public final class ClientState {

    /** 服务端是否安装了本 mod（登录时由服务端告知）。 */
    public static volatile boolean serverSupported = false;
    public static volatile Thread mainThread;
    public static final Map<Integer, PacketChainCraftResult> RESULTS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private ClientState() {}

    public static int nextRequestId() {
        return NEXT_ID.incrementAndGet();
    }
}

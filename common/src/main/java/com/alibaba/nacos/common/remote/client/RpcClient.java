/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.common.remote.client;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.remote.request.ConnectResetRequest;
import com.alibaba.nacos.api.remote.request.Request;
import com.alibaba.nacos.api.remote.request.ServerPushRequest;
import com.alibaba.nacos.api.remote.response.ConnectionUnregisterResponse;
import com.alibaba.nacos.api.remote.response.Response;
import com.alibaba.nacos.api.remote.response.ServerPushResponse;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.remote.ConnectionType;
import com.alibaba.nacos.common.utils.LoggerUtils;
import com.google.common.util.concurrent.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.alibaba.nacos.api.exception.NacosException.SERVER_ERROR;

/**
 * abstract remote client to connect to server.
 *
 * @author liuzunfei
 * @version $Id: RpcClient.java, v 0.1 2020年07月13日 9:15 PM liuzunfei Exp $
 */
public abstract class RpcClient implements Closeable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcClient.class);
    
    protected static final long ACTIVE_INTERNAL = 3000L;
    
    private ServerListFactory serverListFactory;
    
    protected String connectionId;
    
    protected LinkedBlockingQueue<ConnectionEvent> eventLinkedBlockingQueue = new LinkedBlockingQueue<ConnectionEvent>();
    
    protected volatile AtomicReference<RpcClientStatus> rpcClientStatus = new AtomicReference<RpcClientStatus>(
            RpcClientStatus.WAIT_INIT);
    
    private long activeTimeStamp = System.currentTimeMillis();
    
    protected ScheduledExecutorService executorService;
    
    protected volatile Connection currentConnetion;
    
    protected Map<String, String> labels = new HashMap<String, String>();
    
    /**
     * listener called where connect status changed.
     */
    protected List<ConnectionEventListener> connectionEventListeners = new ArrayList<ConnectionEventListener>();
    
    /**
     * change listeners handler registry.
     */
    protected List<ServerRequestHandler> serverRequestHandlers = new ArrayList<ServerRequestHandler>();
    
    public RpcClient() {
        this.connectionId = UUID.randomUUID().toString();
    }
    
    public RpcClient(ServerListFactory serverListFactory) {
        this.serverListFactory = serverListFactory;
        rpcClientStatus.compareAndSet(RpcClientStatus.WAIT_INIT, RpcClientStatus.INITED);
        LoggerUtils.printIfInfoEnabled(LOGGER, "RpcClient init in constructor ,connectionId={}, ServerListFactory ={}",
                this.connectionId, serverListFactory.getClass().getName());
    }
    
    /**
     * Notify when client re connected.
     */
    protected void notifyDisConnected() {
        if (!connectionEventListeners.isEmpty()) {
            LoggerUtils.printIfInfoEnabled(LOGGER, "Notify connection event listeners.");
            connectionEventListeners.forEach(new Consumer<ConnectionEventListener>() {
                @Override
                public void accept(ConnectionEventListener connectionEventListener) {
                    connectionEventListener.onDisConnect();
                }
            });
        }
    }
    
    /**
     * Notify when client new connected.
     */
    protected void notifyConnected() {
        if (!connectionEventListeners.isEmpty()) {
            connectionEventListeners.forEach(new Consumer<ConnectionEventListener>() {
                @Override
                public void accept(ConnectionEventListener connectionEventListener) {
                    connectionEventListener.onConnected();
                }
            });
        }
    }
    
    protected boolean overActiveTime() {
        return System.currentTimeMillis() - this.activeTimeStamp > ACTIVE_INTERNAL;
    }
    
    protected void refereshActiveTimestamp() {
        this.activeTimeStamp = System.currentTimeMillis();
    }
    
    /**
     * check is this client is inited.
     *
     * @return
     */
    public boolean isWaitInited() {
        return this.rpcClientStatus.get() == RpcClientStatus.WAIT_INIT;
    }
    
    /**
     * check is this client is running.
     *
     * @return
     */
    public boolean isRunning() {
        return this.rpcClientStatus.get() == RpcClientStatus.RUNNING;
    }
    
    /**
     * check is this client is in init status,have not start th client.
     *
     * @return
     */
    public boolean isInitStatus() {
        return this.rpcClientStatus.get() == RpcClientStatus.INITED;
    }
    
    /**
     * check is this client is in starting process.
     *
     * @return
     */
    public boolean isStarting() {
        return this.rpcClientStatus.get() == RpcClientStatus.STARTING;
    }
    
    /**
     * init server list factory.
     *
     * @param serverListFactory serverListFactory
     */
    public void init(ServerListFactory serverListFactory) {
        if (!isWaitInited()) {
            return;
        }
        this.serverListFactory = serverListFactory;
        rpcClientStatus.compareAndSet(RpcClientStatus.WAIT_INIT, RpcClientStatus.INITED);
    
        LoggerUtils
                .printIfInfoEnabled(LOGGER, "RpcClient init ,connectionId={}, ServerListFactory ={}", this.connectionId,
                        serverListFactory.getClass().getName());
    }
    
    /**
     * init server list factory.
     *
     * @param labels labels
     */
    public void initLabels(Map<String, String> labels) {
        this.labels.putAll(labels);
        LoggerUtils.printIfInfoEnabled(LOGGER, "RpcClient init label  ,labels={}", this.labels);
    }
    
    /**
     * Start this client.
     */
    public void start() throws NacosException {
    
        executorService = new ScheduledThreadPoolExecutor(5, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("com.alibaba.nacos.client.remote.worker");
                t.setDaemon(true);
                return t;
            }
        });
    
        // connect event consumer.
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    ConnectionEvent take = null;
                    try {
                        take = eventLinkedBlockingQueue.take();
                        if (take.isConnected()) {
                            notifyConnected();
                        } else if (take.isDisConnected()) {
                            notifyDisConnected();
                        }
                    } catch (Exception e) {
                        //Donothing
                    }
                }
            }
        });
    
        //connect to server ,try to connect to server sync once, aync starting if fail.
        Connection connectToServer = null;
        try {
            rpcClientStatus.set(RpcClientStatus.STARTING);
            connectToServer = connectToServer(nextRpcServer());
        } catch (Exception e) {
            //Fail to connect to server
        }
    
        if (connectToServer != null) {
            this.currentConnetion = connectToServer;
            rpcClientStatus.set(RpcClientStatus.RUNNING);
            eventLinkedBlockingQueue.offer(new ConnectionEvent(ConnectionEvent.CONNECTED));
        } else {
            switchServerAsync();
        }
    
        registerServerPushResponseHandler(new ServerRequestHandler() {
            @Override
            public void requestReply(ServerPushRequest request) {
                if (request instanceof ConnectResetRequest) {
                    try {
    
                        if (isRunning()) {
                            clearContextOnResetRequest();
                            switchServerAsync();
                        }
                    } catch (Exception e) {
                        LOGGER.error("switch server  error ", e);
                    }
                }
            }
        
        });
    }
    
    private final ReentrantLock switchingLock = new ReentrantLock();
    
    private volatile AtomicBoolean switchingFlag = new AtomicBoolean(false);
    
    /**
     * 1.判断当前是否正在重连中 2.如果正在重连中，则直接返回；如果不在重连中，则启动重连 3.重连逻辑：创建一个新的连接，如果连接可用
     */
    protected void switchServerAsync() {
    
        //return if is in switching of other thread.
        if (switchingFlag.get()) {
            return;
        }
        executorService.submit(new Runnable() {
            @Override
            public void run() {
    
                try {
                    //only one thread can execute switching meantime.
                    boolean innerLock = switchingLock.tryLock();
                    if (!innerLock) {
                        return;
                    }
                    switchingFlag.set(true);
                    // loop until start client success.
                    boolean switchSuccess = false;
                    while (!switchSuccess) {
    
                        //1.get a new server
                        ServerInfo serverInfo = nextRpcServer();
                        System.out.println("1:" + serverInfo);
                        //2.create a new channel to new server
                        try {
                            Connection connectNew = connectToServer(serverInfo);
                            if (connectNew != null) {
                                //successfully create a new connect.
                                closeConnection(currentConnetion);
                                currentConnetion = connectNew;
                                rpcClientStatus.set(RpcClientStatus.RUNNING);
                                switchSuccess = true;
                                boolean s = eventLinkedBlockingQueue
                                        .add(new ConnectionEvent(ConnectionEvent.CONNECTED));
                                return;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            // error to create connection
                        }
    
                        try {
                            //sleep 1 second to switch next server.
                            Thread.sleep(1000L);
                        } catch (InterruptedException e) {
                            // Do  nothing.
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    switchingFlag.set(false);
                    switchingLock.unlock();
                }
            }
        });
    }
    
    private void closeConnection(Connection connection) {
        if (connection != null) {
            connection.close();
            eventLinkedBlockingQueue.add(new ConnectionEvent(ConnectionEvent.DISCONNECTED));
        }
    }
    
    /**
     * get connection type of this client.
     *
     * @return ConnectionType.
     */
    public abstract ConnectionType getConnectionType();
    
    /**
     * increase offset of the nacos server port for the rpc server port.
     *
     * @return rpc port offset
     */
    public abstract int rpcPortOffset();
    
    protected void clearContextOnResetRequest() {
        // Default do nothing.
    }
    
    /**
     * send request.
     *
     * @param request request.
     * @return
     */
    public Response request(Request request) throws NacosException {
        int retryTimes = 3;
    
        Exception exceptionToThrow = null;
        while (retryTimes > 0) {
            try {
                Response response = this.currentConnetion.request(request);
                if (response != null && response instanceof ConnectionUnregisterResponse) {
                    clearContextOnResetRequest();
                    switchServerAsync();
                    throw new IllegalStateException("Invalid client status.");
                }
                refereshActiveTimestamp();
                return response;
            } catch (Exception e) {
                LoggerUtils.printIfErrorEnabled(LOGGER,
                        "Fail to send request,connectionId={}, request={},errorMesssage={}", this.connectionId, request,
                        e.getMessage());
                exceptionToThrow = e;
            } finally {
                retryTimes--;
            }
        }
        if (exceptionToThrow != null) {
            throw new NacosException(SERVER_ERROR, exceptionToThrow);
        }
        return null;
    }
    
    /**
     * send aync request.
     *
     * @param request request.
     * @return
     */
    public void asyncRequest(Request request, FutureCallback<Response> callback) throws NacosException {
        this.currentConnetion.asyncRequest(request, callback);
        refereshActiveTimestamp();
    }
    
    /**
     * connect to server.
     *
     * @param serverInfo server address to connect.
     * @return whether sucussfully connect to server.
     */
    public abstract Connection connectToServer(ServerInfo serverInfo);
    
    /**
     * handle server request.
     *
     * @param request request.
     * @return response.
     */
    protected void handleServerRequest(final ServerPushRequest request) {
    
        final AtomicReference<ServerPushResponse> responseRef = new AtomicReference<ServerPushResponse>();
        serverRequestHandlers.forEach(new Consumer<ServerRequestHandler>() {
            @Override
            public void accept(ServerRequestHandler serverRequestHandler) {
                serverRequestHandler.requestReply(request);
            }
        });
    }
    
    /**
     * register connection handler.will be notified wher inner connect changed.
     *
     * @param connectionEventListener connectionEventListener
     */
    public void registerConnectionListener(ConnectionEventListener connectionEventListener) {
        
        LoggerUtils.printIfInfoEnabled(LOGGER,
                "Registry connection listener to current client,connectionId={}, connectionEventListener={}",
                this.connectionId, connectionEventListener.getClass().getName());
        this.connectionEventListeners.add(connectionEventListener);
    }
    
    /**
     * register change listeners ,will be called when server send change notify response th current client.
     *
     * @param serverRequestHandler serverRequestHandler
     */
    public void registerServerPushResponseHandler(ServerRequestHandler serverRequestHandler) {
        LoggerUtils.printIfInfoEnabled(LOGGER,
                " Registry server push response  listener to current client,connectionId={}, connectionEventListener={}",
                this.connectionId, serverRequestHandler.getClass().getName());
    
        this.serverRequestHandlers.add(serverRequestHandler);
    }
    
    /**
     * Getter method for property <tt>serverListFactory</tt>.
     *
     * @return property value of serverListFactory
     */
    public ServerListFactory getServerListFactory() {
        return serverListFactory;
    }
    
    protected ServerInfo nextRpcServer() {
    
        String s = getServerListFactory().genNextServer();
        System.out.println("0...,switch..." + s);
        
        String serverAddress = getServerListFactory().getCurrentServer();
        return resolveServerInfo(serverAddress);
    }
    
    protected ServerInfo currentRpcServer() {
        String serverAddress = getServerListFactory().getCurrentServer();
        return resolveServerInfo(serverAddress);
    }
    
    private ServerInfo resolveServerInfo(String serverAddress) {
        ServerInfo serverInfo = new ServerInfo();
        serverInfo.serverPort = rpcPortOffset();
        if (serverAddress.contains("http")) {
            serverInfo.serverIp = serverAddress.split(":")[1].replaceAll("//", "");
            serverInfo.serverPort += Integer.valueOf(serverAddress.split(":")[2].replaceAll("//", ""));
        } else {
            serverInfo.serverIp = serverAddress.split(":")[0];
            serverInfo.serverPort += Integer.valueOf(serverAddress.split(":")[1]);
        }
        return serverInfo;
    }
    
    public static class ServerInfo {
        
        protected String serverIp;
        
        protected int serverPort;
        
        public ServerInfo() {
    
        }
        
        /**
         * Setter method for property <tt>serverIp</tt>.
         *
         * @param serverIp value to be assigned to property serverIp
         */
        public void setServerIp(String serverIp) {
            this.serverIp = serverIp;
        }
        
        /**
         * Setter method for property <tt>serverPort</tt>.
         *
         * @param serverPort value to be assigned to property serverPort
         */
        public void setServerPort(int serverPort) {
            this.serverPort = serverPort;
        }
    
        /**
         * Getter method for property <tt>serverIp</tt>.
         *
         * @return property value of serverIp
         */
        public String getServerIp() {
            return serverIp;
        }
        
        /**
         * Getter method for property <tt>serverPort</tt>.
         *
         * @return property value of serverPort
         */
        public int getServerPort() {
            return serverPort;
        }
    
        @Override
        public String toString() {
            return "ServerInfo{" + "serverIp='" + serverIp + '\'' + ", serverPort=" + serverPort + '}';
        }
    }
    
    public class ConnectionEvent {
        
        public static final int CONNECTED = 1;
        
        public static final int DISCONNECTED = 0;
        
        int eventType;
        
        public ConnectionEvent(int eventType) {
            this.eventType = eventType;
        }
        
        public boolean isConnected() {
            return eventType == CONNECTED;
        }
        
        public boolean isDisConnected() {
            return eventType == DISCONNECTED;
        }
    }
}

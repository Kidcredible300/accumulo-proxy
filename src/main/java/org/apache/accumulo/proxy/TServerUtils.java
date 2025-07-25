/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.proxy;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.net.ssl.SSLServerSocket;

import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.PropertyType;
import org.apache.accumulo.core.rpc.SslConnectionParams;
import org.apache.accumulo.core.rpc.ThriftUtil;
import org.apache.accumulo.core.rpc.UGIAssumingTransportFactory;
import org.apache.accumulo.core.util.Halt;
import org.apache.accumulo.core.util.HostAndPort;
import org.apache.accumulo.core.util.Pair;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.core.util.threads.Threads;
import org.apache.accumulo.server.rpc.ClientInfoProcessorFactory;
import org.apache.accumulo.server.rpc.CustomNonBlockingServer;
import org.apache.accumulo.server.rpc.CustomThreadedSelectorServer;
import org.apache.accumulo.server.rpc.SaslServerConnectionParams;
import org.apache.accumulo.server.rpc.SaslServerDigestCallbackHandler;
import org.apache.accumulo.server.rpc.ServerAddress;
import org.apache.accumulo.server.rpc.ThriftServerType;
import org.apache.accumulo.server.rpc.TimedProcessor;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.thrift.TProcessor;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TSSLTransportFactory;
import org.apache.thrift.transport.TSaslServerTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.TTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.primitives.Ints;

/**
 * Factory methods for creating Thrift server objects
 */
public class TServerUtils {
  private static final Logger log = LoggerFactory.getLogger(TServerUtils.class);

  /**
   * Static instance, passed to {@link ClientInfoProcessorFactory}, which will contain the client
   * address of any incoming RPC.
   */
  public static final ThreadLocal<String> clientAddress = new ThreadLocal<>();

  /**
   *
   * @param config Accumulo configuration
   * @return A Map object with reserved port numbers as keys and Property objects as values
   */
  static Map<Integer,Property> getReservedPorts(AccumuloConfiguration config,
      Property portProperty) {
    return EnumSet.allOf(Property.class).stream()
        .filter(p -> p.getType() == PropertyType.PORT && p != portProperty)
        .flatMap(rp -> config.getPortStream(rp).mapToObj(portNum -> new Pair<>(portNum, rp)))
        .filter(p -> p.getFirst() != 0).collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
  }

  /**
   * Create a non blocking server with multiple select threads and a custom thread pool that can
   * dynamically resize itself.
   */
  private static ServerAddress createThreadedSelectorServer(HostAndPort address,
      TProcessor processor, TProtocolFactory protocolFactory, final String serverName,
      final int numThreads, final long threadTimeOut, final AccumuloConfiguration conf,
      long timeBetweenThreadChecks, long maxMessageSize) throws TTransportException {

    final TNonblockingServerSocket transport =
        new TNonblockingServerSocket(new InetSocketAddress(address.getHost(), address.getPort()), 0,
            Ints.saturatedCast(maxMessageSize));

    TThreadedSelectorServer.Args options = new TThreadedSelectorServer.Args(transport);

    options.selectorThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
    log.info("selectorThreads : {}", options.selectorThreads);
    options.protocolFactory(protocolFactory);
    options.transportFactory(ThriftUtil.transportFactory(maxMessageSize));
    options.maxReadBufferBytes = maxMessageSize;
    options.stopTimeoutVal(5);

    // Create our own very special thread pool.
    ThreadPoolExecutor pool = createSelfResizingThreadPool(serverName, numThreads, threadTimeOut,
        conf, timeBetweenThreadChecks);

    options.executorService(pool);
    options.processorFactory(new TProcessorFactory(processor));

    if (address.getPort() == 0) {
      address = HostAndPort.fromParts(address.getHost(), transport.getPort());
    }

    return new ServerAddress(new CustomThreadedSelectorServer(options), address);
  }

  /**
   * Create a NonBlockingServer with a single select threads and a custom thread pool that can
   * dynamically resize itself.
   */
  private static ServerAddress createNonBlockingServer(HostAndPort address, TProcessor processor,
      TProtocolFactory protocolFactory, final String serverName, final int numThreads,
      final long threadTimeOut, final AccumuloConfiguration conf, long timeBetweenThreadChecks,
      long maxMessageSize) throws TTransportException {

    final TNonblockingServerSocket transport =
        new TNonblockingServerSocket(new InetSocketAddress(address.getHost(), address.getPort()), 0,
            Ints.saturatedCast(maxMessageSize));
    final CustomNonBlockingServer.Args options = new CustomNonBlockingServer.Args(transport);

    options.protocolFactory(protocolFactory);
    options.transportFactory(ThriftUtil.transportFactory(maxMessageSize));
    options.maxReadBufferBytes = maxMessageSize;
    options.stopTimeoutVal(5);

    // Create our own very special thread pool.
    ThreadPoolExecutor pool = createSelfResizingThreadPool(serverName, numThreads, threadTimeOut,
        conf, timeBetweenThreadChecks);

    options.executorService(pool);
    options.processorFactory(new TProcessorFactory(processor));

    if (address.getPort() == 0) {
      address = HostAndPort.fromParts(address.getHost(), transport.getPort());
    }

    return new ServerAddress(new CustomNonBlockingServer(options), address);
  }

  /**
   * Creates a {@link ThreadPoolExecutor} which uses a ScheduledThreadPoolExecutor to inspect the
   * core pool size and number of active threads of the {@link ThreadPoolExecutor} and increase or
   * decrease the core pool size based on activity (excessive or lack thereof).
   *
   * @param serverName A name to describe the thrift server this executor will service
   * @param executorThreads The minimum number of threads for the executor
   * @param threadTimeOut The time after which threads are allowed to terminate including core
   *        threads. If set to 0, the core threads will indefinitely stay running waiting for work.
   * @param conf Accumulo Configuration
   * @param timeBetweenThreadChecks The amount of time, in millis, between attempts to resize the
   *        executor thread pool
   * @return A {@link ThreadPoolExecutor} which will resize itself automatically
   */
  private static ThreadPoolExecutor createSelfResizingThreadPool(final String serverName,
      final int executorThreads, long threadTimeOut, final AccumuloConfiguration conf,
      long timeBetweenThreadChecks) {
    final ThreadPoolExecutor pool = ThreadPools.getServerThreadPools()
        .getPoolBuilder(serverName + "-ClientPool").numCoreThreads(executorThreads)
        .numMaxThreads(executorThreads).withTimeOut(threadTimeOut, TimeUnit.MILLISECONDS)
        .enableThreadPoolMetrics(true).withQueue(new LinkedBlockingQueue<>()).build();
    // periodically adjust the number of threads we need by checking how busy our threads are
    ThreadPools.watchCriticalFixedDelay(conf, timeBetweenThreadChecks, () -> {
      // there is a minor race condition between sampling the current state of the thread pool
      // and adjusting it however, this isn't really an issue, since it adjusts periodically
      if (pool.getCorePoolSize() <= pool.getActiveCount()) {
        int larger = pool.getCorePoolSize() + Math.min(pool.getQueue().size(), 2);
        ThreadPools.resizePool(pool, () -> larger, serverName + "-ClientPool");
      } else {
        if (pool.getCorePoolSize() > pool.getActiveCount() + 3) {
          int smaller = Math.max(executorThreads, pool.getCorePoolSize() - 1);
          ThreadPools.resizePool(pool, () -> smaller, serverName + "-ClientPool");
        }
      }
    });
    return pool;
  }

  /**
   * Creates a TThreadPoolServer for normal unsecure operation. Useful for comparing performance
   * against SSL or SASL transports.
   *
   * @param address Address to bind to
   * @param processor TProcessor for the server
   * @param maxMessageSize Maximum size of a Thrift message allowed
   * @return A configured TThreadPoolServer and its bound address information
   */
  private static ServerAddress createBlockingServer(HostAndPort address, TProcessor processor,
      TProtocolFactory protocolFactory, long maxMessageSize, String serverName, int numThreads,
      long threadTimeOut, final AccumuloConfiguration conf, long timeBetweenThreadChecks)
      throws TTransportException {

    InetSocketAddress isa = new InetSocketAddress(address.getHost(), address.getPort());
    // Must use an ISA, providing only a port would ignore the hostname given
    TServerSocket transport = new TServerSocket(isa);
    ThreadPoolExecutor pool = createSelfResizingThreadPool(serverName, numThreads, threadTimeOut,
        conf, timeBetweenThreadChecks);
    TThreadPoolServer server = createTThreadPoolServer(transport, processor,
        ThriftUtil.transportFactory(maxMessageSize), protocolFactory, pool);

    if (address.getPort() == 0) {
      address =
          HostAndPort.fromParts(address.getHost(), transport.getServerSocket().getLocalPort());
      log.info("Blocking Server bound on {}", address);
    }

    return new ServerAddress(server, address);

  }

  /**
   * Create a {@link TThreadPoolServer} with the provided server transport, processor and transport
   * factory.
   *
   * @param transport TServerTransport for the server
   * @param processor TProcessor for the server
   * @param transportFactory TTransportFactory for the server
   */
  private static TThreadPoolServer createTThreadPoolServer(TServerTransport transport,
      TProcessor processor, TTransportFactory transportFactory, TProtocolFactory protocolFactory,
      ExecutorService service) {
    TThreadPoolServer.Args options = new TThreadPoolServer.Args(transport);
    options.protocolFactory(protocolFactory);
    options.transportFactory(transportFactory);
    options.processorFactory(new ClientInfoProcessorFactory(clientAddress, processor));
    if (service != null) {
      options.executorService(service);
    }
    return new TThreadPoolServer(options);
  }

  /**
   * Create the Thrift server socket for RPC running over SSL.
   *
   * @param port Port of the server socket to bind to
   * @param timeout Socket timeout
   * @param address Address to bind the socket to
   * @param params SSL parameters
   * @return A configured TServerSocket configured to use SSL
   */
  private static TServerSocket getSslServerSocket(int port, int timeout, InetAddress address,
      SslConnectionParams params) throws TTransportException {
    TServerSocket tServerSock;
    if (params.useJsse()) {
      tServerSock =
          TSSLTransportFactory.getServerSocket(port, timeout, params.isClientAuth(), address);
    } else {
      tServerSock = TSSLTransportFactory.getServerSocket(port, timeout, address,
          params.getTSSLTransportParameters());
    }

    final ServerSocket serverSock = tServerSock.getServerSocket();
    if (serverSock instanceof SSLServerSocket) {
      SSLServerSocket sslServerSock = (SSLServerSocket) serverSock;
      String[] protocols = params.getServerProtocols();

      // Be nice for the user and automatically remove protocols that might not exist in their JVM.
      // Keeps us from forcing config alterations too
      // e.g. TLSv1.1 and TLSv1.2 don't exist in JDK6
      Set<String> socketEnabledProtocols =
          new HashSet<>(Arrays.asList(sslServerSock.getEnabledProtocols()));
      // Keep only the enabled protocols that were specified by the configuration
      socketEnabledProtocols.retainAll(Arrays.asList(protocols));
      if (socketEnabledProtocols.isEmpty()) {
        // Bad configuration...
        throw new RuntimeException(
            "No available protocols available for secure socket. Available protocols: "
                + Arrays.toString(sslServerSock.getEnabledProtocols()) + ", allowed protocols: "
                + Arrays.toString(protocols));
      }

      // Set the protocol(s) on the server socketlong
      sslServerSock.setEnabledProtocols(socketEnabledProtocols.toArray(new String[0]));
    }

    return tServerSock;
  }

  /**
   * Create a Thrift SSL server.
   *
   * @param address host and port to bind to
   * @param processor TProcessor for the server
   * @param socketTimeout Socket timeout
   * @param sslParams SSL parameters
   * @return A ServerAddress with the bound-socket information and the Thrift server
   */
  private static ServerAddress createSslThreadPoolServer(HostAndPort address, TProcessor processor,
      TProtocolFactory protocolFactory, long socketTimeout, SslConnectionParams sslParams,
      String serverName, int numThreads, long threadTimeOut, final AccumuloConfiguration conf,
      long timeBetweenThreadChecks) throws TTransportException {
    TServerSocket transport;
    try {
      transport = getSslServerSocket(address.getPort(), (int) socketTimeout,
          InetAddress.getByName(address.getHost()), sslParams);
    } catch (UnknownHostException e) {
      throw new TTransportException(e);
    }

    if (address.getPort() == 0) {
      address =
          HostAndPort.fromParts(address.getHost(), transport.getServerSocket().getLocalPort());
      log.info("SSL Thread Pool Server bound on {}", address);
    }

    ThreadPoolExecutor pool = createSelfResizingThreadPool(serverName, numThreads, threadTimeOut,
        conf, timeBetweenThreadChecks);

    return new ServerAddress(createTThreadPoolServer(transport, processor,
        ThriftUtil.transportFactory(), protocolFactory, pool), address);
  }

  private static ServerAddress createSaslThreadPoolServer(HostAndPort address, TProcessor processor,
      TProtocolFactory protocolFactory, long socketTimeout, SaslServerConnectionParams params,
      final String serverName, final int numThreads, final long threadTimeOut,
      final AccumuloConfiguration conf, long timeBetweenThreadChecks) throws TTransportException {
    // We'd really prefer to use THsHaServer (or similar) to avoid 1 RPC == 1 Thread that the
    // TThreadPoolServer does,
    // but sadly this isn't the case. Because TSaslTransport needs to issue a handshake when it
    // open()'s which will fail
    // when the server does an accept() to (presumably) wake up the eventing system.
    log.info("Creating SASL thread pool thrift server on listening on {}:{}", address.getHost(),
        address.getPort());
    InetSocketAddress isa = new InetSocketAddress(address.getHost(), address.getPort());
    // Must use an ISA, providing only a port would ignore the hostname given
    TServerSocket transport = new TServerSocket(isa, (int) socketTimeout);

    String hostname, fqdn;
    try {
      hostname = InetAddress.getByName(address.getHost()).getCanonicalHostName();
      fqdn = InetAddress.getLocalHost().getCanonicalHostName();
    } catch (UnknownHostException e) {
      transport.close();
      throw new TTransportException(e);
    }

    // If we can't get a real hostname from the provided host test, use the hostname from DNS for
    // localhost
    if ("0.0.0.0".equals(hostname)) {
      hostname = fqdn;
    }

    // ACCUMULO-3497 an easy sanity check we can perform for the user when SASL is enabled. Clients
    // and servers have to agree upon the FQDN
    // so that the SASL handshake can occur. If the provided hostname doesn't match the FQDN for
    // this host, fail quickly and inform them to update
    // their configuration.
    if (!hostname.equals(fqdn)) {
      log.error("Expected hostname of '{}' but got '{}'. Ensure the entries in"
          + " the Accumulo hosts files (e.g. managers, tservers) are the FQDN for"
          + " each host when using SASL.", fqdn, hostname);
      transport.close();
      throw new RuntimeException("SASL requires that the address the thrift"
          + " server listens on is the same as the FQDN for this host");
    }

    final UserGroupInformation serverUser;
    try {
      serverUser = UserGroupInformation.getLoginUser();
    } catch (IOException e) {
      transport.close();
      ThriftUtil.checkIOExceptionCause(e);
      throw new TTransportException(e);
    }

    log.debug("Logged in as {}, creating TSaslServerTransport factory with {}/{}", serverUser,
        params.getKerberosServerPrimary(), hostname);

    // Make the SASL transport factory with the instance and primary from the kerberos server
    // principal, SASL properties
    // and the SASL callback handler from Hadoop to ensure authorization ID is the authentication
    // ID. Despite the 'protocol' argument seeming to be useless, it
    // *must* be the primary of the server.
    TSaslServerTransport.Factory saslTransportFactory = new TSaslServerTransport.Factory();
    saslTransportFactory.addServerDefinition(ThriftUtil.GSSAPI, params.getKerberosServerPrimary(),
        hostname, params.getSaslProperties(), new SaslRpcServer.SaslGssCallbackHandler());

    if (params.getSecretManager() != null) {
      log.info("Adding DIGEST-MD5 server definition for delegation tokens");
      saslTransportFactory.addServerDefinition(ThriftUtil.DIGEST_MD5,
          params.getKerberosServerPrimary(), hostname, params.getSaslProperties(),
          new SaslServerDigestCallbackHandler(params.getSecretManager()));
    } else {
      log.info("SecretManager is null, not adding support for delegation token authentication");
    }

    // Make sure the TTransportFactory is performing a UGI.doAs
    TTransportFactory ugiTransportFactory =
        new UGIAssumingTransportFactory(saslTransportFactory, serverUser);

    if (address.getPort() == 0) {
      // If we chose a port dynamically, make a new use it (along with the proper hostname)
      address =
          HostAndPort.fromParts(address.getHost(), transport.getServerSocket().getLocalPort());
      log.info("SASL thrift server bound on {}", address);
    }

    ThreadPoolExecutor pool = createSelfResizingThreadPool(serverName, numThreads, threadTimeOut,
        conf, timeBetweenThreadChecks);

    final TThreadPoolServer server =
        createTThreadPoolServer(transport, processor, ugiTransportFactory, protocolFactory, pool);

    return new ServerAddress(server, address);
  }

  /**
   * Start the appropriate Thrift server (SSL or non-blocking server) for the given parameters.
   * Non-null SSL parameters will cause an SSL server to be started.
   *
   * @return A ServerAddress encapsulating the Thrift server created and the host/port which it is
   *         bound to.
   */
  public static ServerAddress startTServer(ThriftServerType serverType, TimedProcessor processor,
      TProtocolFactory protocolFactory, String serverName, String threadName, int numThreads,
      long threadTimeOut, final AccumuloConfiguration conf, long timeBetweenThreadChecks,
      long maxMessageSize, SslConnectionParams sslParams, SaslServerConnectionParams saslParams,
      long serverSocketTimeout, HostAndPort... addresses) throws TTransportException {

    // This is presently not supported. It's hypothetically possible, I believe, to work, but it
    // would require changes in how the transports
    // work at the Thrift layer to ensure that both the SSL and SASL handshakes function. SASL's
    // quality of protection addresses privacy issues.
    checkArgument(sslParams == null || saslParams == null,
        "Cannot start a Thrift server using both SSL and SASL");

    ServerAddress serverAddress = null;
    for (HostAndPort address : addresses) {
      try {
        switch (serverType) {
          case SSL:
            log.debug("Instantiating SSL Thrift server");
            serverAddress = createSslThreadPoolServer(address, processor, protocolFactory,
                serverSocketTimeout, sslParams, serverName, numThreads, threadTimeOut, conf,
                timeBetweenThreadChecks);
            break;
          case SASL:
            log.debug("Instantiating SASL Thrift server");
            serverAddress = createSaslThreadPoolServer(address, processor, protocolFactory,
                serverSocketTimeout, saslParams, serverName, numThreads, threadTimeOut, conf,
                timeBetweenThreadChecks);
            break;
          case THREADPOOL:
            log.debug("Instantiating unsecure TThreadPool Thrift server");
            serverAddress =
                createBlockingServer(address, processor, protocolFactory, maxMessageSize,
                    serverName, numThreads, threadTimeOut, conf, timeBetweenThreadChecks);
            break;
          case THREADED_SELECTOR:
            log.debug("Instantiating default, unsecure Threaded selector Thrift server");
            serverAddress =
                createThreadedSelectorServer(address, processor, protocolFactory, serverName,
                    numThreads, threadTimeOut, conf, timeBetweenThreadChecks, maxMessageSize);
            break;
          case CUSTOM_HS_HA:
            log.debug("Instantiating unsecure custom half-async Thrift server");
            serverAddress = createNonBlockingServer(address, processor, protocolFactory, serverName,
                numThreads, threadTimeOut, conf, timeBetweenThreadChecks, maxMessageSize);
            break;
          default:
            throw new IllegalArgumentException("Unknown server type " + serverType);
        }
        break;
      } catch (TTransportException e) {
        log.warn("Error attempting to create server at {}. Error: {}", address, e.getMessage());
      }
    }
    if (serverAddress == null) {
      throw new TTransportException(
          "Unable to create server on addresses: " + Arrays.toString(addresses));
    }

    final TServer finalServer = serverAddress.server;

    Threads.createThread(threadName, () -> {
      try {
        finalServer.serve();
      } catch (Error e) {
        Halt.halt("Unexpected error in TThreadPoolServer " + e + ", halting.", 1);
      }
    }).start();

    // check for the special "bind to everything address"
    if (serverAddress.address.getHost().equals("0.0.0.0")) {
      // can't get the address from the bind, so we'll do our best to invent our hostname
      try {
        serverAddress = new ServerAddress(finalServer, HostAndPort
            .fromParts(InetAddress.getLocalHost().getHostName(), serverAddress.address.getPort()));
      } catch (UnknownHostException e) {
        throw new TTransportException(e);
      }
    }
    return serverAddress;
  }
}

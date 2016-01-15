/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix;

import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.transport.*;
import io.atomix.catalyst.util.Assert;
import io.atomix.catalyst.util.ConfigurationException;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.client.ServerSelectionStrategy;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.cluster.Member;
import io.atomix.copycat.server.storage.Storage;
import io.atomix.manager.ResourceClient;
import io.atomix.manager.ResourceServer;
import io.atomix.manager.state.ResourceManagerState;
import io.atomix.resource.ResourceRegistry;
import io.atomix.resource.ResourceTypeResolver;
import io.atomix.resource.ServiceLoaderResourceResolver;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Provides an interface for creating and operating on {@link io.atomix.resource.Resource}s as a stateful node.
 * <p>
 * Replicas serve as a hybrid {@link AtomixClient} and server to allow a server to be embedded
 * in an application. From the perspective of state, replicas behave like servers in that they
 * maintain a replicated state machine for {@link io.atomix.resource.Resource}s and fully participate in the underlying
 * consensus algorithm. From the perspective of resources, replicas behave like {@link AtomixClient}s in that
 * they may themselves create and modify distributed resources.
 * <p>
 * To create a replica, use the {@link #builder(Address, Address...)} builder factory. Each replica must
 * be initially configured with a server {@link Address} and a list of addresses for other members of the
 * core cluster. Note that the list of member addresses does not have to include the local server nor does
 * it have to include all the servers in the cluster. As long as the replica can reach one live member of
 * the cluster, it can join.
 * <pre>
 *   {@code
 *   List<Address> members = Arrays.asList(new Address("123.456.789.0", 5000), new Address("123.456.789.1", 5000));
 *   Atomix atomix = AtomixReplica.builder(address, members)
 *     .withTransport(new NettyTransport())
 *     .withStorage(new Storage(StorageLevel.MEMORY))
 *     .build();
 *   }
 * </pre>
 * Replicas must be configured with a {@link Transport} and {@link Storage}. By default, if no transport is
 * configured, the {@code NettyTransport} will be used and will thus be expected to be available on the classpath.
 * Similarly, if no storage module is configured, replicated commit logs will be written to
 * {@code System.getProperty("user.dir")} with a default log name.
 * <p>
 * Atomix clusters are not restricted solely to servers or {@link AtomixReplica}s. Clusters may be
 * composed from a mixture of each type of server.
 * <p>
 * <b>Replica lifecycle</b>
 * <p>
 * When the replica is {@link #open() started}, the replica will attempt to contact members in the configured
 * startup {@link Address} list. If any of the members are already in an active state, the replica will request
 * to join the cluster. During the process of joining the cluster, the replica will notify the current cluster
 * leader of its existence. If the leader already knows about the joining replica, the replica will immediately
 * join and become a full voting member. If the joining replica is not yet known to the rest of the cluster,
 * it will join the cluster in a <em>passive</em> state in which it receives replicated state from other
 * servers in the cluster but does not participate in elections or other quorum-based aspects of the
 * underlying consensus algorithm. Once the joining replica is caught up with the rest of the cluster, the
 * leader will promote it to a full voting member.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public final class AtomixReplica extends Atomix {

  /**
   * Returns a new Atomix replica builder.
   * <p>
   * The provided set of members will be used to connect to the other members in the Raft cluster.
   *
   * @param address The address through which clients and servers connect to the replica.
   * @param members The cluster members to which to connect.
   * @return The replica builder.
   */
  public static Builder builder(Address address, Address... members) {
    return builder(address, address, Arrays.asList(Assert.notNull(members, "members")));
  }

  /**
   * Returns a new Atomix replica builder.
   * <p>
   * The provided set of members will be used to connect to the other members in the Raft cluster.
   *
   * @param address The address through which clients and servers connect to the replica.
   * @param members The cluster members to which to connect.
   * @return The replica builder.
   */
  public static Builder builder(Address address, Collection<Address> members) {
    return new Builder(address, address, members);
  }

  /**
   * Returns a new Atomix replica builder.
   * <p>
   * The provided set of members will be used to connect to the other members in the Raft cluster.
   *
   * @param clientAddress The address through which clients connect to the server.
   * @param serverAddress The local server member address.
   * @param members The cluster members to which to connect.
   * @return The replica builder.
   */
  public static Builder builder(Address clientAddress, Address serverAddress, Address... members) {
    return builder(clientAddress, serverAddress, Arrays.asList(Assert.notNull(members, "members")));
  }

  /**
   * Returns a new Atomix replica builder.
   * <p>
   * The provided set of members will be used to connect to the other members in the Raft cluster.
   *
   * @param clientAddress The address through which clients connect to the server.
   * @param serverAddress The local server member address.
   * @param members The cluster members to which to connect.
   * @return The replica builder.
   */
  public static Builder builder(Address clientAddress, Address serverAddress, Collection<Address> members) {
    return new Builder(clientAddress, serverAddress, members);
  }

  private final ResourceServer server;
  private final int quorumHint;
  private final int backupCount;

  /**
   * @throws NullPointerException if {@code client} or {@code server} are null
   */
  private AtomixReplica(ResourceClient client, ResourceServer server, int quorumHint, int backupCount) {
    super(client);
    this.server = Assert.notNull(server, "server");
    this.quorumHint = quorumHint;
    this.backupCount = backupCount;

    server.server().cluster().members().forEach(m -> {
      m.onTypeChange(t -> rebalance());
      m.onStatusChange(s -> rebalance());
    });

    server.server().cluster().onLeaderElection(l -> rebalance());

    server.server().cluster().onJoin(m -> {
      m.onTypeChange(t -> rebalance());
      m.onStatusChange(s -> rebalance());
      rebalance();
    });

    server.server().cluster().onLeave(m -> rebalance());
  }

  /**
   * Rebalances the cluster.
   */
  private void rebalance() {
    if (!server.server().cluster().member().equals(server.server().cluster().leader())) {
      return;
    }

    Collection<Member> members = server.server().cluster().members();
    Member member = server.server().cluster().member();

    Collection<Member> active = members.stream().filter(m -> m.type() == Member.Type.ACTIVE || m.type() == Member.Type.PROMOTABLE).collect(Collectors.toList());
    Collection<Member> passive = members.stream().filter(m -> m.type() == Member.Type.PASSIVE).collect(Collectors.toList());
    Collection<Member> reserve = members.stream().filter(m -> m.type() == Member.Type.RESERVE).collect(Collectors.toList());

    int totalActiveCount = active.size();
    int totalPassiveCount = passive.size();

    long availableActiveCount = active.stream().filter(m -> m.status() == Member.Status.AVAILABLE).count();
    long availablePassiveCount = passive.stream().filter(m -> m.status() == Member.Status.AVAILABLE).count();
    long availableReserveCount = reserve.stream().filter(m -> m.status() == Member.Status.AVAILABLE).count();

    // If the number of available active members is less than the quorum hint, promote a passive or reserve member.
    if (availableActiveCount < quorumHint) {
      // If a passive member is available, promote it.
      if (availablePassiveCount > 0) {
        passive.stream().filter(m -> m.status() == Member.Status.AVAILABLE).findFirst().get()
          .promote(Member.Type.PROMOTABLE)
          .thenRun(this::rebalance);
        return;
      }
      // If a reserve member is available, promote it.
      else if (availableReserveCount > 0) {
        reserve.stream().filter(m -> m.status() == Member.Status.AVAILABLE).findFirst().get()
          .promote(Member.Type.PROMOTABLE)
          .thenRun(this::rebalance);
        return;
      }
    }

    // If the total number of active members is greater than the quorum hint, demote an active member.
    // Preferably, we want to demote a member that is unavailable.
    if (totalActiveCount > quorumHint) {
      // If the number of available passive members is less than the required number, demote an active
      // member to passive.
      if (availablePassiveCount < quorumHint * backupCount) {
        active.stream().filter(m -> m.status() == Member.Status.UNAVAILABLE).findFirst()
          .orElseGet(() -> active.stream().filter(m -> !m.equals(member)).findAny().get())
          .demote(Member.Type.PASSIVE)
          .thenRun(this::rebalance);
        return;
      }
      // Otherwise, demote an active member to reserve.
      else {
        active.stream().filter(m -> m.status() == Member.Status.UNAVAILABLE).findAny()
          .orElseGet(() -> active.stream().filter(m -> !m.equals(member)).findAny().get())
          .demote(Member.Type.RESERVE)
          .thenRun(this::rebalance);
        return;
      }
    }

    // If the number of available passive members is less than the required number of passive members,
    // promote a reserve member.
    if (availablePassiveCount < quorumHint * backupCount) {
      // If any reserve members are available, promote to passive.
      if (availableReserveCount > 0) {
        reserve.stream().filter(m -> m.status() == Member.Status.AVAILABLE).findFirst().get()
          .promote(Member.Type.PASSIVE)
          .thenRun(this::rebalance);
        return;
      }
    }

    // If the total number of passive members is greater than the required number of passive members,
    // demote a passive member. Preferably we demote an unavailable member.
    if (totalPassiveCount > quorumHint * backupCount) {
      passive.stream().filter(m -> m.status() == Member.Status.UNAVAILABLE).findAny()
        .orElseGet(() -> passive.stream().findAny().get())
        .demote(Member.Type.RESERVE)
        .thenRun(this::rebalance);
    }
  }

  @Override
  public CompletableFuture<Atomix> open() {
    return server.open().thenCompose(v -> super.open()).thenApply(v -> this);
  }

  @Override
  public CompletableFuture<Void> close() {
    return super.close().thenCompose(v -> server.close());
  }

  /**
   * Combined server selection strategy.
   */
  private static class CombinedSelectionStrategy implements ServerSelectionStrategy {
    private final List<Address> addresses;

    private CombinedSelectionStrategy(Address address) {
      this.addresses = Collections.singletonList(address);
    }

    @Override
    public List<Address> selectConnections(Address leader, List<Address> servers) {
      return addresses;
    }
  }

  /**
   * Combined transport that aids in the local client communicating directly with the local server.
   */
  private static class CombinedTransport implements Transport {
    private final Transport local;
    private final Transport remote;

    private CombinedTransport(Transport local, Transport remote) {
      this.local = local;
      this.remote = remote;
    }

    @Override
    public Client client() {
      return remote.client();
    }

    @Override
    public Server server() {
      return new CombinedServer(local.server(), remote.server());
    }
  }

  /**
   * Combined server that access connections from the local client directly.
   */
  private static class CombinedServer implements Server {
    private final Server local;
    private final Server remote;

    private CombinedServer(Server local, Server remote) {
      this.local = local;
      this.remote = remote;
    }

    @Override
    public CompletableFuture<Void> listen(Address address, Consumer<Connection> listener) {
      Assert.notNull(address, "address");
      Assert.notNull(listener, "listener");
      return local.listen(address, listener).thenCompose(v -> remote.listen(address, listener));
    }

    @Override
    public CompletableFuture<Void> close() {
      return local.close().thenCompose(v -> remote.close());
    }
  }

  /**
   * Builds an {@link AtomixReplica}.
   * <p>
   * The replica builder configures an {@link AtomixReplica} to listen for connections from clients and other
   * servers/replica, connect to other servers in a cluster, and manage a replicated log. To create a replica builder,
   * use the {@link #builder(Address, Address...)} method:
   * <pre>
   *   {@code
   *   Atomix replica = AtomixReplica.builder(address, members)
   *     .withTransport(new NettyTransport())
   *     .withStorage(Storage.builder()
   *       .withDirectory("logs")
   *       .withStorageLevel(StorageLevel.MAPPED)
   *       .build())
   *     .build();
   *   }
   * </pre>
   * The two most essential components of the builder are the {@link Transport} and {@link Storage}. The
   * transport provides the mechanism for the replica to communicate with clients and other replicas in the
   * cluster. All servers, clients, and replicas must implement the same {@link Transport} type. The {@link Storage}
   * module configures how the replica manages the replicated log. Logs can be written to disk or held in
   * memory or memory-mapped files.
   */
  public static class Builder extends io.atomix.catalyst.util.Builder<AtomixReplica> {
    private final Address clientAddress;
    private final CopycatClient.Builder clientBuilder;
    private final CopycatServer.Builder serverBuilder;
    private Transport clientTransport;
    private Transport serverTransport;
    private int quorumHint;
    private int backupCount;
    private LocalServerRegistry localRegistry = new LocalServerRegistry();
    private ResourceTypeResolver resourceResolver = new ServiceLoaderResourceResolver();

    private Builder(Address clientAddress, Address serverAddress, Collection<Address> members) {
      this.clientAddress = Assert.notNull(clientAddress, "clientAddress");
      this.clientBuilder = CopycatClient.builder(Collections.singleton(clientAddress));
      this.serverBuilder = CopycatServer.builder(clientAddress, serverAddress, members);
      this.quorumHint = members.size();
    }

    /**
     * Sets the replica transport, returning the replica builder for method chaining.
     * <p>
     * The configured transport should be the same transport as all other nodes in the cluster.
     * If no transport is explicitly provided, the instance will default to the {@code NettyTransport}
     * if available on the classpath.
     *
     * @param transport The replica transport.
     * @return The replica builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withTransport(Transport transport) {
      this.serverTransport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the client transport, returning the server builder for method chaining.
     * <p>
     * The configured transport should be the same transport as all clients.
     * If no transport is explicitly provided, the instance will default to the {@code NettyTransport}
     * if available on the classpath.
     *
     * @param transport The server transport.
     * @return The server builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withClientTransport(Transport transport) {
      this.clientTransport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the server transport, returning the server builder for method chaining.
     * <p>
     * The configured transport should be the same transport as all other servers in the cluster.
     * If no transport is explicitly provided, the instance will default to the {@code NettyTransport}
     * if available on the classpath.
     *
     * @param transport The server transport.
     * @return The server builder.
     * @throws NullPointerException if {@code transport} is null
     */
    public Builder withServerTransport(Transport transport) {
      this.serverTransport = Assert.notNull(transport, "transport");
      return this;
    }

    /**
     * Sets the serializer, returning the replica builder for method chaining.
     * <p>
     * The serializer will be used to serialize and deserialize operations that are sent over the wire.
     *
     * @param serializer The serializer.
     * @return The replica builder.
     * @throws NullPointerException if {@code serializer} is null
     */
    public Builder withSerializer(Serializer serializer) {
      clientBuilder.withSerializer(serializer);
      serverBuilder.withSerializer(serializer);
      return this;
    }

    /**
     * Sets the quorum hint.
     *
     * @param quorumHint The quorum hint.
     * @return The replica builder.
     */
    public Builder withQuorumHint(int quorumHint) {
      this.quorumHint = Assert.argNot(quorumHint, quorumHint <= 0, "quorumHint must be positive");
      return this;
    }

    /**
     * Sets the backup count.
     *
     * @param backupCount The backup count.
     * @return The replica builder.
     */
    public Builder withBackupCount(int backupCount) {
      this.backupCount = Assert.argNot(backupCount, backupCount < 0, "backupCount must be positive");
      return this;
    }

    /**
     * Sets the Atomix resource type resolver.
     *
     * @param resolver The resource type resolver.
     * @return The Atomix builder.
     */
    public Builder withResourceResolver(ResourceTypeResolver resolver) {
      this.resourceResolver = Assert.notNull(resolver, "resolver");
      return this;
    }

    /**
     * Sets the replica storage module, returning the replica builder for method chaining.
     * <p>
     * The storage module is the interface the replica will use to store the persistent replicated log.
     * For simple configurations, users can simply construct a {@link Storage} object:
     * <pre>
     *   {@code
     *   Atomix replica = AtomixReplica.builder(address, members)
     *     .withStorage(new Storage("logs"))
     *     .build();
     *   }
     * </pre>
     * For more complex storage configurations, use the {@link io.atomix.copycat.server.storage.Storage.Builder}:
     * <pre>
     *   {@code
     *   Atomix replica = AtomixReplica.builder(address, members)
     *     .withStorage(Storage.builder()
     *       .withDirectory("logs")
     *       .withStorageLevel(StorageLevel.MAPPED)
     *       .withCompactionThreads(2)
     *       .build())
     *     .build();
     *   }
     * </pre>
     *
     * @param storage The replica storage module.
     * @return The replica builder.
     * @throws NullPointerException if {@code storage} is null
     */
    public Builder withStorage(Storage storage) {
      serverBuilder.withStorage(storage);
      return this;
    }

    /**
     * Sets the replica election timeout, returning the replica builder for method chaining.
     * <p>
     * The election timeout is the duration since last contact with the cluster leader after which
     * the replica should start a new election. The election timeout should always be significantly
     * larger than {@link #withHeartbeatInterval(Duration)} in order to prevent unnecessary elections.
     *
     * @param electionTimeout The replica election timeout in milliseconds.
     * @return The replica builder.
     * @throws NullPointerException if {@code electionTimeout} is null
     */
    public Builder withElectionTimeout(Duration electionTimeout) {
      serverBuilder.withElectionTimeout(electionTimeout);
      return this;
    }

    /**
     * Sets the replica heartbeat interval, returning the replica builder for method chaining.
     * <p>
     * The heartbeat interval is the interval at which the replica, if elected leader, should contact
     * other replicas within the cluster to maintain its leadership. The heartbeat interval should
     * always be some fraction of {@link #withElectionTimeout(Duration)}.
     *
     * @param heartbeatInterval The replica heartbeat interval in milliseconds.
     * @return The replica builder.
     * @throws NullPointerException if {@code heartbeatInterval} is null
     */
    public Builder withHeartbeatInterval(Duration heartbeatInterval) {
      serverBuilder.withHeartbeatInterval(heartbeatInterval);
      return this;
    }

    /**
     * Sets the replica session timeout, returning the replica builder for method chaining.
     * <p>
     * The session timeout is assigned by the replica to a client which opens a new session. The session timeout
     * dictates the interval at which the client must send keep-alive requests to the cluster to maintain its
     * session. If a client fails to communicate with the cluster for larger than the configured session
     * timeout, its session may be expired.
     *
     * @param sessionTimeout The replica session timeout in milliseconds.
     * @return The replica builder.
     * @throws NullPointerException if {@code sessionTimeout} is null
     */
    public Builder withSessionTimeout(Duration sessionTimeout) {
      serverBuilder.withSessionTimeout(sessionTimeout);
      return this;
    }

    /**
     * Builds the replica.
     * <p>
     * If no {@link Transport} was configured for the replica, the builder will attempt to create a
     * {@code NettyTransport} instance. If {@code io.atomix.catalyst.transport.NettyTransport} is not available
     * on the classpath, a {@link ConfigurationException} will be thrown.
     * <p>
     * Once the replica is built, it is not yet connected to the cluster. To connect the replica to the cluster,
     * call the asynchronous {@link #open()} method.
     *
     * @return The built replica.
     * @throws ConfigurationException if the replica is misconfigured
     */
    @Override
    public AtomixReplica build() {
      // If no transport was configured by the user, attempt to load the Netty transport.
      if (serverTransport == null) {
        try {
          serverTransport = (Transport) Class.forName("io.atomix.catalyst.transport.NettyTransport").newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
          throw new ConfigurationException("transport not configured");
        }
      }

      // Create a resource registry and resolve resources with the configured resolver.
      ResourceRegistry registry = new ResourceRegistry();
      resourceResolver.resolve(registry);

      // Configure the client and server with a transport that routes all local client communication
      // directly through the local server, ensuring we don't incur unnecessary network traffic by
      // sending operations to a remote server when a local server is already available in the same JVM.=
      clientBuilder.withTransport(new LocalTransport(localRegistry))
        .withServerSelectionStrategy(new CombinedSelectionStrategy(clientAddress))
        .build();

      // Construct the underlying CopycatServer. The server should have been configured with a CombinedTransport
      // that facilitates the local client connecting directly to the server.
      if (clientTransport != null) {
        serverBuilder.withClientTransport(new CombinedTransport(new LocalTransport(localRegistry), clientTransport))
          .withServerTransport(serverTransport);
      } else {
        serverBuilder.withTransport(new CombinedTransport(new LocalTransport(localRegistry), serverTransport));
      }

      // Set the server resource state machine.
      serverBuilder.withStateMachine(new ResourceManagerState(registry));

      return new AtomixReplica(new ResourceClient(new AtomixCopycatClient(clientBuilder.build(), serverTransport), registry), new ResourceServer(serverBuilder.build()), quorumHint, backupCount);
    }
  }

}

/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodesHelper;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.FailedShard;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.WriteLoadForecaster;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.allocator.DesiredBalance;
import org.elasticsearch.cluster.routing.allocation.allocator.DesiredBalanceShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.DeterministicTaskQueue;
import org.elasticsearch.core.Strings;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.snapshots.SnapshotShardSizeInfo;
import org.elasticsearch.snapshots.SnapshotsInfoService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.gateway.TestGatewayAllocator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.ClusterModule.BALANCED_ALLOCATOR;
import static org.elasticsearch.cluster.ClusterModule.DESIRED_BALANCE_ALLOCATOR;
import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;

public abstract class ESAllocationTestCase extends ESTestCase {
    private static final ClusterSettings EMPTY_CLUSTER_SETTINGS = new ClusterSettings(
        Settings.EMPTY,
        ClusterSettings.BUILT_IN_CLUSTER_SETTINGS
    );

    public static final SnapshotsInfoService SNAPSHOT_INFO_SERVICE_WITH_NO_SHARD_SIZES = () -> new SnapshotShardSizeInfo(Map.of()) {
        @Override
        public Long getShardSize(ShardRouting shardRouting) {
            assert shardRouting.recoverySource().getType() == RecoverySource.Type.SNAPSHOT
                : "Expecting a recovery source of type [SNAPSHOT] but got [" + shardRouting.recoverySource().getType() + ']';
            throw new UnsupportedOperationException();
        }
    };

    public static final WriteLoadForecaster TEST_WRITE_LOAD_FORECASTER = new WriteLoadForecaster() {
        @Override
        public Metadata.Builder withWriteLoadForecastForWriteIndex(String dataStreamName, Metadata.Builder metadata) {
            throw new AssertionError("Not required for testing");
        }

        @Override
        @SuppressForbidden(reason = "tests do not need a license to access the write load")
        public OptionalDouble getForecastedWriteLoad(IndexMetadata indexMetadata) {
            return indexMetadata.getForecastedWriteLoad();
        }
    };

    public static MockAllocationService createAllocationService() {
        return createAllocationService(Settings.EMPTY);
    }

    public static MockAllocationService createAllocationService(Settings settings) {
        return createAllocationService(settings, EMPTY_CLUSTER_SETTINGS);
    }

    public static MockAllocationService createAllocationService(Settings settings, ClusterSettings clusterSettings) {
        return new MockAllocationService(
            randomAllocationDeciders(settings, clusterSettings),
            new TestGatewayAllocator(),
            createShardsAllocator(settings),
            EmptyClusterInfoService.INSTANCE,
            SNAPSHOT_INFO_SERVICE_WITH_NO_SHARD_SIZES
        );
    }

    private static ShardsAllocator createShardsAllocator(Settings settings) {
        return switch (randomFrom(BALANCED_ALLOCATOR, DESIRED_BALANCE_ALLOCATOR)) {
            case BALANCED_ALLOCATOR -> new BalancedShardsAllocator(settings);
            case DESIRED_BALANCE_ALLOCATOR -> createDesiredBalanceShardsAllocator(settings);
            default -> throw new AssertionError("Unknown allocator");
        };
    }

    private static DesiredBalanceShardsAllocator createDesiredBalanceShardsAllocator(Settings settings) {
        var queue = new DeterministicTaskQueue();
        return new DesiredBalanceShardsAllocator(
            Settings.EMPTY,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS),
            new BalancedShardsAllocator(settings),
            queue.getThreadPool(),
            null,
            null
        ) {
            private RoutingAllocation lastAllocation;

            @Override
            public void allocate(RoutingAllocation allocation, ActionListener<Void> listener) {
                lastAllocation = allocation;
                super.allocate(allocation, listener);
                queue.runAllTasks();
            }

            @Override
            protected void reconcile(DesiredBalance desiredBalance, RoutingAllocation allocation) {
                // do nothing as balance is not computed yet (during allocate)
            }

            @Override
            protected void submitReconcileTask(DesiredBalance desiredBalance) {
                // reconcile synchronously rather than in cluster state update task
                super.reconcile(desiredBalance, lastAllocation);
            }
        };
    }

    public static MockAllocationService createAllocationService(Settings settings, ClusterInfoService clusterInfoService) {
        return new MockAllocationService(
            randomAllocationDeciders(settings, EMPTY_CLUSTER_SETTINGS),
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(settings),
            clusterInfoService,
            SNAPSHOT_INFO_SERVICE_WITH_NO_SHARD_SIZES
        );
    }

    public static MockAllocationService createAllocationService(Settings settings, GatewayAllocator gatewayAllocator) {
        return createAllocationService(settings, gatewayAllocator, SNAPSHOT_INFO_SERVICE_WITH_NO_SHARD_SIZES);
    }

    public static MockAllocationService createAllocationService(Settings settings, SnapshotsInfoService snapshotsInfoService) {
        return createAllocationService(settings, new TestGatewayAllocator(), snapshotsInfoService);
    }

    public static MockAllocationService createAllocationService(
        Settings settings,
        GatewayAllocator gatewayAllocator,
        SnapshotsInfoService snapshotsInfoService
    ) {
        return new MockAllocationService(
            randomAllocationDeciders(settings, EMPTY_CLUSTER_SETTINGS),
            gatewayAllocator,
            new BalancedShardsAllocator(settings),
            EmptyClusterInfoService.INSTANCE,
            snapshotsInfoService
        );
    }

    public static AllocationDeciders randomAllocationDeciders(Settings settings, ClusterSettings clusterSettings) {
        List<AllocationDecider> deciders = new ArrayList<>(
            ClusterModule.createAllocationDeciders(settings, clusterSettings, Collections.emptyList())
        );
        Collections.shuffle(deciders, random());
        return new AllocationDeciders(deciders);
    }

    protected static Set<DiscoveryNodeRole> MASTER_DATA_ROLES = Set.of(DiscoveryNodeRole.MASTER_ROLE, DiscoveryNodeRole.DATA_ROLE);

    protected static DiscoveryNode newNode(String nodeId) {
        return newNode(nodeId, Version.CURRENT);
    }

    protected static DiscoveryNode newNode(String nodeName, String nodeId, Map<String, String> attributes) {
        return new DiscoveryNode(nodeName, nodeId, buildNewFakeTransportAddress(), attributes, MASTER_DATA_ROLES, Version.CURRENT);
    }

    protected static DiscoveryNode newNode(String nodeId, Map<String, String> attributes) {
        return new DiscoveryNode(nodeId, buildNewFakeTransportAddress(), attributes, MASTER_DATA_ROLES, Version.CURRENT);
    }

    protected static DiscoveryNode newNode(String nodeId, Set<DiscoveryNodeRole> roles) {
        return new DiscoveryNode(nodeId, buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
    }

    protected static DiscoveryNode newNode(String nodeName, String nodeId, Set<DiscoveryNodeRole> roles) {
        return new DiscoveryNode(nodeName, nodeId, buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
    }

    protected static DiscoveryNode newNode(String nodeId, Version version) {
        return new DiscoveryNode(nodeId, buildNewFakeTransportAddress(), emptyMap(), MASTER_DATA_ROLES, version);
    }

    protected static ClusterState startRandomInitializingShard(ClusterState clusterState, AllocationService strategy) {
        List<ShardRouting> initializingShards = RoutingNodesHelper.shardsWithState(clusterState.getRoutingNodes(), INITIALIZING);
        if (initializingShards.isEmpty()) {
            return clusterState;
        }
        return startShardsAndReroute(strategy, clusterState, randomFrom(initializingShards));
    }

    protected static AllocationDeciders yesAllocationDeciders() {
        return new AllocationDeciders(
            Arrays.asList(
                new TestAllocateDecision(Decision.YES),
                new SameShardAllocationDecider(
                    Settings.EMPTY,
                    new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                )
            )
        );
    }

    protected static AllocationDeciders noAllocationDeciders() {
        return new AllocationDeciders(Collections.singleton(new TestAllocateDecision(Decision.NO)));
    }

    protected static AllocationDeciders throttleAllocationDeciders() {
        return new AllocationDeciders(
            Arrays.asList(
                new TestAllocateDecision(Decision.THROTTLE),
                new SameShardAllocationDecider(
                    Settings.EMPTY,
                    new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS)
                )
            )
        );
    }

    protected ClusterState applyStartedShardsUntilNoChange(ClusterState clusterState, AllocationService service) {
        int iterations = 0;
        do {
            iterations += 1;
            if (iterations % 100 == 0) {
                logger.info("applyStartedShardsUntilNoChange: iteration [{}]", iterations);
            }
            final var previousClusterState = clusterState;
            logger.debug(() -> Strings.format("ClusterState: %s", previousClusterState.getRoutingNodes()));
            clusterState = startInitializingShardsAndReroute(service, clusterState);
            if (previousClusterState.equals(clusterState)) {
                return clusterState;
            }
        } while (true);
    }

    /**
     * Mark all initializing shards as started, then perform a reroute (which may start some other shards initializing).
     *
     * @return the cluster state after completing the reroute.
     */
    public static ClusterState startInitializingShardsAndReroute(AllocationService allocationService, ClusterState state) {
        return startShardsAndReroute(allocationService, state, RoutingNodesHelper.shardsWithState(state.getRoutingNodes(), INITIALIZING));
    }

    /**
     * Mark all initializing shards on the given node as started, then perform a reroute (which may start some other shards initializing).
     *
     * @return the cluster state after completing the reroute.
     */
    public static ClusterState startInitializingShardsAndReroute(
        AllocationService allocationService,
        ClusterState clusterState,
        RoutingNode routingNode
    ) {
        return startShardsAndReroute(allocationService, clusterState, routingNode.shardsWithState(INITIALIZING).toList());
    }

    /**
     * Mark all initializing shards for the given index as started, then perform a reroute (which may start some other shards initializing).
     *
     * @return the cluster state after completing the reroute.
     */
    public static ClusterState startInitializingShardsAndReroute(
        AllocationService allocationService,
        ClusterState clusterState,
        String index
    ) {
        return startShardsAndReroute(
            allocationService,
            clusterState,
            clusterState.routingTable().index(index).shardsWithState(INITIALIZING)
        );
    }

    /**
     * Mark the given shards as started, then perform a reroute (which may start some other shards initializing).
     *
     * @return the cluster state after completing the reroute.
     */
    public static ClusterState startShardsAndReroute(
        AllocationService allocationService,
        ClusterState clusterState,
        ShardRouting... initializingShards
    ) {
        return startShardsAndReroute(allocationService, clusterState, Arrays.asList(initializingShards));
    }

    /**
     * Mark the given shards as started, then perform a reroute (which may start some other shards initializing).
     *
     * @return the cluster state after completing the reroute.
     */
    public static ClusterState startShardsAndReroute(
        AllocationService allocationService,
        ClusterState clusterState,
        List<ShardRouting> initializingShards
    ) {
        return allocationService.reroute(
            allocationService.applyStartedShards(clusterState, initializingShards),
            "reroute after starting",
            ActionListener.noop()
        );
    }

    public static class TestAllocateDecision extends AllocationDecider {

        private final Decision decision;

        public TestAllocateDecision(Decision decision) {
            this.decision = decision;
        }

        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            return decision;
        }

        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingAllocation allocation) {
            return decision;
        }
    }

    /** A lock {@link AllocationService} allowing tests to override time */
    protected static class MockAllocationService extends AllocationService {

        private volatile long nanoTimeOverride = -1L;

        public MockAllocationService(
            AllocationDeciders allocationDeciders,
            GatewayAllocator gatewayAllocator,
            ShardsAllocator shardsAllocator,
            ClusterInfoService clusterInfoService,
            SnapshotsInfoService snapshotsInfoService
        ) {
            super(allocationDeciders, gatewayAllocator, shardsAllocator, clusterInfoService, snapshotsInfoService);
        }

        public void setNanoTimeOverride(long nanoTime) {
            this.nanoTimeOverride = nanoTime;
        }

        @Override
        protected long currentNanoTime() {
            return nanoTimeOverride == -1L ? super.currentNanoTime() : nanoTimeOverride;
        }
    }

    /**
     * Mocks behavior in ReplicaShardAllocator to remove delayed shards from list of unassigned shards so they don't get reassigned yet.
     */
    protected static class DelayedShardsMockGatewayAllocator extends GatewayAllocator {
        public DelayedShardsMockGatewayAllocator() {}

        @Override
        public void applyStartedShards(List<ShardRouting> startedShards, RoutingAllocation allocation) {
            // no-op
        }

        @Override
        public void applyFailedShards(List<FailedShard> failedShards, RoutingAllocation allocation) {
            // no-op
        }

        @Override
        public void beforeAllocation(RoutingAllocation allocation) {
            // no-op
        }

        @Override
        public void afterPrimariesBeforeReplicas(RoutingAllocation allocation) {
            // no-op
        }

        @Override
        public void allocateUnassigned(
            ShardRouting shardRouting,
            RoutingAllocation allocation,
            UnassignedAllocationHandler unassignedAllocationHandler
        ) {
            if (shardRouting.primary() || shardRouting.unassignedInfo().getReason() == UnassignedInfo.Reason.INDEX_CREATED) {
                return;
            }
            if (shardRouting.unassignedInfo().isDelayed()) {
                unassignedAllocationHandler.removeAndIgnore(UnassignedInfo.AllocationStatus.DELAYED_ALLOCATION, allocation.changes());
            }
        }
    }
}

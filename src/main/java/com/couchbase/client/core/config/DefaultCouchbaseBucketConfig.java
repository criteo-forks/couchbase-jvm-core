/*
 * Copyright (c) 2016 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.client.core.config;

import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.core.utils.NetworkAddress;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DefaultCouchbaseBucketConfig extends AbstractBucketConfig implements CouchbaseBucketConfig {

    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(CouchbaseBucketConfig.class);

    public static final int PARTITION_NOT_EXISTENT = -2;

    private final CouchbasePartitionInfo partitionInfo;
    private final List<NodeInfo> partitionHosts;
    private final Set<NetworkAddress> nodesWithPrimaryPartitions;

    private final boolean tainted;
    private final long rev;
    private final boolean ephemeral;

    /**
     * Creates a new {@link CouchbaseBucketConfig}.
     *
     * @param rev the revision of the config.
     * @param name the name of the bucket.
     * @param uri the URI for this bucket.
     * @param streamingUri the streaming URI for this bucket.
     * @param partitionInfo partition info for this bucket.
     * @param nodeInfos related node information.
     * @param portInfos port info for the nodes, including services.
     */
    @JsonCreator
    public DefaultCouchbaseBucketConfig(
        @JsonProperty("rev") long rev,
        @JsonProperty("name") String name,
        @JsonProperty("uri") String uri,
        @JsonProperty("streamingUri") String streamingUri,
        @JsonProperty("vBucketServerMap") CouchbasePartitionInfo partitionInfo,
        @JsonProperty("nodes") List<NodeInfo> nodeInfos,
        @JsonProperty("nodesExt") List<PortInfo> portInfos,
        @JsonProperty("bucketCapabilities") List<BucketCapabilities> bucketCapabilities) {
        super(name, BucketNodeLocator.VBUCKET, uri, streamingUri, nodeInfos, portInfos, bucketCapabilities);
        this.partitionInfo = partitionInfo;
        this.tainted = partitionInfo.tainted();
        this.partitionHosts = buildPartitionHosts(nodeInfos, partitionInfo);
        this.nodesWithPrimaryPartitions = buildNodesWithPrimaryPartitions(nodeInfos, partitionInfo.partitions());
        this.rev = rev;

        // Use bucket capabilities to identify if couchapi is missing (then its ephemeral). If its null then
        // we are running an old version of couchbase which doesn't have ephemeral buckets at all.
        this.ephemeral = bucketCapabilities != null && !bucketCapabilities.contains(BucketCapabilities.COUCHAPI);
    }

    /**
     * Pre-computes a set of nodes that have primary partitions active.
     *
     * @param nodeInfos the list of nodes.
     * @param partitions the partitions.
     * @return a set containing the addresses of nodes with primary partitions.
     */
    private static Set<NetworkAddress> buildNodesWithPrimaryPartitions(final List<NodeInfo> nodeInfos,
        final List<Partition> partitions) {
        Set<NetworkAddress> nodes = new HashSet<NetworkAddress>(nodeInfos.size());
        for (Partition partition : partitions) {
            int index = partition.master();
            if (index >= 0) {
                nodes.add(nodeInfos.get(index).hostname());
            }
        }
        return nodes;
    }

    /**
     * Helper method to reference the partition hosts from the raw node list.
     *
     * @param nodeInfos the node infos.
     * @param partitionInfo the partition info.
     * @return a ordered reference list for the partition hosts.
     */
    private static List<NodeInfo> buildPartitionHosts(List<NodeInfo> nodeInfos, CouchbasePartitionInfo partitionInfo) {
        List<NodeInfo> partitionHosts = new ArrayList<NodeInfo>();
        for (String rawHost : partitionInfo.partitionHosts()) {
            NetworkAddress convertedHost;
            int directPort;
            try {
                String pair[] = rawHost.split(":");
                convertedHost = NetworkAddress.create(pair[0]);
                try {
                    directPort = Integer.parseInt(pair[1]);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Could not parse port from the node address: {}, fallback to 0", rawHost);
                    directPort = 0;
                }
            } catch (Exception e) {
                throw new ConfigurationException("Could not resolve " + rawHost + "on config building.");
            }
            for (NodeInfo nodeInfo : nodeInfos) {
                if (nodeInfo.hostname().equals(convertedHost) &&
                        (nodeInfo.services().get(ServiceType.BINARY) == directPort || directPort == 0)) {
                    partitionHosts.add(nodeInfo);
                }
            }
        }
        if (partitionHosts.size() != partitionInfo.partitionHosts().length) {
            throw new ConfigurationException("Partition size is not equal after conversion, this is a bug.");
        }
        return partitionHosts;
    }

    @Override
    public int numberOfReplicas() {
        return partitionInfo.numberOfReplicas();
    }

    @Override
    public boolean tainted() {
        return tainted;
    }

    @Override
    public boolean hasPrimaryPartitionsOnNode(final NetworkAddress hostname) {
        return nodesWithPrimaryPartitions.contains(hostname);
    }

    @Override
    public short nodeIndexForMaster(int partition, boolean useFastForward) {
        if (useFastForward && !hasFastForwardMap()) {
            throw new IllegalStateException("Could not get index from FF-Map, none found in this config.");
        }

        List<Partition> partitions = useFastForward ? partitionInfo.forwardPartitions() : partitionInfo.partitions();
        try {
            return partitions.get(partition).master();
        } catch (IndexOutOfBoundsException ex) {
            LOGGER.debug("Out of bounds on index for master " + partition + ".", ex);
            return PARTITION_NOT_EXISTENT;
        }
    }

    @Override
    public short nodeIndexForReplica(int partition, int replica, boolean useFastForward) {
        if (useFastForward && !hasFastForwardMap()) {
            throw new IllegalStateException("Could not get index from FF-Map, none found in this config.");
        }

        List<Partition> partitions = useFastForward ? partitionInfo.forwardPartitions() : partitionInfo.partitions();

        try {
            return partitions.get(partition).replica(replica);
        } catch (IndexOutOfBoundsException ex) {
            LOGGER.debug("Out of bounds on index for replica " + partition + ".", ex);
            return PARTITION_NOT_EXISTENT;
        }
    }

    @Override
    public int numberOfPartitions() {
        return partitionInfo.partitions().size();
    }

    @Override
    public NodeInfo nodeAtIndex(int nodeIndex) {
        return partitionHosts.get(nodeIndex);
    }

    @Override
    public long rev() {
        return rev;
    }

    @Override
    public BucketType type() {
        return BucketType.COUCHBASE;
    }

    @Override
    public boolean hasFastForwardMap() {
        return partitionInfo.hasFastForwardMap();
    }

    @Override
    public boolean ephemeral() {
        return ephemeral;
    }

    @Override
    public String toString() {
        return "DefaultCouchbaseBucketConfig{"
            + "name='" + name() + '\''
            + ", locator=" + locator()
            + ", uri='" + uri() + '\''
            + ", streamingUri='" + streamingUri() + '\''
            + ", nodeInfo=" + nodes()
            + ", partitionInfo=" + partitionInfo
            + ", tainted=" + tainted
            + ", rev=" + rev + '}';
    }
}

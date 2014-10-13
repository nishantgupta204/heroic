package com.spotify.heroic.cluster;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import com.spotify.heroic.async.Future;
import com.spotify.heroic.async.Transform;
import com.spotify.heroic.cluster.httprpc.HttpRpcMetadata;
import com.spotify.heroic.cluster.httprpc.rpc4.Rpc4ClusterNode;
import com.spotify.heroic.cluster.httprpc.rpc5.Rpc5ClusterNode;
import com.spotify.heroic.cluster.model.NodeCapability;
import com.spotify.heroic.cluster.model.NodeMetadata;
import com.spotify.heroic.cluster.model.NodeMetadataImpl;
import com.spotify.heroic.cluster.model.NodeRegistryEntry;
import com.spotify.heroic.httpclient.HttpClientManager;
import com.spotify.heroic.httpclient.HttpClientSession;
import com.spotify.heroic.httpclient.exceptions.RpcNodeException;
import com.spotify.heroic.metadata.MetadataManager;

@RequiredArgsConstructor
public class ClusterRPC {
    public static final int CURRENT_VERSION = 5;

    @Inject
    private HttpClientManager clients;

    @Inject
    private MetadataManager metadata;

    @Inject
    private NodeMetadata localMetadata;

    @Inject
    private LocalClusterNode localClusterNode;

    @Inject
    @Named("useLocal")
    private boolean useLocal;

    @Data
    private static class EntryTransformer implements Transform<NodeMetadata, NodeRegistryEntry> {
        private final HttpClientManager clients;
        private final NodeRegistryEntry localEntry;
        private final boolean useLocal;
        private final MetadataManager localMetadata;
        private final URI uri;

        @Override
        public NodeRegistryEntry transform(NodeMetadata metadata) throws Exception {
            if (useLocal && metadata.getId().equals(localEntry.getMetadata().getId())) {
                return localEntry;
            }

            final ClusterNode node = buildClusterNode(metadata);
            return new NodeRegistryEntry(uri, node, metadata);
        }

        /**
         * Pick the best cluster node implementation depending on the provided metadata.
         */
        private ClusterNode buildClusterNode(NodeMetadata m) throws Exception {
            if (m.getVersion() < 4) {
                throw new RpcNodeException(uri, "Unsupported RPC version: " + m.getVersion());
            }

            /* Only create a client for the highest possibly supported version. */
            final String base = String.format("rpc%d", Math.min(m.getVersion(), CURRENT_VERSION));
            final HttpClientSession client = clients.newSession(uri, base);

            switch (m.getVersion()) {
            case 4:
                // backwards compatibility entails providing this with access to
                // local metadata.
                return new Rpc4ClusterNode(client, localMetadata);
            default:
                return new Rpc5ClusterNode(client);
            }
        }
    }

    private Transform<NodeMetadata, NodeRegistryEntry> transformerFor(URI uri) {
        return new EntryTransformer(clients, localEntry(), useLocal, metadata, uri);
    }

    private Future<NodeMetadata> metadataFor(URI uri) {
        final HttpClientSession client = clients.newSession(uri, "rpc");

        final Transform<HttpRpcMetadata, NodeMetadata> transformer = new Transform<HttpRpcMetadata, NodeMetadata>() {
            @Override
            public NodeMetadata transform(final HttpRpcMetadata r) throws Exception {
                return new NodeMetadataImpl(r.getVersion(), r.getId(), r.getTags(), r.getCapabilities());
            }
        };

        return client.get(HttpRpcMetadata.class, "metadata").transform(transformer);
    }

    public NodeRegistryEntry localEntry() {
        return new NodeRegistryEntry(null, localClusterNode, localMetadata);
    }

    /**
     * Resolve a NodeRegistryEntry for the specified URI.
     *
     * @param uri
     * @return
     */
    public Future<NodeRegistryEntry> resolve(URI uri) {
        return metadataFor(uri).transform(transformerFor(uri));
    }

    public static NodeMetadata localMetadata(UUID localId, Map<String, String> localTags,
            Set<NodeCapability> capabilities) {
        return new NodeMetadataImpl(CURRENT_VERSION, localId, localTags, capabilities);
    }
}

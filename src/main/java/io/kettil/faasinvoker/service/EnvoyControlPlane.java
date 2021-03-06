package io.kettil.faasinvoker.service;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.*;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.BufferSettings;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.CheckSettings;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.kettil.faas.Manifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.Closeable;
import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class EnvoyControlPlane implements Closeable {
    private static final String GROUP = "key";

    private final int port;
    private final Manifest manifest;

    private SimpleCache<String> cache;
    private Server server;

    public EnvoyControlPlane(
        @org.springframework.beans.factory.annotation.Value("${xds.port:9000}") int port,
        Manifest manifest) {
        this.port = port;
        this.manifest = manifest;

        this.cache = new SimpleCache<>(new NodeGroup<String>() {
            @Override
            public String hash(io.envoyproxy.envoy.api.v2.core.Node node) {
                return GROUP;
            }

            @Override
            public String hash(Node node) {
                return GROUP;
            }
        });
    }

    @PostConstruct
    public void start() throws IOException {
//        Admin admin = getAdmin();
//        LayeredRuntime layeredRuntime = getLayeredRuntime();

        List<Cluster> clusters = Arrays.asList(
            getCluster("invoker", "invoker", 8080),
            getCluster("acl_api", "authz", 8081),
            getCluster("authz", "authz", 8080)
                .toBuilder()
                .putTypedExtensionProtocolOptions(
                    "envoy.extensions.upstreams.http.v3.HttpProtocolOptions",
                    Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions")
                        .setValue(HttpProtocolOptions.newBuilder()
                            .setExplicitHttpConfig(HttpProtocolOptions.ExplicitHttpConfig.newBuilder()
                                .setHttp2ProtocolOptions(Http2ProtocolOptions.newBuilder()
                                    .build()))
                            .build().toByteString())
                        .build())
                .build());

        List<Route> routes = new ArrayList<>();

        routes.add(getRoute(false, "/acl/", "acl_api", new HashMap<>() {{
            put("namespace_object", "acl");
            put("namespace_service", "api");
            put("service_path", "/acl/{objectId}");
            put("relation", "owner");
        }}));

        for (Map.Entry<String, Manifest.PathManifest> i : manifest.getPaths().entrySet()) {
            LinkedHashMap<String, String> materializedExtensions = new LinkedHashMap<>(manifest.getAuthorization().getExtensions());

            materializedExtensions.put("service_path", i.getKey());
            String objectIdPtr = i.getValue().getAuthorization().getObjectIdPtr();
            if (objectIdPtr != null)
                materializedExtensions.put("objectid_ptr", objectIdPtr);

            materializedExtensions.putAll(i.getValue().getAuthorization().getExtensions());

            routes.add(getRoute(i.getKey(), "invoker", materializedExtensions));
        }

        List<Listener> listeners = getListeners(routes);

        cache.setSnapshot(
            GROUP,
            Snapshot.create(
                clusters,
                ImmutableList.of(),
                listeners,
                ImmutableList.of(),
                ImmutableList.of(),
                "1"));

        log.info(cache.toString());

        V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);

        server = NettyServerBuilder.forPort(port)
            .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
            .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
            .build();

        server.start();
        log.info("Envoy control plane server started on port {}", server.getPort());
    }

    private List<Listener> getListeners(List<Route> routes) {
        return Arrays.asList(Listener.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("0.0.0.0")
                    .setPortValue(18000)
                    .build())
                .build())
            .addFilterChains(FilterChain.newBuilder()
                .addFilters(Filter.newBuilder()
                    .setName("envoy.filters.network.http_connection_manager")
                    .setTypedConfig(Any.newBuilder()
                        .setTypeUrl("type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager")
                        .setValue(HttpConnectionManager.newBuilder()
                            .setCodecType(HttpConnectionManager.CodecType.AUTO)
                            .setStatPrefix("ingress_http")
                            .addHttpFilters(HttpFilter.newBuilder()
                                .setName("envoy.ext_authz1")
                                .setTypedConfig(Any.newBuilder()
                                    .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz")
                                    .setValue(ExtAuthz.newBuilder()
                                        .setTransportApiVersion(ApiVersion.V3)
                                        .setGrpcService(GrpcService.newBuilder()
                                            .setTimeout(Duration.newBuilder()
                                                .setSeconds(1)
                                                .build())
                                            .setEnvoyGrpc(GrpcService.EnvoyGrpc.newBuilder()
                                                .setClusterName("authz")
                                                .build())
                                            .build())
                                        .setIncludePeerCertificate(true)
                                        .setWithRequestBody(BufferSettings.newBuilder()
                                            .setMaxRequestBytes(65536)
                                            .setAllowPartialMessage(false)
                                            .setPackAsBytes(false)
                                            .build())
                                        .build().toByteString())
                                    .build())
                                .build())
                            .addHttpFilters(HttpFilter.newBuilder()
                                .setName("envoy.filters.http.router")
                                .build())
                            .setRouteConfig(RouteConfiguration.newBuilder()
                                .setName("local_route")
                                .addVirtualHosts(VirtualHost.newBuilder()
                                    .setName("backend")
                                    .addDomains("*")
                                    .addAllRoutes(routes)
                                    .build())
                                .build())
                            .build().toByteString())
                        .build())
                    .build())
                .build())
            .build());
    }

    private Route getRoute(String path, String cluster, Map<String, String> contextExtensions) {
        return getRoute(true, path, cluster, contextExtensions);
    }

    private Route getRoute(boolean exactMatch, String path, String cluster, Map<String, String> contextExtensions) {
        return Route.newBuilder()
            .setMatch(exactMatch
                ? RouteMatch.newBuilder().setPath(path).build()
                : RouteMatch.newBuilder().setPrefix(path).build())
            .setRoute(RouteAction.newBuilder()
                .setCluster(cluster)
                .build())
            .putTypedPerFilterConfig("envoy.filters.http.ext_authz", Any.newBuilder()
                .setTypeUrl("type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute")
                .setValue(ExtAuthzPerRoute.newBuilder()
                    .setCheckSettings(CheckSettings.newBuilder()
                        .putAllContextExtensions(contextExtensions)
                        .build())
                    .build().toByteString())
                .build())
            .build();
    }

    private Cluster getCluster(String name, String host, int port) {
        return Cluster.newBuilder()
            .setName(name)
            .setConnectTimeout(Duration.newBuilder().setSeconds(1))
            .setType(Cluster.DiscoveryType.STRICT_DNS)
            .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
            .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                .setClusterName(name)
                .addEndpoints(LocalityLbEndpoints.newBuilder()
                    .addLbEndpoints(LbEndpoint.newBuilder()
                        .setEndpoint(Endpoint.newBuilder()
                            .setAddress(Address.newBuilder()
                                .setSocketAddress(SocketAddress.newBuilder()
                                    .setAddress(host)
                                    .setPortValue(port)
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build();
    }

//    private LayeredRuntime getLayeredRuntime() {
//        return LayeredRuntime.newBuilder()
//            .addLayers(RuntimeLayer.newBuilder()
//                .setName("static_layer_0")
//                .setStaticLayer(Struct.newBuilder()
//                    .putFields("envoy", Value.newBuilder()
//                        .setStructValue(Struct.newBuilder()
//                            .putFields("resource_limits", Value.newBuilder()
//                                .setStructValue(Struct.newBuilder()
//                                    .putFields("listener", Value.newBuilder()
//                                        .setStructValue(Struct.newBuilder()
//                                            .putFields("example_listener_name", Value.newBuilder()
//                                                .setStructValue(Struct.newBuilder()
//                                                    .putFields("connection_limit", Value.newBuilder()
//                                                        .setNumberValue(10000)
//                                                        .build())
//                                                    .build())
//                                                .build())
//                                            .build())
//                                        .build())
//                                    .build())
//                                .build())
//                            .build())
//                        .build())
//                    .build())
//                .build())
//            .build();
//    }

//    private Admin getAdmin() {
//        return Admin.newBuilder()
//            .setAccessLogPath("/dev/null")
//            .setAddress(Address.newBuilder()
//                .setSocketAddress(SocketAddress.newBuilder()
//                    .setAddress("0.0.0.0")
//                    .setPortValue(8001)
//                    .build())
//                .build())
//            .build();
//}

    @Override
    public void close() throws IOException {
        if (server != null)
            server.shutdown();
    }
}

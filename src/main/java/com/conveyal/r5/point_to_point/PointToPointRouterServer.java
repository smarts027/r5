package com.conveyal.r5.point_to_point;

import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.RouterInfo;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.*;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.*;
import com.vividsolutions.jts.operation.buffer.BufferParameters;
import com.vividsolutions.jts.operation.buffer.OffsetCurveBuilder;
import gnu.trove.set.TIntSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.util.*;

import java.io.File;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;
import static spark.Spark.*;

/**
 * This will represent point to point search server.
 *
 * It can build point to point TransportNetwork and start a server with API for point to point searches
 *
 */
public class PointToPointRouterServer {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointRouterServer.class);

    private static final int DEFAULT_PORT = 8080;

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph";

    public static final int RADIUS_METERS = 200;

    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        final boolean inMemory = false;

        if ("--build".equals(commandArguments[0])) {

            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }

            TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
            //In memory doesn't save it to disk others do (build, preFlight)
            if (!inMemory) {
                try {
                    OutputStream outputStream = new BufferedOutputStream(
                        new FileOutputStream(new File(dir, "network.dat")));
                    transportNetwork.write(outputStream);
                    outputStream.close();
                } catch (IOException e) {
                    LOG.error("An error occurred during saving transit networks. Exiting.", e);
                    System.exit(-1);
                }

            }
        } else if ("--graphs".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                LOG.info("Loading transit networks from: {}", dir);
                InputStream inputStream = new BufferedInputStream(new FileInputStream(new File(dir, "network.dat")));
                TransportNetwork transportNetwork = TransportNetwork.read(inputStream);
                run(transportNetwork);
                inputStream.close();
            } catch (IOException e) {
                LOG.error("An error occurred during reading of transit networks", e);
                System.exit(-1);
            } catch (Exception e) {
                LOG.error("An error occurred during decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--help".equals(commandArguments[0])
                || "-h".equals(commandArguments[0])
                || "--usage".equals(commandArguments[0])
                || "-u".equals(commandArguments[0])) {
            System.out.println(USAGE);
        } else {
            LOG.info("Unknown argument: {}", commandArguments[0]);
            System.out.println(USAGE);
        }

    }

    private static void run(TransportNetwork transportNetwork) {
        port(DEFAULT_PORT);
        staticFileLocation("debug-plan");
        // add cors header
        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        get("/metadata", (request, response) -> {
            response.header("Content-Type", "application/json");
            RouterInfo routerInfo = new RouterInfo();
            routerInfo.envelope = transportNetwork.getEnvelope();
            return routerInfo;

        }, JsonUtilities.objectMapper::writeValueAsString);

        get("/plan", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>();
            String queryMode = request.queryParams("mode");

            Mode mode = Mode.valueOf(queryMode);
            if (mode == null) {
                content.put("errors", "Mode is wrong");
                return content;
            }
            Float fromLat = request.queryMap("fromLat").floatValue();
            Float toLat = request.queryMap("toLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();
            Float toLon = request.queryMap("toLon").floatValue();
            //TODO errorchecks

            Boolean fullStateList = request.queryMap("full").booleanValue();
            RoutingVisitor routingVisitor = new RoutingVisitor(transportNetwork.streetLayer.edgeStore, mode);

            if (fullStateList == null) {
                fullStateList = false;
            }

            ProfileRequest profileRequest = new ProfileRequest();
            profileRequest.zoneId = transportNetwork.getTimeZone();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            profileRequest.toLat = toLat;
            profileRequest.toLon = toLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.profileRequest = profileRequest;
            streetRouter.mode = mode;
            //Split for end coordinate
            Split split = transportNetwork.streetLayer.findSplit(profileRequest.toLat, profileRequest.toLon,
                RADIUS_METERS);
            if (split == null) {
                content.put("errors", "Edge near the end coordinate wasn't found. Routing didn't start!");
                return content;
            }
            if (!streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
                content.put("errors", "Edge near the origin coordinate wasn't found. Routing didn't start!");
                return content;
            }
            if (fullStateList) {
                streetRouter.setRoutingVisitor(routingVisitor);
            }
            streetRouter.route();

            if (fullStateList) {
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = routingVisitor.getFeatures();

                LOG.info("Num features:{}", features.size());
                featureCollection.put("features", features);
                content.put("data", featureCollection);
                return content;
            }

            //Gets lowest weight state for end coordinate split
            StreetRouter.State lastState = streetRouter.getState(split);
            if (lastState != null) {
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = new ArrayList<>();

                fillFeature(transportNetwork, mode, lastState, features);
                featureCollection.put("features", features);
                content.put("data", featureCollection);
            } else {
                Split originSplit = streetRouter.getOriginSplit();
                //FIXME: why are coordinates of vertex0 and vertex1 the same
                //but distance to vertex and vertex idx differ

                /*Path wasn't found we return 3 points:
                 * - Point on start of edge that we started the search
                 * - Point on end of edge that started the search
                 * - Closest point on edge to start coordinate
                 *
                 * We also return outgoing edges from start and end points of edge that started the search
                 */
                Map<String, Object> featureCollection = new HashMap<>(2);
                featureCollection.put("type", "FeatureCollection");
                List<GeoJsonFeature> features = new ArrayList<>(10);
                GeoJsonFeature feature = new GeoJsonFeature(
                    fixedDegreesToFloating(originSplit.fLon), fixedDegreesToFloating(originSplit.fLat));
                feature.addProperty("type", "Point on edge");

                features.add(feature);

                VertexStore.Vertex vertex = transportNetwork.streetLayer.vertexStore.getCursor(originSplit.vertex0);
                feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
                feature.addProperty("type", "Edge start point");
                feature.addProperty("vertex_idx", originSplit.vertex0);
                feature.addProperty("distance_to_vertex", originSplit.distance0_mm/1000);
                features.add(feature);
                transportNetwork.streetLayer.getOutgoingEdges().get(originSplit.vertex0)
                    .forEach(edge_idx -> {
                        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edge_idx);
                        GeoJsonFeature edge_feature = new GeoJsonFeature(edge.getGeometry());
                        edge_feature.addProperty("idx", edge_idx);
                        edge_feature.addProperty("permissions", edge.getPermissionsAsString());
                        edge_feature.addProperty("from", "vertex0");
                        features.add(edge_feature);
                        return true;
                    });

                transportNetwork.streetLayer.vertexStore.getCursor(originSplit.vertex1);
                feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
                feature.addProperty("type", "Edge end point");
                feature.addProperty("vertex_idx", originSplit.vertex1);
                feature.addProperty("distance_to_vertex", originSplit.distance1_mm/1000);
                features.add(feature);
                transportNetwork.streetLayer.getOutgoingEdges().get(originSplit.vertex1)
                    .forEach(edge_idx -> {
                        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edge_idx);
                        GeoJsonFeature edge_feature = new GeoJsonFeature(edge.getGeometry());
                        edge_feature.addProperty("idx", edge_idx);
                        edge_feature.addProperty("permissions", edge.getPermissionsAsString());
                        edge_feature.addProperty("from", "vertex1");
                        features.add(edge_feature);
                        return true;
                    });

                featureCollection.put("features", features);
                content.put("data", featureCollection);
                content.put("errors", "Path to end coordinate wasn't found!");
            }
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        get("debug/streetEdges", (request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.queryParams().size() < 4) {
                response.status(400);
                return "";
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();
            Boolean iboth = request.queryMap("both").booleanValue();
            Boolean idetail = request.queryMap("detail").booleanValue();
            boolean both, detail;


            if (iboth == null) {
                both = false;
            } else {
                both = iboth;
            }

            if (idetail == null) {
                detail = false;
            } else {
                detail = idetail;
            }

            String layer = "streetEdges"; // request.params(":layer");

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.spatialIndex.query(env);

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                return "";
            }

            // write geojson to response
            Map<String, Object> featureCollection = new HashMap<>(2);
            featureCollection.put("type", "FeatureCollection");
            List<GeoJsonFeature> features = new ArrayList<>(streets.size());

            EdgeStore.Edge cursor = transportNetwork.streetLayer.edgeStore.getCursor();
            VertexStore.Vertex vcursor = transportNetwork.streetLayer.vertexStore.getCursor();

            BufferParameters bufParams = new BufferParameters();
            bufParams.setSingleSided(true);
            bufParams.setJoinStyle(BufferParameters.JOIN_BEVEL);
            OffsetCurveBuilder offsetBuilder = new OffsetCurveBuilder(new PrecisionModel(),
                bufParams);
            float distance = -0.00005f;

            Set<Integer> seenVertices = new HashSet<>(streets.size());

            if ("streetEdges".equals(layer)) {
                streets.forEach(s -> {
                    try {
                        cursor.seek(s);

                        GeoJsonFeature feature = getEdgeFeature(both, cursor, offsetBuilder,
                            distance);

                        if (!both) {
                            //Adds fake flag oneway which is added to forward edge if permission flags on forward and backward edge differ.
                            EnumSet<EdgeStore.EdgeFlag> forwardPermissions = cursor.getPermissionFlags();
                            EdgeStore.Edge backwardCursor = transportNetwork.streetLayer.edgeStore.getCursor(s+1);

                            EnumSet<EdgeStore.EdgeFlag> backwardPermissions = backwardCursor.getPermissionFlags();

                            if (!forwardPermissions.equals(backwardPermissions)) {
                                feature.addProperty("ONEWAY", true);
                            }
                        }
                        features.add(feature);

                        getVertexFeatures(cursor, vcursor, seenVertices, features);
                        if (both) {
                            //backward edge
                            cursor.seek(s+1);
                            feature = getEdgeFeature(both, cursor, offsetBuilder,
                                distance);
                            features.add(feature);

                        }
                        return true;
                    } catch (Exception e) {
                        response.status(500);
                        LOG.error("Exception:", e);
                        return false;
                    }
                });
            }

            featureCollection.put("features", features);

            return featureCollection;
        }, JsonUtilities.objectMapper::writeValueAsString);

        //Returns flags usage in requested area
        get("debug/stats", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            EnumMap<EdgeStore.EdgeFlag, Integer> flagUsage = new EnumMap<>(
                EdgeStore.EdgeFlag.class);
            if (request.queryParams().size() < 4) {

                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                for (int e = 0; e < transportNetwork.streetLayer.edgeStore.nEdges(); e += 2) {
                    edge.seek(e);

                    try {

                        for (EdgeStore.EdgeFlag flag : EdgeStore.EdgeFlag.values()) {
                            if (edge.getFlag(flag)) {
                                Integer currentValue = flagUsage.getOrDefault(flag, 0);
                                flagUsage.put(flag, currentValue + 1);
                            }
                        }
                    } catch (Exception ex) {
                        response.status(500);
                        content.put("errors", "Problem reading edges:" + ex.getMessage());
                        LOG.error("Exception:", e);
                        return content;
                    }
                }
                content.put("data", flagUsage);
                return content;
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();
            boolean detail = request.queryMap("detail").booleanValue();

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.spatialIndex.query(env);
            //transportNetwork.streetLayer.edgeStore.getCursor()

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                content.put("errors", "Refusing to include more than 100,000 edges in result");
                return content;
            }

            EdgeStore.Edge cursor = transportNetwork.streetLayer.edgeStore.getCursor();
            VertexStore.Vertex vcursor = transportNetwork.streetLayer.vertexStore.getCursor();
            streets.forEach(s -> {
                try {
                    cursor.seek(s);
                    for (EdgeStore.EdgeFlag flag : EdgeStore.EdgeFlag.values()) {
                        if (cursor.getFlag(flag)) {
                            Integer currentValue = flagUsage.getOrDefault(flag, 0);
                            flagUsage.put(flag, currentValue + 1);
                        }
                    }
                    return true;
                } catch (Exception e) {
                    response.status(500);
                    content.put("errors", "Problem reading edges:" + e.getMessage());
                    LOG.error("Exception:", e);
                    return false;
                }
            });
            content.put("data", flagUsage);
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);

        //Returns flags usage in requested area
        get("debug/speeds", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            Map<Short, Integer> speedUsage = new HashMap<>();
            MinMax minMax = new MinMax();
            if (request.queryParams().size() < 4) {

                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                for (int e = 0; e < transportNetwork.streetLayer.edgeStore.nEdges(); e += 2) {
                    edge.seek(e);

                    try {
                        updateSpeed(edge, speedUsage, minMax);
                    } catch (Exception ex) {
                        response.status(500);
                        content.put("errors", "Problem reading edges:" + ex.getMessage());
                        LOG.error("Exception:", e);
                        return content;
                    }
                }
                content.put("data", speedUsage);
                content.put("min", minMax.min);
                content.put("max", minMax.max);
                return content;
            }
            float north = request.queryMap("n").floatValue();
            float south = request.queryMap("s").floatValue();
            float east = request.queryMap("e").floatValue();
            float west = request.queryMap("w").floatValue();

            Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                floatingDegreesToFixed(south), floatingDegreesToFixed(north));
            TIntSet streets = transportNetwork.streetLayer.spatialIndex.query(env);

            if (streets.size() > 100_000) {
                LOG.warn("Refusing to include more than 100,000 edges in result");
                response.status(401);
                content.put("errors", "Refusing to include more than 100,000 edges in result");
                return content;
            }

            EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
            streets.forEach(s -> {
                try {
                    edge.seek(s);
                    updateSpeed(edge, speedUsage, minMax);
                    return true;
                } catch (Exception e) {
                    response.status(500);
                    content.put("errors", "Problem reading edges:" + e.getMessage());
                    LOG.error("Exception:", e);
                    return false;
                }
            });
            content.put("data", speedUsage);
            content.put("min", minMax.min);
            content.put("max", minMax.max);
            return content;
        }, JsonUtilities.objectMapper::writeValueAsString);


        //queries edge store for edgeID and returns envelope of this edge ID
        get("/query", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>(2);
            try {

            Integer edgeID = request.queryMap("edgeID").integerValue();

            if (edgeID == null) {
                content.put("errors", "edgeID is empty!");
            } else {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
                try {
                    edge.seek(edgeID);
                    content.put("data", edge.getGeometry().getEnvelopeInternal());
                } catch (Exception ex) {
                    content.put("errors", ex.getMessage());
                    LOG.error("Error getting edge:{}", ex);
                }

            }
            } catch (Exception ex) {
                content.put("errors", "Problem converting edgeID to integer:"+ ex.getMessage());
                LOG.error("Error converting edgeID to integer:{}", ex.getMessage());
            }

            return content;

        }, JsonUtilities.objectMapper::writeValueAsString);

    }

    /**
     * Creates features from from and to vertices of provided edge
     * if they weren't alreade created and they have TRAFFIC_SIGNAL flag
     * 
     * @param cursor
     * @param vcursor
     * @param seenVertices
     * @param features
     */
    private static void getVertexFeatures(EdgeStore.Edge cursor, VertexStore.Vertex vcursor,
        Set<Integer> seenVertices, List<GeoJsonFeature> features) {

        int fromVertex = cursor.getFromVertex();
        if (!seenVertices.contains(fromVertex)) {
            vcursor.seek(fromVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(fromVertex);
        }
        int toVertex = cursor.getToVertex();
        if (!seenVertices.contains(toVertex)) {
            vcursor.seek(toVertex);
            GeoJsonFeature feature = getVertexFeature(vcursor);
            //It can be null since we only insert vertices with flags
            if (feature != null) {
                features.add(feature);
            }
            seenVertices.add(toVertex);
        }
    }

    /**
     * Creates geojson feature from specified vertex
     *
     * Currently it only does that if vertex have TRAFFIC_SIGNAL flag.
     * Properties in GeoJSON are:
     * - vertex_id
     * - flags: TRAFFIC_SIGNAL
     * @param vertex
     * @return
     */
    private static GeoJsonFeature getVertexFeature(VertexStore.Vertex vertex) {
        if (vertex.getFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL)) {
            GeoJsonFeature feature = new GeoJsonFeature(vertex.getLon(), vertex.getLat());
            feature.addProperty("vertex_id", vertex.index);
            //TODO: add all flags (when there is more)
            feature.addProperty("flags", VertexStore.VertexFlag.TRAFFIC_SIGNAL.toString());
            return feature;
        }
        return null;
    }

    private static void fillFeature(TransportNetwork transportNetwork, Mode mode,
        StreetRouter.State lastState, List<GeoJsonFeature> features) {
        LinkedList<StreetRouter.State> states = new LinkedList<>();

                /*
                * Starting from latest (time-wise) state, copy states to the head of a list in reverse
                * chronological order. List indices will thus increase forward in time, and backEdges will
                * be chronologically 'back' relative to their state.
                */
        for (StreetRouter.State cur = lastState; cur != null; cur = cur.backState) {
            states.addFirst(cur);
        }

        //TODO: this can be improved since end and start vertices are the same in all the edges.
        for (StreetRouter.State state : states) {
            Integer edgeIdx = state.backEdge;
            if (!(edgeIdx == -1 || edgeIdx == null)) {
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore
                    .getCursor(edgeIdx);
                GeoJsonFeature feature = new GeoJsonFeature(edge.getGeometry());
                feature.addProperty("weight", state.weight);
                //FIXME: get this from state
                feature.addProperty("mode", mode);
                features.add(feature);
                feature.addProperty("time", Instant.ofEpochMilli(state.getTime()).toString());
            }
        }
    }

    /**
     * Gets feature from edge in EdgeStore
     * @param both true if we are showing edges in both directions AKA it needs to be offset
     * @param cursor cursor to current forward or reversed edge
     * @param offsetBuilder builder which creates edge offset if needed
     * @param distance for which edge is offset if both is true
     * @return
     */
    private static GeoJsonFeature getEdgeFeature(boolean both, EdgeStore.Edge cursor,
        OffsetCurveBuilder offsetBuilder, float distance) {
        LineString geometry = cursor.getGeometry();

        if (both) {
            Coordinate[] coords = offsetBuilder.getOffsetCurve(geometry.getCoordinates(),
                distance);
            geometry = GeometryUtils.geometryFactory.createLineString(coords);
        }

        GeoJsonFeature feature = new GeoJsonFeature(geometry);
        feature.addProperty("permission", cursor.getPermissionsAsString());
        feature.addProperty("edge_id", cursor.getEdgeIndex());
        feature.addProperty("speed_ms", cursor.getSpeed());
        //Needed for filtering flags
        for (EdgeStore.EdgeFlag flag: EdgeStore.EdgeFlag.values()) {
            if (cursor.getFlag(flag)) {
                feature.addProperty(flag.toString(), true);
            }
        }
        feature.addProperty("flags", cursor.getFlagsAsString());
        return feature;
    }

    private static void updateSpeed(EdgeStore.Edge edge, Map<Short, Integer> speedUsage,
        MinMax minMax) {
        Short currentEdgeSpeed = edge.getSpeed();
        Integer currentValue = speedUsage.getOrDefault(currentEdgeSpeed, 0);
        speedUsage.put(currentEdgeSpeed, currentValue+1);
        minMax.updateMin(currentEdgeSpeed);
        minMax.updateMax(currentEdgeSpeed);
    }

    private static class MinMax {
        public short min = Short.MAX_VALUE;
        public short max = Short.MIN_VALUE;

        public void updateMin(Short currentEdgeSpeed) {
            min = (short) Math.min(currentEdgeSpeed, min);
        }

        public void updateMax(Short currentEdgeSpeed) {
            max = (short) Math.max(currentEdgeSpeed, max);
        }
    }

    private static float roundSpeed(float speed) {
        return Math.round(speed * 1000) / 1000;
    }

}
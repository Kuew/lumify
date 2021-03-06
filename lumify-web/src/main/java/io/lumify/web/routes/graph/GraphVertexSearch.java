package io.lumify.web.routes.graph;

import com.altamiracorp.bigtable.model.user.ModelUserContext;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.detectedObjects.DetectedObjectRepository;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.ontology.PropertyType;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.user.User;
import io.lumify.core.util.JsonSerializer;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.BaseRequestHandler;
import com.altamiracorp.miniweb.HandlerChain;
import org.securegraph.Authorizations;
import org.securegraph.DateOnly;
import org.securegraph.Graph;
import org.securegraph.Vertex;
import org.securegraph.query.Compare;
import org.securegraph.query.GeoCompare;
import org.securegraph.query.Query;
import org.securegraph.query.TextPredicate;
import org.securegraph.type.GeoCircle;
import com.google.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

import static io.lumify.core.model.ontology.OntologyLumifyProperties.CONCEPT_TYPE;

public class GraphVertexSearch extends BaseRequestHandler {
    //TODO should we limit to 10000??
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private final int MAX_RESULT_COUNT = 10000;

    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(GraphVertexSearch.class);
    private final Graph graph;
    private final OntologyRepository ontologyRepository;
    private final DetectedObjectRepository detectedObjectRepository;
    private final UserRepository userRepository;

    @Inject
    public GraphVertexSearch(
            final OntologyRepository ontologyRepository,
            final Graph graph,
            final UserRepository userRepository,
            final Configuration configuration,
            final WorkspaceRepository workspaceRepository,
            final DetectedObjectRepository detectedObjectRepository) {
        super(userRepository, workspaceRepository, configuration);
        this.ontologyRepository = ontologyRepository;
        this.graph = graph;
        this.detectedObjectRepository = detectedObjectRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        final String query;
        final String filter = getRequiredParameter(request, "filter");
        final long offset = getOptionalParameterLong(request, "offset", 0);
        final long size = getOptionalParameterLong(request, "size", 100);
        final String conceptType = getOptionalParameter(request, "conceptType");
        final String getLeafNodes = getOptionalParameter(request, "leafNodes");
        final String relatedToVertexId = getOptionalParameter(request, "relatedToVertexId");
        if (relatedToVertexId == null) {
            query = getRequiredParameter(request, "q");
        } else {
            query = getOptionalParameter(request, "q");
        }

        long startTime = System.nanoTime();

        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        String workspaceId = getActiveWorkspaceId(request);
        ModelUserContext modelUserContext = userRepository.getModelUserContext(authorizations, workspaceId);

        JSONArray filterJson = new JSONArray(filter);

        ontologyRepository.resolvePropertyIds(filterJson);

        graph.flush();

        LOGGER.debug("search %s\n%s", query, filterJson.toString(2));

        Query graphQuery;
        if (relatedToVertexId == null) {
            graphQuery = graph.query(query, authorizations);
        } else if (query == null || StringUtils.isBlank(query)) {
            graphQuery = graph.getVertex(relatedToVertexId, authorizations).query(authorizations);
        } else {
            graphQuery = graph.getVertex(relatedToVertexId, authorizations).query(query, authorizations);
        }

        for (int i = 0; i < filterJson.length(); i++) {
            JSONObject obj = filterJson.getJSONObject(i);
            if (obj.length() > 0) {
                updateQueryWithFilter(graphQuery, obj);
            }
        }

        if (conceptType != null) {
            Concept concept = ontologyRepository.getConceptByIRI(conceptType);
            if (getLeafNodes == null || !getLeafNodes.equals("false")) {
                List<Concept> leafNodeList = ontologyRepository.getAllLeafNodesByConcept(concept);
                if (leafNodeList.size() > 0) {
                    String[] conceptIds = new String[leafNodeList.size()];
                    int count = 0;
                    for (Concept c : leafNodeList) {
                        conceptIds[count] = c.getTitle();
                        count++;
                    }
                    graphQuery.has(CONCEPT_TYPE.getKey(), Compare.IN, conceptIds);
                }
            } else {
                graphQuery.has(CONCEPT_TYPE.getKey(), conceptType);
            }
        }

        graphQuery.limit(MAX_RESULT_COUNT);
        Iterable<Vertex> searchResults = graphQuery.vertices();

        JSONArray vertices = new JSONArray();
        JSONObject counts = new JSONObject();
        int verticesCount = 0;
        int count = 0;
        for (Vertex vertex : searchResults) {
            if (verticesCount >= offset && verticesCount <= offset + size) {
                vertices.put(JsonSerializer.toJson(vertex, workspaceId));
                vertices.getJSONObject(count).put("detectedObjects", detectedObjectRepository.toJSON(vertex, modelUserContext, authorizations, workspaceId));
                count++;
            }
            String type = CONCEPT_TYPE.getPropertyValue(vertex);
            if (type == null) {
                type = "Unknown";
            }
            if (counts.keySet().contains(type)) {
                counts.put(type, (counts.getInt(type) + 1));
            } else {
                counts.put(type, 1);
            }
            verticesCount++;
            // TODO this used create hierarchical results
        }

        JSONObject results = new JSONObject();
        results.put("vertices", vertices);
        results.put("verticesCount", counts);

        long endTime = System.nanoTime();
        LOGGER.info("Search for \"%s\" found %d vertices in %dms", query, verticesCount, (endTime - startTime) / 1000 / 1000);

        respondWithJson(response, results);
    }

    private void updateQueryWithFilter(Query graphQuery, JSONObject obj) throws ParseException {
        String predicateString = obj.optString("predicate");
        JSONArray values = obj.getJSONArray("values");
        PropertyType propertyDataType = PropertyType.convert(obj.optString("propertyDataType"));
        String propertyName = obj.getString("propertyName");
        Object value0 = jsonValueToObject(values, propertyDataType, 0);

        if (PropertyType.STRING.equals(propertyDataType) && (predicateString == null || "".equals(predicateString))) {
            graphQuery.has(propertyName, TextPredicate.CONTAINS, value0);
        } else if ("<".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.LESS_THAN, value0);
        } else if (">".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.GREATER_THAN, value0);
        } else if ("range".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.GREATER_THAN_EQUAL, value0);
            graphQuery.has(propertyName, Compare.LESS_THAN_EQUAL, jsonValueToObject(values, propertyDataType, 1));
        } else if ("=".equals(predicateString) || "equal".equals(predicateString)) {
            graphQuery.has(propertyName, Compare.EQUAL, value0);
        } else if (PropertyType.GEO_LOCATION.equals(propertyDataType)) {
            GeoCircle circle = new GeoCircle(values.getDouble(0), values.getDouble(1), values.getDouble(2));
            graphQuery.has(propertyName, GeoCompare.WITHIN, circle);
        } else {
            throw new LumifyException("unhandled query\n" + obj.toString(2));
        }
    }

    private Object jsonValueToObject(JSONArray values, PropertyType propertyDataType, int index) throws ParseException {
        if (PropertyType.DATE.equals(propertyDataType)) {
            return new DateOnly(DATE_FORMAT.parse(values.getString(index)));
        } else if (PropertyType.STRING.equals(propertyDataType)) {
            return values.getString(index);
        } else {
            return values.getDouble(index);
        }
    }
}

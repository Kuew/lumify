package io.lumify.web.routes.relationship;

import io.lumify.core.config.Configuration;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.security.LumifyVisibilityProperties;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.core.util.GraphUtil;
import io.lumify.core.util.JsonSerializer;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.BaseRequestHandler;
import com.altamiracorp.miniweb.HandlerChain;
import org.securegraph.Authorizations;
import org.securegraph.Edge;
import org.securegraph.Graph;
import org.securegraph.Visibility;
import com.google.inject.Inject;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class RelationshipSetVisibility extends BaseRequestHandler {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(RelationshipSetVisibility.class);
    private final Graph graph;
    private final VisibilityTranslator visibilityTranslator;
    private final WorkQueueRepository workQueueRepository;

    @Inject
    public RelationshipSetVisibility(
            final Graph graph,
            final UserRepository userRepository,
            final Configuration configuration,
            final VisibilityTranslator visibilityTranslator,
            final WorkspaceRepository workspaceRepository,
            final WorkQueueRepository workQueueRepository) {
        super(userRepository, workspaceRepository, configuration);
        this.graph = graph;
        this.visibilityTranslator = visibilityTranslator;
        this.workQueueRepository = workQueueRepository;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        final String graphEdgeId = getAttributeString(request, "graphEdgeId");
        final String visibilitySource = getRequiredParameter(request, "visibilitySource");

        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);
        String workspaceId = getActiveWorkspaceId(request);

        Edge graphEdge = graph.getEdge(graphEdgeId, authorizations);
        if (graphEdge == null) {
            respondWithNotFound(response);
            return;
        }

        if (!graph.isVisibilityValid(new Visibility(visibilitySource), authorizations)) {
            LOGGER.warn("%s is not a valid visibility for %s user", visibilitySource, user.getDisplayName());
            respondWithBadRequest(response, "visibilitySource", STRINGS.getString("visibility.invalid"));
            chain.next(request, response);
            return;
        }

        LOGGER.info("changing edge (%s) visibility source to %s", graphEdge.getId().toString(), visibilitySource);

        GraphUtil.updateElementVisibilitySource(visibilityTranslator, graphEdge, GraphUtil.getSandboxStatus(graphEdge, workspaceId), visibilitySource, workspaceId);

        this.graph.flush();

        this.workQueueRepository.pushGraphPropertyQueue(graphEdge, null, LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.getKey());

        JSONObject json = JsonSerializer.toJson(graphEdge, workspaceId);
        respondWithJson(response, json);
    }
}

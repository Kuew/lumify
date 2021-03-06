package io.lumify.web.routes.vertex;

import io.lumify.core.config.Configuration;
import io.lumify.core.model.user.UserRepository;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.model.workspace.diff.SandboxStatus;
import io.lumify.core.user.User;
import io.lumify.core.util.GraphUtil;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.web.BaseRequestHandler;
import io.lumify.web.routes.workspace.WorkspaceHelper;
import com.altamiracorp.miniweb.HandlerChain;
import org.securegraph.Authorizations;
import org.securegraph.Edge;
import org.securegraph.Graph;
import org.securegraph.Vertex;
import com.google.inject.Inject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class VertexRelationshipRemoval extends BaseRequestHandler {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(VertexRelationshipRemoval.class);
    private final Graph graph;
    private final WorkspaceHelper workspaceHelper;

    @Inject
    public VertexRelationshipRemoval(
            final Graph graph,
            final WorkspaceHelper workspaceHelper,
            final UserRepository userRepository,
            final WorkspaceRepository workspaceRepository,
            final Configuration configuration) {
        super(userRepository, workspaceRepository, configuration);
        this.graph = graph;
        this.workspaceHelper = workspaceHelper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, HandlerChain chain) throws Exception {
        final String sourceId = getRequiredParameter(request, "sourceId");
        final String targetId = getRequiredParameter(request, "targetId");
        final String edgeId = getRequiredParameter(request, "edgeId");
        String workspaceId = getActiveWorkspaceId(request);

        User user = getUser(request);
        Authorizations authorizations = getAuthorizations(request, user);

        Vertex sourceVertex = graph.getVertex(sourceId, authorizations);
        Vertex destVertex = graph.getVertex(targetId, authorizations);
        Edge edge = graph.getEdge(edgeId, authorizations);

        SandboxStatus sandboxStatuses = GraphUtil.getSandboxStatus(edge, workspaceId);

        if (sandboxStatuses == SandboxStatus.PUBLIC) {
            LOGGER.warn("Could not find non-public edge: %s", edgeId);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            chain.next(request, response);
            return;
        }

        respondWithJson(response, workspaceHelper.deleteEdge(edge, sourceVertex, destVertex, user, authorizations));
    }
}

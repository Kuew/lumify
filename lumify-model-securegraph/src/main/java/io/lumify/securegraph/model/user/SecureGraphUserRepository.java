package io.lumify.securegraph.model.user;

import com.altamiracorp.bigtable.model.user.ModelUserContext;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.ontology.OntologyLumifyProperties;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.user.*;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.user.Privilege;
import io.lumify.core.user.SystemUser;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import org.apache.commons.lang.StringUtils;
import org.securegraph.Graph;
import org.securegraph.Vertex;
import org.securegraph.VertexBuilder;
import org.securegraph.util.ConvertingIterable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class SecureGraphUserRepository extends UserRepository {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(SecureGraphUserRepository.class);
    private final AuthorizationRepository authorizationRepository;
    private Graph graph;
    private String userConceptId;
    private org.securegraph.Authorizations authorizations;
    private final Cache<String, Set<String>> userAuthorizationCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private final Cache<String, Set<Privilege>> userPrivilegesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private UserListenerUtil userListenerUtil;

    @Inject
    public SecureGraphUserRepository(
            final Configuration configuration,
            final AuthorizationRepository authorizationRepository,
            final Graph graph,
            final OntologyRepository ontologyRepository,
            final UserListenerUtil userListenerUtil) {
        super(configuration);
        this.authorizationRepository = authorizationRepository;
        this.graph = graph;
        this.userListenerUtil = userListenerUtil;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
        authorizationRepository.addAuthorizationToGraph(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);

        Concept userConcept = ontologyRepository.getOrCreateConcept(null, LUMIFY_USER_CONCEPT_ID, "lumifyUser");
        userConceptId = userConcept.getTitle();

        Set<String> authorizationsSet = new HashSet<String>();
        authorizationsSet.add(VISIBILITY_STRING);
        authorizationsSet.add(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        this.authorizations = authorizationRepository.createAuthorizations(authorizationsSet);
    }

    private SecureGraphUser createFromVertex(Vertex user) {
        if (user == null) {
            return null;
        }

        String[] authorizations = Iterables.toArray(getAuthorizations(user), String.class);
        ModelUserContext modelUserContext = getModelUserContext(authorizations);

        String userName = UserLumifyProperties.USERNAME.getPropertyValue(user);
        String userId = (String) user.getId();
        String userStatus = UserLumifyProperties.STATUS.getPropertyValue(user);
        Set<Privilege> privileges = Privilege.stringToPrivileges(UserLumifyProperties.PRIVILEGES.getPropertyValue(user));
        String currentWorkspaceId = UserLumifyProperties.CURRENT_WORKSPACE.getPropertyValue(user);
        LOGGER.debug("Creating user from UserRow. userName: %s, authorizations: %s", userName, UserLumifyProperties.AUTHORIZATIONS.getPropertyValue(user));
        return new SecureGraphUser(userId, userName, modelUserContext, userStatus, privileges, currentWorkspaceId);
    }

    @Override
    public User findByUsername(String username) {
        return createFromVertex(Iterables.getFirst(graph.query(authorizations)
                .has(UserLumifyProperties.USERNAME.getKey(), username)
                .has(OntologyLumifyProperties.CONCEPT_TYPE.getKey(), userConceptId)
                .vertices(), null));
    }

    @Override
    public Iterable<User> findAll() {
        return new ConvertingIterable<Vertex, User>(graph.query(authorizations)
                .has(OntologyLumifyProperties.CONCEPT_TYPE.getKey(), userConceptId)
                .vertices()) {
            @Override
            protected User convert(Vertex vertex) {
                return createFromVertex(vertex);
            }
        };
    }

    @Override
    public User findById(String userId) {
        return createFromVertex(findByIdUserVertex(userId));
    }

    public Vertex findByIdUserVertex(String userId) {
        return graph.getVertex(userId, authorizations);
    }

    @Override
    public User addUser(String username, String displayName, String password, String[] userAuthorizations) {
        User existingUser = findByUsername(username);
        if (existingUser != null) {
            throw new LumifyException("duplicate username");
        }

        String authorizationsString = StringUtils.join(userAuthorizations, ",");

        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);

        String id = "USER_" + graph.getIdGenerator().nextId().toString();
        VertexBuilder userBuilder = graph.prepareVertex(id, VISIBILITY.getVisibility(), this.authorizations);
        UserLumifyProperties.USERNAME.setProperty(userBuilder, displayName, VISIBILITY.getVisibility());
        OntologyLumifyProperties.CONCEPT_TYPE.setProperty(userBuilder, userConceptId, VISIBILITY.getVisibility());
        UserLumifyProperties.PASSWORD_SALT.setProperty(userBuilder, salt, VISIBILITY.getVisibility());
        UserLumifyProperties.PASSWORD_HASH.setProperty(userBuilder, passwordHash, VISIBILITY.getVisibility());
        UserLumifyProperties.STATUS.setProperty(userBuilder, UserStatus.OFFLINE.toString(), VISIBILITY.getVisibility());
        UserLumifyProperties.AUTHORIZATIONS.setProperty(userBuilder, authorizationsString, VISIBILITY.getVisibility());
        UserLumifyProperties.PRIVILEGES.setProperty(userBuilder, Privilege.toString(getDefaultPrivileges()), VISIBILITY.getVisibility());
        User user = createFromVertex(userBuilder.save());
        graph.flush();

        userListenerUtil.fireNewUserAddedEvent(user);

        return user;
    }

    @Override
    public void setPassword(User user, String password) {
        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserLumifyProperties.PASSWORD_SALT.setProperty(userVertex, salt, VISIBILITY.getVisibility());
        UserLumifyProperties.PASSWORD_HASH.setProperty(userVertex, passwordHash, VISIBILITY.getVisibility());
        graph.flush();
    }

    @Override
    public boolean isPasswordValid(User user, String password) {
        try {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            return UserPasswordUtil.validatePassword(password, UserLumifyProperties.PASSWORD_SALT.getPropertyValue(userVertex), UserLumifyProperties.PASSWORD_HASH.getPropertyValue(userVertex));
        } catch (Exception ex) {
            throw new RuntimeException("error validating password", ex);
        }
    }

    @Override
    public User setCurrentWorkspace(String userId, String workspaceId) {
        User user = findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserLumifyProperties.CURRENT_WORKSPACE.setProperty(userVertex, workspaceId, VISIBILITY.getVisibility());
        graph.flush();
        return user;
    }

    @Override
    public String getCurrentWorkspaceId(String userId) {
        User user = findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        return UserLumifyProperties.CURRENT_WORKSPACE.getPropertyValue(userVertex);
    }

    @Override
    public User setStatus(String userId, UserStatus status) {
        SecureGraphUser user = (SecureGraphUser) findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserLumifyProperties.STATUS.setProperty(userVertex, status.toString(), VISIBILITY.getVisibility());
        graph.flush();
        user.setUserStatus(status.toString());
        return user;
    }

    @Override
    public void addAuthorization(User user, String auth) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizations = getAuthorizations(userVertex);
        if (authorizations.contains(auth)) {
            return;
        }
        authorizations.add(auth);

        this.authorizationRepository.addAuthorizationToGraph(auth);

        String authorizationsString = StringUtils.join(authorizations, ",");
        UserLumifyProperties.AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility());
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public void removeAuthorization(User user, String auth) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizations = getAuthorizations(userVertex);
        if (!authorizations.contains(auth)) {
            return;
        }
        authorizations.remove(auth);
        String authorizationsString = StringUtils.join(authorizations, ",");
        UserLumifyProperties.AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility());
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public org.securegraph.Authorizations getAuthorizations(User user, String... additionalAuthorizations) {
        Set<String> userAuthorizations;
        if (user instanceof SystemUser) {
            userAuthorizations = new HashSet<String>();
            userAuthorizations.add(LumifyVisibility.SUPER_USER_VISIBILITY_STRING);
        } else {
            userAuthorizations = userAuthorizationCache.getIfPresent(user.getUserId());
        }
        if (userAuthorizations == null) {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            userAuthorizations = getAuthorizations(userVertex);
            userAuthorizationCache.put(user.getUserId(), userAuthorizations);
        }

        Set<String> authorizationsSet = new HashSet<String>(userAuthorizations);
        Collections.addAll(authorizationsSet, additionalAuthorizations);
        return authorizationRepository.createAuthorizations(authorizationsSet);
    }

    public static Set<String> getAuthorizations(Vertex userVertex) {
        String authorizationsString = UserLumifyProperties.AUTHORIZATIONS.getPropertyValue(userVertex);
        if (authorizationsString == null) {
            return new HashSet<String>();
        }
        String[] authorizationsArray = authorizationsString.split(",");
        if (authorizationsArray.length == 1 && authorizationsArray[0].length() == 0) {
            authorizationsArray = new String[0];
        }
        HashSet<String> authorizations = new HashSet<String>();
        for (String s : authorizationsArray) {
            // Accumulo doesn't like zero length strings. they shouldn't be in the auth string to begin with but this just protects from that happening.
            if (s.trim().length() == 0) {
                continue;
            }

            authorizations.add(s);
        }
        return authorizations;
    }

    @Override
    public Set<Privilege> getPrivileges(User user) {
        Set<Privilege> privileges;
        if (user instanceof SystemUser) {
            return Privilege.ALL;
        } else {
            privileges = userPrivilegesCache.getIfPresent(user.getUserId());
        }
        if (privileges == null) {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            privileges = getPrivileges(userVertex);
            userPrivilegesCache.put(user.getUserId(), privileges);
        }
        return privileges;
    }

    @Override
    public void delete(User user) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        graph.removeVertex(userVertex, authorizations);
    }

    @Override
    public void setPrivileges(User user, Set<Privilege> privileges) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        UserLumifyProperties.PRIVILEGES.setProperty(userVertex, Privilege.toString(privileges), VISIBILITY.getVisibility());
    }

    private Set<Privilege> getPrivileges(Vertex userVertex) {
        return Privilege.stringToPrivileges(UserLumifyProperties.PRIVILEGES.getPropertyValue(userVertex));
    }
}

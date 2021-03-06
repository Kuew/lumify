package io.lumify.sql.model.workspace;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.lumify.core.exception.LumifyAccessDeniedException;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.model.workspace.*;
import io.lumify.core.model.workspace.diff.DiffItem;
import io.lumify.core.user.User;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.sql.model.user.SqlUser;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.criterion.Restrictions;
import org.securegraph.util.ConvertingIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.securegraph.util.IterableUtils.toList;

@Singleton
public class SqlWorkspaceRepository extends WorkspaceRepository {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(SqlWorkspaceRepository.class);
    private final SessionFactory sessionFactory;

    @Inject
    public SqlWorkspaceRepository(final SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public void delete(Workspace workspace, User user) {
        if (!hasWritePermissions(workspace.getId(), user)) {
            throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getId(), user, workspace.getId());
        }

        Session session = sessionFactory.openSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            session.delete(workspace);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    @Override
    public Workspace findById(String workspaceId, User user) {
        Session session = sessionFactory.openSession();
        List workspaces = session.createCriteria(SqlWorkspace.class).add(Restrictions.eq("workspaceId", Integer.parseInt(workspaceId))).list();
        session.close();
        if (workspaces.size() == 0) {
            return null;
        } else if (workspaces.size() > 1) {
            throw new LumifyException("more than one user was returned");
        } else {
            if (!hasReadPermissions(workspaceId, user)) {
                throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have read access to workspace " + workspaceId, user, workspaceId);
            }
            return (SqlWorkspace) workspaces.get(0);
        }
    }

    @Override
    public Workspace add(String title, User user) {
        Session session = sessionFactory.openSession();

        Transaction transaction = null;
        SqlWorkspace newWorkspace;
        try {
            transaction = session.beginTransaction();
            newWorkspace = new SqlWorkspace();
            newWorkspace.setDisplayTitle(title);
            newWorkspace.setCreator((SqlUser) user);

            SqlWorkspaceUser sqlWorkspaceUser = new SqlWorkspaceUser();
            sqlWorkspaceUser.setWorkspaceAccess(WorkspaceAccess.WRITE);
            sqlWorkspaceUser.setUser((SqlUser) user);
            sqlWorkspaceUser.setWorkspace(newWorkspace);

            LOGGER.debug("add %s to workspace table", title);
            newWorkspace.getSqlWorkspaceUser().add(sqlWorkspaceUser);
            session.save(newWorkspace);
            session.save(sqlWorkspaceUser);
            session.update(user);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
        return newWorkspace;
    }

    @Override
    public Iterable<Workspace> findAll(User user) {
        Session session = sessionFactory.openSession();
        List workspaces = session.createCriteria(SqlWorkspaceUser.class)
                .add(Restrictions.eq("sqlWorkspaceUserId.user.id", Integer.parseInt(user.getUserId())))
                .add(Restrictions.in("workspaceAccess", new String[]{WorkspaceAccess.READ.toString(), WorkspaceAccess.WRITE.toString()}))
                .list();
        session.close();
        return new ConvertingIterable<Object, Workspace>(workspaces) {
            @Override
            protected Workspace convert(Object obj) {
                SqlWorkspaceUser sqlWorkspaceUser = (SqlWorkspaceUser) obj;
                return sqlWorkspaceUser.getWorkspace();
            }
        };
    }

    @Override
    public void setTitle(Workspace workspace, String title, User user) {
        Session session = sessionFactory.openSession();
        if (!hasWritePermissions(workspace.getId(), user)) {
            session.close();
            throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getId(), user, workspace.getId());
        }

        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            ((SqlWorkspace) workspace).setDisplayTitle(title);
            session.update(workspace);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    @Override
    public List<WorkspaceUser> findUsersWithAccess(String workspaceId, User user) {
        Session session = sessionFactory.openSession();
        List<WorkspaceUser> withAccess = new ArrayList<WorkspaceUser>();
        try {
            List<SqlWorkspaceUser> sqlWorkspaceUsers = getSqlWorkspaceUsers(workspaceId);

            for (SqlWorkspaceUser sqlWorkspaceUser : sqlWorkspaceUsers) {
                if (!sqlWorkspaceUser.getWorkspaceAccess().equals(WorkspaceAccess.NONE.toString())) {
                    String userId = sqlWorkspaceUser.getUser().getUserId();
                    Workspace workspace = findById(workspaceId, user);
                    boolean isCreator = ((SqlWorkspace) workspace).getCreator().getUserId().equals(userId);
                    withAccess.add(new WorkspaceUser(userId, WorkspaceAccess.valueOf(sqlWorkspaceUser.getWorkspaceAccess()), isCreator));
                }
            }
        } finally {
            session.close();
        }
        return withAccess;
    }

    @Override
    public List<WorkspaceEntity> findEntities(Workspace workspace, User user) {
        if (!hasReadPermissions(workspace.getId(), user)) {
            throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have read access to workspace " + workspace.getId(), user, workspace.getId());
        }

        Session session = sessionFactory.openSession();
        List<WorkspaceEntity> workspaceEntities = new ArrayList<WorkspaceEntity>();
        try {
            Set<SqlWorkspaceVertex> sqlWorkspaceVertices = ((SqlWorkspace) workspace).getSqlWorkspaceVertices();
            workspaceEntities = toList(new ConvertingIterable<SqlWorkspaceVertex, WorkspaceEntity>(sqlWorkspaceVertices) {
                @Override
                protected WorkspaceEntity convert(SqlWorkspaceVertex sqlWorkspaceVertex) {
                    Object vertexId = sqlWorkspaceVertex.getVertexId();

                    int graphPositionX = sqlWorkspaceVertex.getGraphPositionX();
                    int graphPositionY = sqlWorkspaceVertex.getGraphPositionY();
                    boolean visible = sqlWorkspaceVertex.isVisible();

                    return new WorkspaceEntity(vertexId, visible, graphPositionX, graphPositionY);
                }
            });
        } finally {
            session.close();
        }
        return workspaceEntities;
    }

    @Override
    public void softDeleteEntityFromWorkspace(Workspace workspace, Object vertexId, User user) {
        Session session = sessionFactory.openSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            Set<SqlWorkspaceVertex> sqlWorkspaceVertices = ((SqlWorkspace) workspace).getSqlWorkspaceVertices();
            for (SqlWorkspaceVertex sqlWorkspaceVertex : sqlWorkspaceVertices) {
                sqlWorkspaceVertex.setVisible(false);
                session.update(sqlWorkspaceVertex);
            }
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    @Override
    public void updateEntityOnWorkspace(Workspace workspace, Object vertexId, Boolean visible, Integer graphPositionX, Integer graphPositionY, User user) {
        checkNotNull(workspace, "Workspace cannot be null");

        if (!hasWritePermissions(workspace.getId(), user)) {
            throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getId(), user, workspace.getId());
        }

        Session session = sessionFactory.openSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            List vertices = session.createCriteria(SqlWorkspaceVertex.class)
                    .add(Restrictions.eq("vertexId", vertexId))
                    .add(Restrictions.eq("workspace.workspaceId", Integer.valueOf(workspace.getId())))
                    .list();
            SqlWorkspaceVertex sqlWorkspaceVertex;
            if (vertices.size() > 1) {
                throw new LumifyException("more than one vertex was returned");
            } else if (vertices.size() == 0) {
                sqlWorkspaceVertex = new SqlWorkspaceVertex();
                sqlWorkspaceVertex.setVertexId(vertexId.toString());
                sqlWorkspaceVertex.setWorkspace((SqlWorkspace) workspace);
                ((SqlWorkspace) workspace).getSqlWorkspaceVertices().add(sqlWorkspaceVertex);
                session.update(workspace);
            } else {
                sqlWorkspaceVertex = (SqlWorkspaceVertex) vertices.get(0);
            }
            sqlWorkspaceVertex.setVisible(visible);
            sqlWorkspaceVertex.setGraphPositionX(graphPositionX);
            sqlWorkspaceVertex.setGraphPositionY(graphPositionY);
            session.saveOrUpdate(sqlWorkspaceVertex);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    @Override
    public void deleteUserFromWorkspace(Workspace workspace, String userId, User user) {
        updateUserOnWorkspace(workspace, userId, WorkspaceAccess.NONE, user);
    }

    /**
     * @param workspace       workspace to update
     * @param userId          userId of the user you want to update
     * @param workspaceAccess level of access to set
     * @param user            user requesting the update
     */
    @Override
    public void updateUserOnWorkspace(Workspace workspace, String userId, WorkspaceAccess workspaceAccess, User user) {
        checkNotNull(userId);
        if (!hasWritePermissions(workspace.getId(), user)) {
            throw new LumifyAccessDeniedException("user " + user.getUserId() + " does not have write access to workspace " + workspace.getId(), user, workspace.getId());
        }

        Session session = sessionFactory.openSession();
        Transaction transaction = null;
        try {

            transaction = session.beginTransaction();
            SqlWorkspace sqlWorkspace = (SqlWorkspace) workspace;
            Set<SqlWorkspaceUser> sqlWorkspaceUsers = sqlWorkspace.getSqlWorkspaceUser();
            for (SqlWorkspaceUser sqlWorkspaceUser : sqlWorkspaceUsers) {
                if (sqlWorkspaceUser.getUser().getUserId().equals(userId)) {
                    sqlWorkspaceUser.setWorkspaceAccess(workspaceAccess);
                }
            }
            ((SqlWorkspace) workspace).setSqlWorkspaceUser(sqlWorkspaceUsers);
            session.update(workspace);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new RuntimeException(e);
        } finally {
            session.close();
        }
    }

    @Override
    public List<DiffItem> getDiff(Workspace workspace, User user) {
        return new ArrayList<DiffItem>();
    }

    @Override
    public boolean hasWritePermissions(String workspaceId, User user) {
        List<SqlWorkspaceUser> sqlWorkspaceUsers = getSqlWorkspaceUsers(workspaceId);
        for (SqlWorkspaceUser workspaceUser : sqlWorkspaceUsers) {
            if (workspaceUser.getUser().getUserId().equals(user.getUserId()) && workspaceUser.getWorkspaceAccess().equals(WorkspaceAccess.WRITE.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasReadPermissions(String workspaceId, User user) {
        if (hasWritePermissions(workspaceId, user)) {
            return true;
        }
        List<SqlWorkspaceUser> sqlWorkspaceUsers = getSqlWorkspaceUsers(workspaceId);
        for (SqlWorkspaceUser workspaceUser : sqlWorkspaceUsers) {
            if (workspaceUser.getWorkspace().getId().equals(workspaceId) &&
                    workspaceUser.getWorkspaceAccess().equals(WorkspaceAccess.READ.toString())) {
                return true;
            }
        }
        return false;
    }


    protected List<SqlWorkspaceUser> getSqlWorkspaceUsers(String workspaceId) {
        Session session = sessionFactory.openSession();
        List<SqlWorkspaceUser> sqlWorkspaceUsers;
        try {
            sqlWorkspaceUsers = session.createCriteria(SqlWorkspaceUser.class).add(Restrictions.eq("sqlWorkspaceUserId.workspace.id", Integer.parseInt(workspaceId))).list();
        } finally {
            session.close();
        }
        return sqlWorkspaceUsers;
    }

    protected List<SqlWorkspaceVertex> getSqlWorkspaceVertices(SqlWorkspace sqlWorkspace) {
        Session session = sessionFactory.openSession();
        List<SqlWorkspaceVertex> sqlWorkspaceVertices;
        try {
            sqlWorkspaceVertices = session.createCriteria(SqlWorkspaceVertex.class).add(Restrictions.eq("workspace.id", Integer.parseInt(sqlWorkspace.getId()))).list();
        } finally {
            session.close();
        }
        return sqlWorkspaceVertices;
    }
}

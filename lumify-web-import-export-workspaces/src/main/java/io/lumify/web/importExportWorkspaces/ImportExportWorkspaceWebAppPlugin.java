package io.lumify.web.importExportWorkspaces;

import com.altamiracorp.miniweb.Handler;
import com.altamiracorp.miniweb.StaticResourceHandler;
import io.lumify.web.AuthenticationProvider;
import io.lumify.web.WebApp;
import io.lumify.web.WebAppPlugin;
import io.lumify.web.privilegeFilters.AdminPrivilegeFilter;

import javax.servlet.ServletConfig;

public class ImportExportWorkspaceWebAppPlugin implements WebAppPlugin {
    @Override
    public void init(WebApp app, ServletConfig config, Class<? extends Handler> authenticator, AuthenticationProvider authenticatorInstance) {
        app.get("/admin/workspaceImport.html", authenticatorInstance, new StaticResourceHandler(getClass(), "/workspaceImport.html", "text/html"));
        app.get("/admin/workspaceExport.html", authenticatorInstance, new StaticResourceHandler(getClass(), "/workspaceExport.html", "text/html"));
        app.get("/admin/workspace/export", authenticator, AdminPrivilegeFilter.class, Export.class);
        app.post("/admin/workspace/import", authenticator, AdminPrivilegeFilter.class, Import.class);
    }
}

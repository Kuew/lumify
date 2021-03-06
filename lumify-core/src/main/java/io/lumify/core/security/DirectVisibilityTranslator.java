package io.lumify.core.security;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DirectVisibilityTranslator implements VisibilityTranslator {

    public void init(Map configuration) {

    }

    @Override
    public LumifyVisibility toVisibility(JSONObject visibilityJson) {
        StringBuilder visibilityString = new StringBuilder();

        List<String> required = new ArrayList<String>();

        String source = visibilityJson.optString(JSON_SOURCE);
        if (source != null && source.trim().length() > 0) {
            required.add(source.trim());
        }

        JSONArray workspaces = visibilityJson.optJSONArray(JSON_WORKSPACES);
        if (workspaces != null) {
            for (int i = 0; i < workspaces.length(); i++) {
                String workspace = workspaces.getString(i);
                required.add(workspace);
            }
        }

        for (String v : required) {
            if (visibilityString.length() > 0) {
                visibilityString.append("&");
            }
            visibilityString
                    .append("(")
                    .append(v)
                    .append(")");
        }

        return new LumifyVisibility(visibilityString.toString());
    }
}

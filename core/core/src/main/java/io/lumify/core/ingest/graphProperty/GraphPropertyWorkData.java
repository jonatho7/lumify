package io.lumify.core.ingest.graphProperty;

import io.lumify.core.model.workspace.Workspace;
import io.lumify.core.security.LumifyVisibilityProperties;
import org.json.JSONObject;
import org.securegraph.*;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class GraphPropertyWorkData {
    private final Element element;
    private final Property property;
    private final String workspaceId;
    private final String visibilitySource;
    private File localFile;

    public GraphPropertyWorkData(Element element, Property property, String workspaceId, String visibilitySource) {
        this.element = element;
        this.property = property;
        this.workspaceId = workspaceId;
        this.visibilitySource = visibilitySource;
    }

    public Element getElement() {
        return element;
    }

    public Property getProperty() {
        return property;
    }

    public void setLocalFile(File localFile) {
        this.localFile = localFile;
    }

    public File getLocalFile() {
        return localFile;
    }

    public Visibility getVisibility() {
        return getElement().getVisibility();
    }

    public String getWorkspaceId () { return workspaceId; }

    public String getVisibilitySource () { return visibilitySource; }

    public Map<String, Object> createPropertyMetadata() {
        Map<String, Object> metadata = new HashMap<String, Object>();
        JSONObject visibilityJson = LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.getPropertyValue(getElement());
        if (visibilityJson != null) {
            LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setMetadata(metadata, visibilityJson);
        }
        return metadata;
    }

    public void setVisibilityJsonOnElement(ElementBuilder builder) {
        JSONObject visibilityJson = LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.getPropertyValue(getElement());
        if (visibilityJson != null) {
            LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setProperty(builder, visibilityJson, getVisibility());
        }
    }

    public void setVisibilityJsonOnElement(Element element, Authorizations authorizations) {
        JSONObject visibilityJson = LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.getPropertyValue(getElement());
        if (visibilityJson != null) {
            LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setProperty(element, visibilityJson, getVisibility(), authorizations);
        }
    }
}

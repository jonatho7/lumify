package io.lumify.core.model.textHighlighting;

import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.workspace.diff.SandboxStatus;
import io.lumify.core.util.RowKeyHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public abstract class OffsetItem implements Comparable {
    public static final int VIDEO_TRANSCRIPT_INDEX_BITS = 12;
    public static final int VIDEO_TRANSCRIPT_OFFSET_BITS = 20;

    public abstract long getStart();

    public int getVideoTranscriptEntryIndex() {
        return (int) (getStart() >> VIDEO_TRANSCRIPT_OFFSET_BITS);
    }

    public abstract long getEnd();

    public abstract String getType();

    public abstract String getRowKey();

    public String getGraphVertexId() {
        return null;
    }

    public String getEdgeId() {
        return null;
    }

    public abstract SandboxStatus getSandboxStatus();

    public JSONObject getInfoJson() {
        try {
            JSONObject infoJson = new JSONObject();
            infoJson.put("start", getStart());
            infoJson.put("end", getEnd());
            infoJson.put("sandboxStatus", getSandboxStatus().toString());
            infoJson.put(LumifyProperties.ROW_KEY.getPropertyName(), RowKeyHelper.jsonEncode(getRowKey()));
            if (getGraphVertexId() != null && !getGraphVertexId().equals("") && getEdgeId() != null && !getEdgeId().equals("")) {
                infoJson.put("graphVertexId", getGraphVertexId());
                infoJson.put("edgeId", getEdgeId());
            }
            infoJson.put("type", getType());
            return infoJson;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getCssClasses() {
        ArrayList<String> classes = new ArrayList<String>();
        if (getGraphVertexId() != null && !getGraphVertexId().equals("")) {
            classes.add("resolved");
        }
        return classes;
    }

    public JSONObject toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("info", getInfoJson());

            JSONArray cssClasses = new JSONArray();
            for (String cssClass : getCssClasses()) {
                cssClasses.put(cssClass);
            }
            json.put("cssClasses", cssClasses);
            return json;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean shouldHighlight() {
        return true;
    }

    public String getTitle() {
        return null;
    }

    @Override
    public String toString() {
        return "rowKey: " + getRowKey() + ", start: " + getStart() + ", end: " + getEnd() + ", title: " + getTitle();
    }

    @Override
    public int compareTo(Object o) {
        if (!(o instanceof OffsetItem)) {
            return -1;
        }

        OffsetItem other = (OffsetItem) o;

        if (getOffset(getStart()) != getOffset(other.getStart())) {
            return getOffset(getStart()) < getOffset(other.getStart()) ? -1 : 1;
        }

        if (getOffset(getEnd()) != getOffset(other.getEnd())) {
            return getOffset(getEnd()) < getOffset(other.getEnd()) ? -1 : 1;
        }

        if (getGraphVertexId() == null && other.getGraphVertexId() == null) {
            return 0;
        }

        if (getGraphVertexId() == null) {
            return 1;
        }

        if (other.getGraphVertexId() == null) {
            return -1;
        }

        return getGraphVertexId().compareTo(other.getGraphVertexId());
    }

    public static long getOffset(long offset) {
        return offset & ((2 << (OffsetItem.VIDEO_TRANSCRIPT_OFFSET_BITS - 1)) - 1L);
    }
}

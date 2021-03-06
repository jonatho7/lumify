package io.lumify.core.ingest.graphProperty;

import com.altamiracorp.bigtable.model.FlushFlag;
import com.google.inject.Inject;
import io.lumify.core.config.Configuration;
import io.lumify.core.exception.LumifyException;
import io.lumify.core.ingest.term.extraction.TermExtractionResult;
import io.lumify.core.ingest.term.extraction.TermMention;
import io.lumify.core.ingest.term.extraction.TermRelationship;
import io.lumify.core.ingest.term.extraction.VertexRelationship;
import io.lumify.core.ingest.video.VideoTranscript;
import io.lumify.core.model.audit.AuditAction;
import io.lumify.core.model.audit.AuditRepository;
import io.lumify.core.model.ontology.Concept;
import io.lumify.core.model.ontology.OntologyRepository;
import io.lumify.core.model.properties.LumifyProperties;
import io.lumify.core.model.properties.MediaLumifyProperties;
import io.lumify.core.model.termMention.TermMentionModel;
import io.lumify.core.model.termMention.TermMentionRepository;
import io.lumify.core.model.termMention.TermMentionRowKey;
import io.lumify.core.model.workQueue.WorkQueueRepository;
import io.lumify.core.model.workspace.Workspace;
import io.lumify.core.model.workspace.WorkspaceRepository;
import io.lumify.core.security.LumifyVisibility;
import io.lumify.core.security.LumifyVisibilityProperties;
import io.lumify.core.security.VisibilityTranslator;
import io.lumify.core.user.User;
import io.lumify.core.util.GraphUtil;
import io.lumify.core.util.LumifyLogger;
import io.lumify.core.util.LumifyLoggerFactory;
import io.lumify.core.util.RowKeyHelper;
import org.json.JSONObject;
import org.securegraph.*;
import org.securegraph.mutation.ElementMutation;
import org.securegraph.mutation.ExistingElementMutation;
import org.securegraph.property.StreamingPropertyValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.securegraph.util.IterableUtils.singleOrDefault;

public abstract class GraphPropertyWorker {
    private static final LumifyLogger LOGGER = LumifyLoggerFactory.getLogger(GraphPropertyWorker.class);
    private Graph graph;
    private WorkQueueRepository workQueueRepository;
    private WorkspaceRepository workspaceRepository;
    private OntologyRepository ontologyRepository;
    private AuditRepository auditRepository;
    private TermMentionRepository termMentionRepository;
    private GraphPropertyWorkerPrepareData workerPrepareData;
    private Configuration configuration;
    private String artifactHasEntityIri;
    private String locationIri;
    private String organizationIri;
    private String personIri;
    private VisibilityTranslator visibilityTranslator;

    public void prepare(GraphPropertyWorkerPrepareData workerPrepareData) throws Exception {
        this.workerPrepareData = workerPrepareData;
        this.artifactHasEntityIri = getConfiguration().get(Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY);
        if (this.artifactHasEntityIri == null) {
            throw new LumifyException("Could not find configuration for " + Configuration.ONTOLOGY_IRI_ARTIFACT_HAS_ENTITY);
        }

        this.locationIri = (String) workerPrepareData.getStormConf().get(Configuration.ONTOLOGY_IRI_LOCATION);
        if (this.locationIri == null || this.locationIri.length() == 0) {
            throw new LumifyException("Could not find configuration: " + Configuration.ONTOLOGY_IRI_LOCATION);
        }

        this.organizationIri = (String) workerPrepareData.getStormConf().get(Configuration.ONTOLOGY_IRI_ORGANIZATION);
        if (this.organizationIri == null || this.organizationIri.length() == 0) {
            throw new LumifyException("Could not find configuration: " + Configuration.ONTOLOGY_IRI_ORGANIZATION);
        }

        this.personIri = (String) workerPrepareData.getStormConf().get(Configuration.ONTOLOGY_IRI_PERSON);
        if (this.personIri == null || this.personIri.length() == 0) {
            throw new LumifyException("Could not find configuration: " + Configuration.ONTOLOGY_IRI_PERSON);
        }
    }

    public abstract void execute(InputStream in, GraphPropertyWorkData data) throws Exception;

    public abstract boolean isHandled(Element element, Property property);

    public boolean isLocalFileRequired() {
        return false;
    }

    protected User getUser() {
        return this.workerPrepareData.getUser();
    }

    public Authorizations getAuthorizations() {
        return this.workerPrepareData.getAuthorizations();
    }

    @Inject
    public final void setGraph(Graph graph) {
        this.graph = graph;
    }

    protected Graph getGraph() {
        return graph;
    }

    @Inject
    public final void setWorkQueueRepository(WorkQueueRepository workQueueRepository) {
        this.workQueueRepository = workQueueRepository;
    }

    @Inject
    public final void setWorkspaceRepository(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    protected WorkQueueRepository getWorkQueueRepository() {
        return workQueueRepository;
    }

    protected OntologyRepository getOntologyRepository() {
        return ontologyRepository;
    }

    @Inject
    public final void setOntologyRepository(OntologyRepository ontologyRepository) {
        this.ontologyRepository = ontologyRepository;
    }

    protected AuditRepository getAuditRepository() {
        return auditRepository;
    }

    @Inject
    public final void setAuditRepository(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Inject
    public final void setVisibilityTranslatory (VisibilityTranslator visibilityTranslator) { this.visibilityTranslator = visibilityTranslator; }

    protected TermMentionRepository getTermMentionRepository() {
        return termMentionRepository;
    }

    @Inject
    public void setTermMentionRepository(TermMentionRepository termMentionRepository) {
        this.termMentionRepository = termMentionRepository;
    }

    protected Configuration getConfiguration() {
        return configuration;
    }

    @Inject
    public final void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Determines if this is a property that should be analyzed by text processing tools.
     */
    protected boolean isTextProperty(Property property) {
        if (property == null) {
            return false;
        }

        if (property.getName().equals(LumifyProperties.RAW.getPropertyName())) {
            return false;
        }

        String mimeType = (String) property.getMetadata().get(LumifyProperties.MIME_TYPE.getPropertyName());
        return !(mimeType == null || !mimeType.startsWith("text"));
    }

    protected String mapToOntologyIri(String type) {
        String ontologyClassUri;
        if ("location".equals(type)) {
            ontologyClassUri = this.locationIri;
        } else if ("organization".equals(type)) {
            ontologyClassUri = this.organizationIri;
        } else if ("person".equals(type)) {
            ontologyClassUri = this.personIri;
        } else {
            ontologyClassUri = LumifyProperties.CONCEPT_TYPE_THING;
        }
        return ontologyClassUri;
    }

    protected void addVideoTranscriptAsTextPropertiesToMutation(ExistingElementMutation<Vertex> mutation, String propertyKey, VideoTranscript videoTranscript, Map<String, Object> metadata, Visibility visibility) {
        metadata.put(LumifyProperties.META_DATA_MIME_TYPE, "text/plain");
        for (VideoTranscript.TimedText entry : videoTranscript.getEntries()) {
            String textPropertyKey = getVideoTranscriptTimedTextPropertyKey(propertyKey, entry);
            StreamingPropertyValue value = new StreamingPropertyValue(new ByteArrayInputStream(entry.getText().getBytes()), String.class);
            LumifyProperties.TEXT.addPropertyValue(mutation, textPropertyKey, value, metadata, visibility);
        }
    }

    protected void pushVideoTranscriptTextPropertiesOnWorkQueue(Element element, String propertyKey, VideoTranscript videoTranscript) {
        for (VideoTranscript.TimedText entry : videoTranscript.getEntries()) {
            String textPropertyKey = getVideoTranscriptTimedTextPropertyKey(propertyKey, entry);
            getWorkQueueRepository().pushGraphPropertyQueue(element, textPropertyKey, LumifyProperties.TEXT.getPropertyName());
        }
    }

    private String getVideoTranscriptTimedTextPropertyKey(String propertyKey, VideoTranscript.TimedText entry) {
        String startTime = String.format("%08d", Math.max(0L, entry.getTime().getStart()));
        String endTime = String.format("%08d", Math.max(0L, entry.getTime().getEnd()));
        return propertyKey + RowKeyHelper.MINOR_FIELD_SEPARATOR + MediaLumifyProperties.VIDEO_FRAME.getPropertyName() + RowKeyHelper.MINOR_FIELD_SEPARATOR + startTime + RowKeyHelper.MINOR_FIELD_SEPARATOR + endTime;
    }

    protected void saveTermExtractionResult(Vertex artifactGraphVertex, TermExtractionResult termExtractionResult,
                                            String workspaceId, String visibilitySource) {
        List<TermMentionWithGraphVertex> termMentionsResults = saveTermMentions(artifactGraphVertex,
                termExtractionResult.getTermMentions(), workspaceId, visibilitySource);
        saveRelationships(termExtractionResult.getRelationships(), termMentionsResults);
        saveVertexRelationships(termExtractionResult.getVertexRelationships(), termMentionsResults);
    }

    private void saveVertexRelationships(List<VertexRelationship> vertexRelationships, List<TermMentionWithGraphVertex> termMentionsWithGraphVertices) {
        for (VertexRelationship vertexRelationship : vertexRelationships) {
            TermMentionWithGraphVertex sourceTermMentionsWithGraphVertex = findTermMentionWithGraphVertex(termMentionsWithGraphVertices, vertexRelationship.getSource());
            checkNotNull(sourceTermMentionsWithGraphVertex, "Could not find source");
            checkNotNull(sourceTermMentionsWithGraphVertex.getVertex(), "Could not find source vertex");

            Vertex targetVertex = graph.getVertex(vertexRelationship.getTargetId(), getAuthorizations());
            checkNotNull(targetVertex, "Could not find target vertex: " + vertexRelationship.getTargetId());

            String label = vertexRelationship.getLabel();
            checkNotNull(label, "label is required");

            Edge edge = singleOrDefault(sourceTermMentionsWithGraphVertex.getVertex().getEdges(targetVertex, Direction.OUT, label, getAuthorizations()), null);
            if (edge == null) {
                LOGGER.debug("adding edge %s -> %s (%s)", sourceTermMentionsWithGraphVertex.getVertex().getId(), targetVertex.getId(), label);
                graph.addEdge(
                        sourceTermMentionsWithGraphVertex.getVertex(),
                        targetVertex,
                        label,
                        vertexRelationship.getVisibility(),
                        getAuthorizations()
                );
            }
        }
    }

    private void saveRelationships(List<TermRelationship> relationships, List<TermMentionWithGraphVertex> termMentionsWithGraphVertices) {
        for (TermRelationship relationship : relationships) {
            TermMentionWithGraphVertex sourceTermMentionsWithGraphVertex = findTermMentionWithGraphVertex(termMentionsWithGraphVertices, relationship.getSourceTermMention());
            checkNotNull(sourceTermMentionsWithGraphVertex, "source was not found for " + relationship.getSourceTermMention());
            checkNotNull(sourceTermMentionsWithGraphVertex.getVertex(), "source vertex was not found for " + relationship.getSourceTermMention());
            TermMentionWithGraphVertex destTermMentionsWithGraphVertex = findTermMentionWithGraphVertex(termMentionsWithGraphVertices, relationship.getDestTermMention());
            checkNotNull(destTermMentionsWithGraphVertex, "dest was not found for " + relationship.getDestTermMention());
            checkNotNull(destTermMentionsWithGraphVertex.getVertex(), "dest vertex was not found for " + relationship.getDestTermMention());
            String label = relationship.getLabel();

            // TODO: a better way to check if the same edge exists instead of looking it up every time?
            Edge edge = singleOrDefault(sourceTermMentionsWithGraphVertex.getVertex().getEdges(destTermMentionsWithGraphVertex.getVertex(), Direction.OUT, label, getAuthorizations()), null);
            if (edge == null) {
                graph.addEdge(
                        sourceTermMentionsWithGraphVertex.getVertex(),
                        destTermMentionsWithGraphVertex.getVertex(),
                        label,
                        relationship.getVisibility(),
                        getAuthorizations()
                );
            }
        }
        graph.flush();
    }

    private TermMentionWithGraphVertex findTermMentionWithGraphVertex(List<TermMentionWithGraphVertex> termMentionsWithGraphVertices, TermMention termMention) {
        for (TermMentionWithGraphVertex termMentionsWithGraphVertex : termMentionsWithGraphVertices) {
            if (termMentionsWithGraphVertex.getTermMention().getRowKey().getStartOffset() == termMention.getStart()
                    && termMentionsWithGraphVertex.getTermMention().getRowKey().getEndOffset() == termMention.getEnd()
                    && termMentionsWithGraphVertex.getVertex() != null) {
                return termMentionsWithGraphVertex;
            }
        }
        return null;
    }

    protected List<TermMentionWithGraphVertex> saveTermMentions(Vertex artifactGraphVertex, Iterable<TermMention> termMentions,
                                                                String workspaceId, String visibilitySource) {
        getAuditRepository().auditAnalyzedBy(AuditAction.ANALYZED_BY, artifactGraphVertex, getClass().getSimpleName(),
                getUser(), artifactGraphVertex.getVisibility());
        for (TermMentionFilter termMentionFilter : this.workerPrepareData.getTermMentionFilters()) {
            try {
                termMentions = termMentionFilter.apply(artifactGraphVertex, termMentions);
            } catch (Exception ex) {
                throw new LumifyException("Failed to run term mention filter: " + termMentionFilter.getClass().getName(), ex);
            }
        }

        List<TermMentionWithGraphVertex> results = new ArrayList<TermMentionWithGraphVertex>();
        for (TermMention termMention : termMentions) {
            results.add(saveTermMention(artifactGraphVertex, termMention, workspaceId, visibilitySource));
        }
        return results;
    }

    private TermMentionWithGraphVertex saveTermMention(Vertex artifactGraphVertex, TermMention termMention,
                                                       String workspaceId, String visibilitySource) {
        LOGGER.debug("Saving term mention '%s':%s:%s (%d:%d)", termMention.getSign(), termMention.getOntologyClassUri(), termMention.getPropertyKey(), termMention.getStart(), termMention.getEnd());
        Vertex vertex = null;
        if (visibilitySource == null) {
            visibilitySource = termMention.getVisibility().getVisibilityString();
        }

        JSONObject visibilityJson = new JSONObject();
        visibilityJson.put(VisibilityTranslator.JSON_SOURCE, visibilitySource);
        Visibility visibility = visibilityTranslator.toVisibility(visibilityJson).getVisibility();

        Visibility visibilityWithWorkspaceId;
        JSONObject visibilityJsonWithWorkspaceId;
        if (!workspaceId.equals("")) {
            visibilityJsonWithWorkspaceId = GraphUtil.updateVisibilitySourceAndAddWorkspaceId(null ,visibilitySource, workspaceId);
            visibilityWithWorkspaceId = visibilityTranslator.toVisibility(visibilityJsonWithWorkspaceId).getVisibility();
        } else {
            visibilityWithWorkspaceId = visibility;
            visibilityJsonWithWorkspaceId = visibilityJson;
        }

        TermMentionRowKey termMentionRowKey = new TermMentionRowKey(artifactGraphVertex.getId().toString(), termMention.getPropertyKey(), termMention.getStart(), termMention.getEnd());
        TermMentionModel termMentionModel = new TermMentionModel(termMentionRowKey);
        termMentionModel.getMetadata().setSign(termMention.getSign(), visibility);
        termMentionModel.getMetadata().setOntologyClassUri(termMention.getOntologyClassUri(), visibility);
        if (termMention.getProcess() != null && !termMention.getProcess().equals("")) {
            termMentionModel.getMetadata().setAnalyticProcess(termMention.getProcess(), visibility);
        }

        Concept concept = ontologyRepository.getConceptByIRI(termMention.getOntologyClassUri());
        if (concept == null) {
            LOGGER.error("Could not find ontology graph vertex '%s'", termMention.getOntologyClassUri());
            return null;
        }
        termMentionModel.getMetadata().setConceptGraphVertexId(concept.getTitle(), visibility);

        if (termMention.isResolved()) {
            String title = termMention.getSign();
            ElementMutation<Vertex> vertexElementMutation;
            if (termMention.getUseExisting()) {
                graph.flush(); // make sure the previous term mentions have made it into the graph
                if (termMention.getId() != null) {
                    vertex = graph.getVertex(termMention.getId(), getAuthorizations());
                } else {
                    vertex = singleOrDefault(graph.query(getAuthorizations())
                            .has(LumifyProperties.TITLE.getPropertyName(), title)
                            .has(LumifyProperties.CONCEPT_TYPE.getPropertyName(), concept.getTitle())
                            .vertices(), null);
                }
            }

            Map<String, Object> metadata = new HashMap<String, Object>();
            LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setMetadata(metadata, visibilityJsonWithWorkspaceId);

            if (vertex == null) {
                if (termMention.getId() != null) {
                    vertexElementMutation = graph.prepareVertex(termMention.getId(), visibilityWithWorkspaceId);
                } else {
                    vertexElementMutation = graph.prepareVertex(visibilityWithWorkspaceId);
                }
                LumifyProperties.TITLE.setProperty(vertexElementMutation, title, metadata, visibilityWithWorkspaceId);
                LumifyProperties.CONCEPT_TYPE.setProperty(vertexElementMutation, concept.getTitle(), metadata, visibilityWithWorkspaceId);
            } else {
                vertexElementMutation = vertex.prepareMutation();
            }

            for (TermMention.TermMentionProperty termMentionProperty : termMention.getNewProperties()) {
                vertexElementMutation.addPropertyValue(termMentionProperty.getKey(), termMentionProperty.getName(), termMentionProperty.getValue(), metadata, visibilityWithWorkspaceId);
            }

            LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setProperty(vertexElementMutation, visibilityJsonWithWorkspaceId, metadata, visibilityWithWorkspaceId);
            vertexElementMutation.addPropertyValue(graph.getIdGenerator().nextId(), LumifyProperties.ROW_KEY.getPropertyName(), termMentionRowKey.toString(), metadata, visibilityWithWorkspaceId);

            if (!(vertexElementMutation instanceof ExistingElementMutation)) {
                vertex = vertexElementMutation.save(getAuthorizations());
                auditRepository.auditVertexElementMutation(AuditAction.UPDATE, vertexElementMutation, vertex, termMention.getProcess(), getUser(), visibilityWithWorkspaceId);
            } else {
                auditRepository.auditVertexElementMutation(AuditAction.UPDATE, vertexElementMutation, vertex, termMention.getProcess(), getUser(), visibilityWithWorkspaceId);
                vertex = vertexElementMutation.save(getAuthorizations());
            }

            // TODO: a better way to check if the same edge exists instead of looking it up every time?
            Edge edge = singleOrDefault(artifactGraphVertex.getEdges(vertex, Direction.OUT, artifactHasEntityIri, getAuthorizations()), null);
            if (edge == null) {
                edge = graph.addEdge(artifactGraphVertex, vertex, artifactHasEntityIri, visibility, getAuthorizations());
                LumifyVisibilityProperties.VISIBILITY_JSON_PROPERTY.setProperty(edge, visibilityJsonWithWorkspaceId, metadata, visibilityWithWorkspaceId, getAuthorizations());
                auditRepository.auditRelationship(AuditAction.CREATE, artifactGraphVertex, vertex, edge, termMention.getProcess(), "", getUser(), visibilityWithWorkspaceId);
            }

            graph.flush();
            if (workspaceId != null && !workspaceId.equals("")) {
                Workspace workspace = workspaceRepository.findById(workspaceId, getUser());
                workspaceRepository.updateEntityOnWorkspace(workspace, vertex.getId(), null, null, null, getUser());
            }

            termMentionModel.getMetadata()
                    .setVertexId(vertex.getId().toString(), visibility)
                    .setEdgeId(edge.getId().toString(), visibility);
        }

        getTermMentionRepository().save(termMentionModel, FlushFlag.NO_FLUSH);
        return new TermMentionWithGraphVertex(termMentionModel, vertex);
    }

    public static class TermMentionWithGraphVertex {
        private final TermMentionModel termMention;
        private final Vertex vertex;

        public TermMentionWithGraphVertex(TermMentionModel termMention, Vertex vertex) {
            this.termMention = termMention;
            this.vertex = vertex;
        }

        public TermMentionModel getTermMention() {
            return termMention;
        }

        public Vertex getVertex() {
            return vertex;
        }
    }
}

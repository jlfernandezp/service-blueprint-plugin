package org.processmining.serviceblueprint.model;

import java.util.*;

/**
 * Modelo principal del Service Blueprint.
 * Contiene actividades clasificadas por nivel, conexiones entre ellas,
 * métricas agregadas por capa, handoffs entre capas y patrones de traza
 * para el cálculo del happy path.
 */
public class ServiceBlueprint {

    private final String name;
    private final Map<String, BlueprintActivity> activities;
    private final List<BlueprintConnection> connections;
    private final Map<BlueprintLevel, List<BlueprintActivity>> levelActivities;

    /** Métricas agregadas por nivel: eventCount, tiempo total, recurso top. */
    private final Map<BlueprintLevel, LayerMetrics> layerMetrics;
    /** Handoffs entre niveles distintos. Clave = "FROM->TO". */
    private final Map<String, HandoffMetrics> handoffs;

    /**
     * Patrones de traza para happy path.
     * Clave = actividades unidas por \u001F (separador de unidad), Valor = frecuencia.
     */
    private final Map<String, Integer> tracePatterns;

    private static final char TRACE_SEP = '\u001F';

    public ServiceBlueprint(String name) {
        this.name = name;
        this.activities = new LinkedHashMap<>();
        this.connections = new ArrayList<>();
        this.levelActivities = new EnumMap<>(BlueprintLevel.class);
        this.layerMetrics = new EnumMap<>(BlueprintLevel.class);
        this.handoffs = new LinkedHashMap<>();
        this.tracePatterns = new LinkedHashMap<>();
        for (BlueprintLevel level : BlueprintLevel.values()) {
            levelActivities.put(level, new ArrayList<BlueprintActivity>());
            layerMetrics.put(level, new LayerMetrics());
        }
    }

    // ── Actividades y conexiones ──────────────────────────────────────────────

    public BlueprintActivity getOrCreateActivity(String name) {
        BlueprintActivity activity = activities.get(name);
        if (activity == null) {
            activity = new BlueprintActivity(name);
            activities.put(name, activity);
        }
        return activity;
    }

    public void addConnection(BlueprintActivity source, BlueprintActivity target) {
        for (BlueprintConnection conn : connections) {
            if (conn.getSource().equals(source) && conn.getTarget().equals(target)) {
                conn.incrementFrequency();
                return;
            }
        }
        BlueprintConnection conn = new BlueprintConnection(source, target);
        conn.incrementFrequency();
        connections.add(conn);
    }

    public void addConnectionDirect(BlueprintActivity source, BlueprintActivity target, int frequency) {
        BlueprintConnection conn = new BlueprintConnection(source, target);
        conn.setFrequency(frequency);
        connections.add(conn);
    }

    /** Reorganiza las actividades por nivel. Llamar después de clasificar o tras drag. */
    public void organizeByLevel() {
        for (BlueprintLevel level : BlueprintLevel.values()) {
            levelActivities.get(level).clear();
        }
        for (BlueprintActivity activity : activities.values()) {
            levelActivities.get(activity.getLevel()).add(activity);
        }
    }

    // ── Recopilación de métricas ──────────────────────────────────────────────

    /** Registra un evento en el nivel dado con su recurso (puede ser null). */
    public void recordEvent(BlueprintLevel level, String resource) {
        LayerMetrics lm = layerMetrics.get(level);
        if (lm == null) return;
        lm.addEvent();
        if (resource != null && !resource.isEmpty()) lm.addResource(resource);
    }

    /**
     * Atribuye el tiempo duration_i al nivel del evento i (previousLevel).
     * Si además hay cambio de nivel, registra el handoff.
     */
    public void addHandoffDirect(HandoffMetrics hm) {
        handoffs.put(HandoffMetrics.key(hm.getFrom(), hm.getTo()), hm);
    }

    public void loadLayerMetrics(BlueprintLevel level, int eventCount,
                                 double totalTimeMs, Map<String, Integer> resources) {
        LayerMetrics lm = layerMetrics.get(level);
        if (lm != null) lm.loadData(eventCount, totalTimeMs, resources);
    }

    public void loadTracePatterns(Map<String, Integer> patterns) {
        tracePatterns.clear();
        tracePatterns.putAll(patterns);
    }

    public Map<String, Integer> getTracePatterns() {
        return Collections.unmodifiableMap(tracePatterns);
    }

    public void recordTime(BlueprintLevel previousLevel, BlueprintLevel currentLevel,
                           double durationMs) {
        LayerMetrics lm = layerMetrics.get(previousLevel);
        if (lm != null) lm.addTime(durationMs);

        if (previousLevel != currentLevel) {
            String key = HandoffMetrics.key(previousLevel, currentLevel);
            HandoffMetrics hm = handoffs.get(key);
            if (hm == null) {
                hm = new HandoffMetrics(previousLevel, currentLevel);
                handoffs.put(key, hm);
            }
            hm.addOccurrence(durationMs);
        }
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * Registra la secuencia de actividades de una traza para el cálculo del happy path.
     * Llamar una vez por traza durante el discovery.
     */
    public void recordTrace(List<String> activityNames) {
        if (activityNames == null || activityNames.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < activityNames.size(); i++) {
            if (i > 0) sb.append(TRACE_SEP);
            sb.append(activityNames.get(i));
        }
        String key = sb.toString();
        Integer count = tracePatterns.get(key);
        tracePatterns.put(key, count != null ? count + 1 : 1);
    }

    /**
     * Devuelve el conjunto de aristas del happy path (traza más frecuente).
     * Formato de cada arista: "nombreOrigen::nombreDestino".
     * Devuelve conjunto vacío si no hay trazas registradas.
     */
    public Set<String> getHappyPathEdges() {
        String bestKey = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : tracePatterns.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (bestKey == null) return Collections.emptySet();

        String[] names = bestKey.split(String.valueOf(TRACE_SEP), -1);
        Set<String> edges = new LinkedHashSet<>();
        for (int i = 0; i < names.length - 1; i++) {
            edges.add(names[i] + "::" + names[i + 1]);
        }
        return edges;
    }

    /** Frecuencia de la traza más frecuente (0 si no hay datos). */
    public int getHappyPathFrequency() {
        int best = 0;
        for (int v : tracePatterns.values()) if (v > best) best = v;
        return best;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getName() { return name; }

    public Map<String, BlueprintActivity> getActivities() {
        return Collections.unmodifiableMap(activities);
    }

    public List<BlueprintConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    public List<BlueprintActivity> getActivitiesByLevel(BlueprintLevel level) {
        return Collections.unmodifiableList(levelActivities.get(level));
    }

    public int getTotalActivities() { return activities.size(); }

    public Map<BlueprintLevel, LayerMetrics> getLayerMetrics() {
        return Collections.unmodifiableMap(layerMetrics);
    }

    public Map<String, HandoffMetrics> getHandoffs() {
        return Collections.unmodifiableMap(handoffs);
    }
}

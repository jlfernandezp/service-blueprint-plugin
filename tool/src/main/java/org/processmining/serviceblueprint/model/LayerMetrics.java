package org.processmining.serviceblueprint.model;

import java.util.*;

/**
 * Métricas agregadas para un nivel (capa) del Service Blueprint.
 *
 *   eventCount  = nº de eventos clasificados en este nivel
 *   totalTimeMs = suma de duration_i donde duration_i = ts(i+1) - ts(i),
 *                 atribuida al nivel del evento i (el evento "activo" durante ese gap)
 *   resourceCounts = recurso → nº de apariciones en eventos de este nivel
 */
public class LayerMetrics {

    private int eventCount = 0;
    private double totalTimeMs = 0.0;
    private final Map<String, Integer> resourceCounts = new LinkedHashMap<>();

    public void addEvent() {
        eventCount++;
    }

    public void addTime(double ms) {
        if (ms > 0) totalTimeMs += ms;
    }

    public void addResource(String resource) {
        if (resource == null || resource.isEmpty()) return;
        Integer c = resourceCounts.get(resource);
        resourceCounts.put(resource, c != null ? c + 1 : 1);
    }

    public int getEventCount() { return eventCount; }

    public double getTotalTimeMs() { return totalTimeMs; }

    /**
     * Recurso que más veces aparece en eventos de este nivel.
     * Devuelve "N/A" si no hay recursos registrados.
     */
    public String getTopResource() {
        if (resourceCounts.isEmpty()) return "N/A";
        String top = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : resourceCounts.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = e.getKey();
            }
        }
        return top != null ? top : "N/A";
    }

    public Map<String, Integer> getResourceCounts() {
        return Collections.unmodifiableMap(resourceCounts);
    }

    public void loadData(int eventCount, double totalTimeMs, Map<String, Integer> resources) {
        this.eventCount = eventCount;
        this.totalTimeMs = totalTimeMs;
        this.resourceCounts.clear();
        if (resources != null) this.resourceCounts.putAll(resources);
    }
}

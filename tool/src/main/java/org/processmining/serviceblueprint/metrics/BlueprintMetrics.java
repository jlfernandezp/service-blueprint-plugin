package org.processmining.serviceblueprint.metrics;

import org.processmining.serviceblueprint.model.*;

import java.util.*;

/**
 * Calcula métricas clave del Service Blueprint:
 * frecuencias, porcentajes por capa, interacciones entre niveles, cuellos de botella, etc.
 */
public class BlueprintMetrics {

    private final ServiceBlueprint blueprint;

    public BlueprintMetrics(ServiceBlueprint blueprint) {
        this.blueprint = blueprint;
    }

    public int getTotalActivities() {
        return blueprint.getTotalActivities();
    }

    public Map<BlueprintLevel, Integer> getActivitiesPerLevel() {
        Map<BlueprintLevel, Integer> result = new EnumMap<>(BlueprintLevel.class);
        for (BlueprintLevel level : BlueprintLevel.values()) {
            result.put(level, blueprint.getActivitiesByLevel(level).size());
        }
        return result;
    }

    public Map<BlueprintLevel, Double> getPercentagePerLevel() {
        Map<BlueprintLevel, Double> result = new EnumMap<>(BlueprintLevel.class);
        int total = getTotalActivities();
        for (BlueprintLevel level : BlueprintLevel.values()) {
            double pct = total > 0 ? (blueprint.getActivitiesByLevel(level).size() * 100.0) / total : 0.0;
            result.put(level, Math.round(pct * 100.0) / 100.0);
        }
        return result;
    }

    public int getTotalFrequency() {
        int total = 0;
        for (BlueprintActivity activity : blueprint.getActivities().values()) {
            total += activity.getFrequency();
        }
        return total;
    }

    public List<BlueprintActivity> getTopActivities(int n) {
        List<BlueprintActivity> sorted = new ArrayList<>(blueprint.getActivities().values());
        Collections.sort(sorted, new Comparator<BlueprintActivity>() {
            @Override
            public int compare(BlueprintActivity a, BlueprintActivity b) {
                return Integer.compare(b.getFrequency(), a.getFrequency());
            }
        });
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    public int getCrossLevelConnectionCount() {
        int count = 0;
        for (BlueprintConnection conn : blueprint.getConnections()) {
            if (conn.isCrossLevel()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Mapa de interacciones entre niveles con sus frecuencias agregadas.
     * Ej: "Cliente <-> Frontstage" -> 42
     */
    public Map<String, Integer> getCrossLevelInteractions() {
        Map<String, Integer> interactions = new LinkedHashMap<>();
        for (BlueprintConnection conn : blueprint.getConnections()) {
            if (conn.isCrossLevel()) {
                String key = conn.getSource().getLevel().getDisplayName()
                        + " <-> " + conn.getTarget().getLevel().getDisplayName();
                Integer current = interactions.get(key);
                interactions.put(key, (current != null ? current : 0) + conn.getFrequency());
            }
        }
        return interactions;
    }

    /**
     * Identifica posibles cuellos de botella: las actividades con mayor frecuencia.
     */
    public List<BlueprintActivity> getBottlenecks(int topN) {
        return getTopActivities(topN);
    }

    public Set<String> getUniqueResources() {
        Set<String> resources = new TreeSet<>();
        for (BlueprintActivity activity : blueprint.getActivities().values()) {
            if (activity.getResource() != null && !activity.getResource().isEmpty()) {
                resources.add(activity.getResource());
            }
        }
        return resources;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Service Blueprint Metrics ===\n");
        sb.append("Total actividades: ").append(getTotalActivities()).append("\n");
        sb.append("Total frecuencia: ").append(getTotalFrequency()).append("\n");
        sb.append("Conexiones entre niveles: ").append(getCrossLevelConnectionCount()).append("\n");
        sb.append("Recursos unicos: ").append(getUniqueResources().size()).append("\n\n");

        sb.append("Actividades por nivel:\n");
        for (Map.Entry<BlueprintLevel, Integer> entry : getActivitiesPerLevel().entrySet()) {
            sb.append("  ").append(entry.getKey().getDisplayName())
                    .append(": ").append(entry.getValue()).append("\n");
        }

        sb.append("\nPorcentaje por nivel:\n");
        for (Map.Entry<BlueprintLevel, Double> entry : getPercentagePerLevel().entrySet()) {
            sb.append("  ").append(entry.getKey().getDisplayName())
                    .append(": ").append(String.format("%.1f%%", entry.getValue())).append("\n");
        }

        sb.append("\nInteracciones entre niveles:\n");
        for (Map.Entry<String, Integer> entry : getCrossLevelInteractions().entrySet()) {
            sb.append("  ").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append("\n");
        }

        sb.append("\nTop 5 actividades (posibles cuellos de botella):\n");
        for (BlueprintActivity activity : getBottlenecks(5)) {
            sb.append("  ").append(activity.toString()).append("\n");
        }

        return sb.toString();
    }
}

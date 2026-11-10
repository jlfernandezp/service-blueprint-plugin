package org.processmining.serviceblueprint.model;

import java.util.*;

/**
 * Configuración del Service Blueprint.
 * Define los campos XES a evaluar (con pesos) y las keywords por nivel.
 *
 * El algoritmo de clasificación pondera cada campo:
 *   score(nivel) = SUM( matches_en_campo_i * peso_campo_i )
 * El nivel con mayor score gana. Si hay empate a 0, se clasifica como UNCATEGORIZED.
 */
public class BlueprintConfig {

    private final List<FieldConfig> fields;
    private final Map<BlueprintLevel, Set<String>> keywords;

    public BlueprintConfig() {
        this.fields = new ArrayList<>();
        this.keywords = new EnumMap<>(BlueprintLevel.class);
        for (BlueprintLevel level : BlueprintLevel.values()) {
            keywords.put(level, new LinkedHashSet<String>());
        }
    }

    public void addField(String name, double weight) {
        fields.add(new FieldConfig(name, weight));
    }

    public void setKeywords(BlueprintLevel level, Set<String> kws) {
        keywords.put(level, kws);
    }

    public List<FieldConfig> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public Map<BlueprintLevel, Set<String>> getKeywords() {
        return Collections.unmodifiableMap(keywords);
    }

    public Set<String> getKeywordsForLevel(BlueprintLevel level) {
        Set<String> kws = keywords.get(level);
        return kws != null ? Collections.unmodifiableSet(kws) : Collections.<String>emptySet();
    }

    /**
     * Configuración por defecto cuando no se carga ningún JSON.
     */
    public static BlueprintConfig createDefault() {
        BlueprintConfig config = new BlueprintConfig();

        config.addField("concept:name", 1.0);
        config.addField("org:role", 0.6);
        config.addField("org:resource", 0.6);

        config.setKeywords(BlueprintLevel.CUSTOMER, new LinkedHashSet<>(Arrays.asList(
                "customer", "user", "client", "request", "feedback",
                "registration", "booking", "payment", "confirm", "submit",
                "cancel", "login", "profile update", "purchase", "search"
        )));

        config.setKeywords(BlueprintLevel.FRONTSTAGE, new LinkedHashSet<>(Arrays.asList(
                "support", "agent", "call", "chat", "interaction",
                "service desk", "reply", "assist", "handle request",
                "respond", "agent action", "verification visible",
                "notification to user"
        )));

        config.setKeywords(BlueprintLevel.BACKSTAGE, new LinkedHashSet<>(Arrays.asList(
                "validate", "approve", "process", "internal", "review",
                "assign", "data entry", "workflow", "internal validation",
                "processing", "approval", "routing", "task assignment"
        )));

        config.setKeywords(BlueprintLevel.SUPPORT, new LinkedHashSet<>(Arrays.asList(
                "server", "system", "api", "database", "scheduler",
                "infrastructure", "automation", "monitoring",
                "system event", "service call", "api trigger",
                "db update", "background job", "system check"
        )));

        return config;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Campos configurados:\n");
        for (FieldConfig f : fields) {
            sb.append("  ").append(f).append("\n");
        }
        sb.append("Keywords por nivel:\n");
        for (Map.Entry<BlueprintLevel, Set<String>> e : keywords.entrySet()) {
            if (e.getKey() == BlueprintLevel.UNCATEGORIZED) continue;
            sb.append("  ").append(e.getKey().getDisplayName())
                    .append(": ").append(e.getValue().size()).append(" keywords\n");
        }
        return sb.toString();
    }

    /**
     * Un campo XES a evaluar con su peso.
     */
    public static class FieldConfig {
        private final String name;
        private final double weight;

        public FieldConfig(String name, double weight) {
            this.name = name;
            this.weight = weight;
        }

        public String getName() {
            return name;
        }

        public double getWeight() {
            return weight;
        }

        @Override
        public String toString() {
            return name + " (peso: " + weight + ")";
        }
    }
}

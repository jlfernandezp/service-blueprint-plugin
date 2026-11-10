package org.processmining.serviceblueprint.model;

import java.util.*;

/**
 * Representa una actividad dentro del Service Blueprint, clasificada en un nivel.
 * Almacena todos los valores de atributos observados con sus frecuencias,
 * permitiendo inspección detallada, filtrado en tiempo real e informes de keywords.
 */
public class BlueprintActivity {

    private final String name;
    private BlueprintLevel level;
    private int frequency;
    private double totalDuration;
    private int durationCount;
    private String resource;
    /** Confianza media de clasificación en [0,1]. -1 = no calculada (modo manual). */
    private double confidence = -1.0;

    /** campo -> (valor -> nº de apariciones) */
    private final Map<String, Map<String, Integer>> attributeValueCounts;

    public BlueprintActivity(String name) {
        this.name = name;
        this.level = BlueprintLevel.UNCATEGORIZED;
        this.frequency = 0;
        this.totalDuration = 0;
        this.durationCount = 0;
        this.attributeValueCounts = new LinkedHashMap<>();
    }

    public String getName() {
        return name;
    }

    public BlueprintLevel getLevel() {
        return level;
    }

    public void setLevel(BlueprintLevel level) {
        this.level = level;
    }

    public int getFrequency() {
        return frequency;
    }

    public void incrementFrequency() {
        this.frequency++;
    }

    public void addDuration(double millis) {
        this.totalDuration += millis;
        this.durationCount++;
    }

    public double getAvgDuration() {
        return durationCount > 0 ? totalDuration / durationCount : 0;
    }

    public double getTotalDuration() {
        return totalDuration;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public double getConfidence() { return confidence; }

    /**
     * Acumula la confianza del evento para calcular la media por actividad.
     * Se llama una vez por evento clasificado con confianza.
     */
    public void accumulateConfidence(double eventConfidence) {
        if (confidence < 0) {
            confidence = eventConfidence;
        } else {
            // Media acumulativa ponderada por frecuencia
            confidence = (confidence * (frequency - 1) + eventConfidence) / frequency;
        }
    }

    public void setFrequency(int frequency) { this.frequency = frequency; }

    public void setConfidence(double confidence) { this.confidence = confidence; }

    public int getDurationCount() { return durationCount; }

    public void loadDurationData(double totalDuration, int durationCount) {
        this.totalDuration = totalDuration;
        this.durationCount = durationCount;
    }

    public void loadAttributeValue(String field, String value, int count) {
        if (value == null || value.isEmpty() || count <= 0) return;
        Map<String, Integer> counts = attributeValueCounts.get(field);
        if (counts == null) {
            counts = new LinkedHashMap<>();
            attributeValueCounts.put(field, counts);
        }
        counts.put(value, count);
    }

    /**
     * Registra un valor de atributo e incrementa su contador.
     */
    public void addAttributeValue(String field, String value) {
        if (value == null || value.isEmpty()) return;
        Map<String, Integer> counts = attributeValueCounts.get(field);
        if (counts == null) {
            counts = new LinkedHashMap<>();
            attributeValueCounts.put(field, counts);
        }
        Integer current = counts.get(value);
        counts.put(value, current != null ? current + 1 : 1);
    }

    /**
     * Devuelve campo -> conjunto de valores distintos (compatible con filtrado y combos).
     */
    public Map<String, Set<String>> getAttributeValues() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : attributeValueCounts.entrySet()) {
            result.put(entry.getKey(), new LinkedHashSet<>(entry.getValue().keySet()));
        }
        return result;
    }

    /**
     * Devuelve campo -> (valor -> nº de apariciones) para informes de keywords.
     */
    public Map<String, Map<String, Integer>> getAttributeValueCounts() {
        return Collections.unmodifiableMap(attributeValueCounts);
    }

    /**
     * Devuelve todos los nombres de campo registrados.
     */
    public Set<String> getAttributeFields() {
        return Collections.unmodifiableSet(attributeValueCounts.keySet());
    }

    /**
     * Comprueba si esta actividad tiene algún evento cuyo campo contenga el valor dado
     * (búsqueda case-insensitive con contains).
     */
    public boolean matchesFilter(String field, String value) {
        Map<String, Integer> counts = attributeValueCounts.get(field);
        if (counts == null) return false;
        String lowerValue = value.toLowerCase();
        for (String v : counts.keySet()) {
            if (v.toLowerCase().contains(lowerValue)) return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BlueprintActivity)) return false;
        return name.equals(((BlueprintActivity) obj).name);
    }

    @Override
    public String toString() {
        return name + " [" + level.getDisplayName() + "] (freq=" + frequency + ")";
    }
}

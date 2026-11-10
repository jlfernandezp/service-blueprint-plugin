package org.processmining.serviceblueprint.classifier;

import org.deckfour.xes.extension.std.XConceptExtension;
import org.deckfour.xes.extension.std.XOrganizationalExtension;
import org.deckfour.xes.model.XAttribute;
import org.deckfour.xes.model.XEvent;
import org.processmining.serviceblueprint.model.BlueprintConfig;
import org.processmining.serviceblueprint.model.BlueprintLevel;

import java.util.*;

/**
 * Clasificador ponderado de actividades en niveles del Service Blueprint.
 *
 * Algoritmo:
 *   Para cada campo configurado (ej: concept:name peso=1.0, org:role peso=0.6):
 *     1. Obtiene el valor del campo en el evento
 *     2. Cuenta cuántas keywords de cada nivel coinciden en ese valor
 *     3. Multiplica el conteo por el peso del campo
 *     4. Suma al score total del nivel
 *   Devuelve el nivel con mayor score. Si ningún keyword coincide -> UNCATEGORIZED.
 *
 * Ejemplo: org:role="user_xx" (peso 0.6), concept:name="support" (peso 1.0)
 *   CUSTOMER  = 1 match en org:role ("user") * 0.6 = 0.6
 *   FRONTSTAGE = 1 match en concept:name ("support") * 1.0 = 1.0
 *   Resultado: FRONTSTAGE (1.0 > 0.6)
 */
public class KeywordClassifier {

    private final BlueprintConfig config;

    /**
     * Constructor con configuración por defecto.
     */
    public KeywordClassifier() {
        this.config = BlueprintConfig.createDefault();
    }

    /**
     * Constructor con configuración personalizada (desde JSON).
     */
    public KeywordClassifier(BlueprintConfig config) {
        this.config = config;
    }

    /**
     * Clasifica un evento XES usando scoring ponderado por campos.
     */
    public BlueprintLevel classify(XEvent event) {
        return classifyWithConfidence(event).level;
    }

    /**
     * Clasifica un evento y devuelve nivel + confianza.
     *
     * Confianza (margin-based): (score_mejor - score_segundo) / score_mejor
     *   1.0 = clasificación inequívoca (solo un nivel con score > 0)
     *   0.0 = sin señal o empate perfecto → UNCATEGORIZED
     */
    public ClassificationResult classifyWithConfidence(XEvent event) {
        Map<BlueprintLevel, Double> scores = new EnumMap<>(BlueprintLevel.class);
        for (BlueprintLevel level : BlueprintLevel.values()) {
            scores.put(level, 0.0);
        }

        for (BlueprintConfig.FieldConfig field : config.getFields()) {
            String value = extractFieldValue(event, field.getName());
            if (value == null || value.isEmpty()) {
                continue;
            }

            String valueLower = value.toLowerCase();

            for (Map.Entry<BlueprintLevel, Set<String>> entry : config.getKeywords().entrySet()) {
                BlueprintLevel level = entry.getKey();
                if (level == BlueprintLevel.UNCATEGORIZED) {
                    continue;
                }

                int matches = 0;
                for (String keyword : entry.getValue()) {
                    if (valueLower.contains(keyword)) {
                        matches++;
                    }
                }

                if (matches > 0) {
                    double weightedScore = matches * field.getWeight();
                    scores.put(level, scores.get(level) + weightedScore);
                }
            }
        }

        // Encontrar el mejor y el segundo mejor (excluyendo UNCATEGORIZED)
        BlueprintLevel bestLevel  = BlueprintLevel.UNCATEGORIZED;
        double bestScore   = 0.0;
        double secondScore = 0.0;

        for (Map.Entry<BlueprintLevel, Double> entry : scores.entrySet()) {
            if (entry.getKey() == BlueprintLevel.UNCATEGORIZED) continue;
            double s = entry.getValue();
            if (s > bestScore) {
                secondScore = bestScore;
                bestScore   = s;
                bestLevel   = entry.getKey();
            } else if (s > secondScore) {
                secondScore = s;
            }
        }

        double confidence = bestScore > 0.0
                ? (bestScore - secondScore) / bestScore
                : 0.0;

        return new ClassificationResult(bestLevel, confidence);
    }

    /**
     * Resultado de la clasificación: nivel asignado + confianza en [0,1].
     */
    public static class ClassificationResult {
        public final BlueprintLevel level;
        /** Confianza en [0,1]: margen entre el mejor y el segundo nivel. */
        public final double confidence;

        public ClassificationResult(BlueprintLevel level, double confidence) {
            this.level      = level;
            this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        }
    }

    /**
     * Extrae el valor de un campo del evento XES.
     * Soporta campos estándar y cualquier atributo genérico por nombre.
     */
    private String extractFieldValue(XEvent event, String fieldName) {
        switch (fieldName) {
            case "concept:name":
                return XConceptExtension.instance().extractName(event);
            case "org:resource":
                return XOrganizationalExtension.instance().extractResource(event);
            case "org:role":
                return XOrganizationalExtension.instance().extractRole(event);
            default:
                XAttribute attr = event.getAttributes().get(fieldName);
                return attr != null ? attr.toString() : null;
        }
    }

    public BlueprintConfig getConfig() {
        return config;
    }
}

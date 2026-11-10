package org.processmining.serviceblueprint.classifier;

import org.deckfour.xes.model.XAttribute;
import org.deckfour.xes.model.XAttributeDiscrete;
import org.deckfour.xes.model.XAttributeLiteral;
import org.deckfour.xes.model.XEvent;
import org.deckfour.xes.model.XLog;
import org.deckfour.xes.model.XTrace;
import org.processmining.serviceblueprint.model.AutoSelectConfig;
import org.processmining.serviceblueprint.model.BlueprintConfig;
import org.processmining.serviceblueprint.model.BlueprintLevel;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Selecciona automáticamente los campos XES más relevantes para clasificación
 * y calcula sus pesos mediante scoring ponderado.
 *
 * Fórmula: score(campo) = coverage * semantic * discriminative * (1 - id_penalty)
 *
 *   coverage      = fracción de eventos con valor no vacío en este campo
 *   semantic      = calidad semántica de los valores (longitud de tokens, espacios)
 *   discriminative= entropía normalizada de la distribución de valores
 *   id_penalty    = penalización por valores tipo ID (regex, ratio de unicidad)
 *
 * Al final, el peso de cada campo = score / max_score (normalizado a [0,1]).
 */
public class AutoFieldSelector {

    private final AutoSelectConfig config;
    private final List<Pattern> idPatterns;

    public AutoFieldSelector(AutoSelectConfig config) {
        this.config = config;
        this.idPatterns = new ArrayList<>();
        for (String regex : config.getIdRegexPatterns()) {
            try {
                idPatterns.add(Pattern.compile(regex));
            } catch (Exception ignored) {
                // regex inválido en el JSON → se ignora
            }
        }
    }

    /**
     * Analiza el log y devuelve un BlueprintConfig con los campos seleccionados
     * y sus pesos calculados. Las keywords vienen del AutoSelectConfig.
     *
     * @param log el XLog completo a analizar
     * @return BlueprintConfig listo para usar en KeywordClassifier
     */
    public BlueprintConfig select(XLog log) {

        // ── PASO 1: Recopilar estadísticas por campo ──────────────────────────
        int totalEvents = 0;
        // campo → nº de eventos que tienen ese campo con valor no vacío
        Map<String, Integer> fieldEventCount = new LinkedHashMap<>();
        // campo → valor → nº de ocurrencias
        Map<String, Map<String, Integer>> fieldValueFreq = new LinkedHashMap<>();

        for (XTrace trace : log) {
            for (XEvent event : trace) {
                totalEvents++;
                for (Map.Entry<String, XAttribute> entry : event.getAttributes().entrySet()) {
                    String fieldName = entry.getKey();
                    XAttribute attr = entry.getValue();

                    // Solo campos literal y discrete
                    if (!(attr instanceof XAttributeLiteral)
                            && !(attr instanceof XAttributeDiscrete)) {
                        continue;
                    }

                    String value = attr.toString().trim();
                    if (value.isEmpty()) continue;

                    Integer ec = fieldEventCount.get(fieldName);
                    fieldEventCount.put(fieldName, ec != null ? ec + 1 : 1);

                    Map<String, Integer> vf = fieldValueFreq.get(fieldName);
                    if (vf == null) {
                        vf = new LinkedHashMap<>();
                        fieldValueFreq.put(fieldName, vf);
                    }
                    Integer vc = vf.get(value);
                    vf.put(value, vc != null ? vc + 1 : 1);
                }
            }
        }

        if (totalEvents == 0) {
            return buildDefaultConfig();
        }

        // ── PASO 2: Puntuar cada campo ────────────────────────────────────────
        Map<String, Double> fieldScores = new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry : fieldEventCount.entrySet()) {
            String field = entry.getKey();
            int eventsWithField = entry.getValue();
            Map<String, Integer> valueFreq = fieldValueFreq.get(field);
            Set<String> distinctValues = valueFreq.keySet();

            double coverage = eventsWithField / (double) totalEvents;
            if (coverage < config.getMinCoverageThreshold()) continue;

            double idPenalty = computeIdPenalty(distinctValues, eventsWithField);
            if (idPenalty >= config.getMaxIdPenaltyThreshold()) continue;

            double semantic = computeSemanticScore(distinctValues);
            if (semantic <= 0.0) continue;

            double discriminative = computeNormalizedEntropy(valueFreq, eventsWithField);

            double score = coverage * semantic * discriminative * (1.0 - idPenalty);
            if (score > 0.0) {
                fieldScores.put(field, score);
            }
        }

        // ── PASO 3: Seleccionar campos ────────────────────────────────────────
        // Primero los forzados (always_include_if_present), aunque tengan score 0
        List<String> selectedFields = new ArrayList<>();
        for (String forced : config.getAlwaysIncludeIfPresent()) {
            if (fieldEventCount.containsKey(forced) && !selectedFields.contains(forced)) {
                selectedFields.add(forced);
            }
        }

        // Luego los de mayor score hasta completar maxSelectedFields
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(fieldScores.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });

        int remaining = config.getMaxSelectedFields() - selectedFields.size();
        for (Map.Entry<String, Double> e : sorted) {
            if (remaining <= 0) break;
            if (!selectedFields.contains(e.getKey())) {
                selectedFields.add(e.getKey());
                remaining--;
            }
        }

        if (selectedFields.isEmpty()) {
            return buildDefaultConfig();
        }

        // ── PASO 4: Normalizar pesos ──────────────────────────────────────────
        double maxScore = 0.0;
        for (String field : selectedFields) {
            Double s = fieldScores.get(field);
            if (s != null && s > maxScore) maxScore = s;
        }

        BlueprintConfig result = new BlueprintConfig();
        for (String field : selectedFields) {
            Double s = fieldScores.get(field);
            double weight;
            if (s != null && maxScore > 0.0) {
                weight = s / maxScore;
            } else {
                // Campo forzado sin score calculado → peso mínimo
                weight = 0.1;
            }
            weight = Math.max(0.05, Math.min(1.0, weight));
            result.addField(field, Math.round(weight * 100.0) / 100.0);
        }

        // Añadir keywords del AutoSelectConfig
        for (Map.Entry<BlueprintLevel, Set<String>> kw : config.getKeywords().entrySet()) {
            result.setKeywords(kw.getKey(), kw.getValue());
        }

        return result;
    }

    /**
     * Devuelve el informe de scoring para mostrarlo en el diálogo de resumen.
     * Mapa: campo → [coverage, semantic, discriminative, idPenalty, score]
     */
    public Map<String, double[]> computeScoreReport(XLog log) {
        int totalEvents = 0;
        Map<String, Integer> fieldEventCount = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> fieldValueFreq = new LinkedHashMap<>();

        for (XTrace trace : log) {
            for (XEvent event : trace) {
                totalEvents++;
                for (Map.Entry<String, XAttribute> entry : event.getAttributes().entrySet()) {
                    String fieldName = entry.getKey();
                    XAttribute attr = entry.getValue();
                    if (!(attr instanceof XAttributeLiteral)
                            && !(attr instanceof XAttributeDiscrete)) continue;
                    String value = attr.toString().trim();
                    if (value.isEmpty()) continue;
                    Integer ec = fieldEventCount.get(fieldName);
                    fieldEventCount.put(fieldName, ec != null ? ec + 1 : 1);
                    Map<String, Integer> vf = fieldValueFreq.get(fieldName);
                    if (vf == null) { vf = new LinkedHashMap<>(); fieldValueFreq.put(fieldName, vf); }
                    Integer vc = vf.get(value);
                    vf.put(value, vc != null ? vc + 1 : 1);
                }
            }
        }

        Map<String, double[]> report = new LinkedHashMap<>();
        if (totalEvents == 0) return report;

        for (Map.Entry<String, Integer> entry : fieldEventCount.entrySet()) {
            String field = entry.getKey();
            int eventsWithField = entry.getValue();
            Map<String, Integer> valueFreq = fieldValueFreq.get(field);
            double coverage      = eventsWithField / (double) totalEvents;
            double idPenalty     = computeIdPenalty(valueFreq.keySet(), eventsWithField);
            double semantic      = computeSemanticScore(valueFreq.keySet());
            double discriminative = computeNormalizedEntropy(valueFreq, eventsWithField);
            double score = coverage * semantic * discriminative * (1.0 - idPenalty);
            report.put(field, new double[]{coverage, semantic, discriminative, idPenalty, score});
        }
        return report;
    }

    // ── Métodos de scoring ────────────────────────────────────────────────────

    /**
     * Penalización por ID: combinación de ratio de unicidad y ratio de matches con regex de ID.
     */
    private double computeIdPenalty(Set<String> distinctValues, int totalEventsWithField) {
        if (distinctValues.isEmpty()) return 1.0;

        // Ratio de unicidad: si casi todos los valores son únicos → ID
        double uniqueRatio = distinctValues.size() / (double) totalEventsWithField;
        double uniquePenalty = uniqueRatio > config.getUniqueRatioThreshold()
                ? config.getPenaltyWeight() : 0.0;

        // Ratio de matches con patrones de ID
        int idMatches = 0;
        for (String value : distinctValues) {
            for (Pattern p : idPatterns) {
                if (p.matcher(value).matches()) {
                    idMatches++;
                    break;
                }
            }
        }
        double idRatio = (double) idMatches / distinctValues.size();

        return Math.min(1.0, Math.max(idRatio, uniquePenalty));
    }

    /**
     * Calidad semántica basada en longitud de tokens y presencia de espacios.
     * Valores cortos tipo "W", "A", "123" → score bajo.
     * Valores con palabras legibles tipo "validate request", "agent action" → score alto.
     */
    private double computeSemanticScore(Set<String> distinctValues) {
        if (distinctValues.isEmpty()) return 0.0;

        double totalTokenLen = 0;
        double totalStrLen   = 0;
        int    totalTokens   = 0;
        int    spaceCount    = 0;

        for (String value : distinctValues) {
            totalStrLen += value.length();
            String[] tokens = value.split("[ _\\-:./]+");
            for (String token : tokens) {
                if (!token.isEmpty()) {
                    totalTokenLen += token.length();
                    totalTokens++;
                }
            }
            if (value.contains(" ")) spaceCount++;
        }

        double avgTokenLen = totalTokens > 0 ? totalTokenLen / totalTokens : 0.0;
        double avgStrLen   = totalStrLen / distinctValues.size();

        if (avgTokenLen < config.getMinAvgTokenLength()
                || avgStrLen < config.getMinAvgStringLength()) {
            return 0.0;
        }

        double spaceFraction = (double) spaceCount / distinctValues.size();
        return Math.min(1.0, avgTokenLen / 10.0 + spaceFraction * config.getSpaceTokenBonus());
    }

    /**
     * Entropía de Shannon normalizada a [0,1].
     * 0 = todos los eventos tienen el mismo valor (no discrimina).
     * 1 = todos los valores son distintos (ID-like, pero ya controlado por id_penalty).
     * El valor óptimo para clasificación está entre 0.3 y 0.8.
     */
    private double computeNormalizedEntropy(Map<String, Integer> valueFreq, int totalEvents) {
        if (totalEvents == 0 || valueFreq.isEmpty()) return 0.0;

        double entropy = 0.0;
        for (int count : valueFreq.values()) {
            double p = count / (double) totalEvents;
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2));
        }

        int n = valueFreq.size();
        double maxEntropy = n > 1 ? Math.log(n) / Math.log(2) : 1.0;
        return maxEntropy > 0 ? entropy / maxEntropy : 0.0;
    }

    private BlueprintConfig buildDefaultConfig() {
        return BlueprintConfig.createDefault();
    }
}

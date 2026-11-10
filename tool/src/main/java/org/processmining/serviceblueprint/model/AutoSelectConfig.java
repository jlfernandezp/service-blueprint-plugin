package org.processmining.serviceblueprint.model;

import java.util.*;

/**
 * Parámetros de configuración para la auto-selección de campos XES.
 * Se carga desde el JSON unificado (formato autofieldselect + keywords).
 *
 * Flujo:
 *   AutoSelectConfig (parámetros del algoritmo + keywords)
 *     → AutoFieldSelector.select(XLog)
 *       → BlueprintConfig (campos con pesos calculados + keywords)
 *         → KeywordClassifier
 */
public class AutoSelectConfig {

    // --- field_selection ---
    private double minCoverageThreshold   = 0.3;
    private double maxIdPenaltyThreshold  = 0.7;
    private int    maxSelectedFields      = 3;

    // --- id_detection ---
    private List<String> idRegexPatterns = Arrays.asList(
            "^[A-Z]+[_-]?\\d+$",
            "^User_\\d+$",
            "^[0-9a-fA-F-]{16,}$"
    );
    private double uniqueRatioThreshold = 0.8;
    private double penaltyWeight        = 1.0;

    // --- semantic_analysis ---
    private double minAvgTokenLength  = 3.0;
    private double minAvgStringLength = 4.0;
    private double spaceTokenBonus    = 0.2;

    // --- confidence_calculation ---
    private double lowConfidenceThreshold = 0.2;

    // --- fallback_rules ---
    private List<String> alwaysIncludeIfPresent = Arrays.asList("concept:name");

    // --- keywords por nivel ---
    private Map<BlueprintLevel, Set<String>> keywords;

    public AutoSelectConfig() {
        keywords = new EnumMap<>(BlueprintLevel.class);
        for (BlueprintLevel level : BlueprintLevel.values()) {
            keywords.put(level, new LinkedHashSet<String>());
        }
    }

    // --- Getters ---

    public double getMinCoverageThreshold()  { return minCoverageThreshold; }
    public double getMaxIdPenaltyThreshold() { return maxIdPenaltyThreshold; }
    public int    getMaxSelectedFields()     { return maxSelectedFields; }

    public List<String> getIdRegexPatterns()     { return idRegexPatterns; }
    public double       getUniqueRatioThreshold() { return uniqueRatioThreshold; }
    public double       getPenaltyWeight()        { return penaltyWeight; }

    public double getMinAvgTokenLength()  { return minAvgTokenLength; }
    public double getMinAvgStringLength() { return minAvgStringLength; }
    public double getSpaceTokenBonus()    { return spaceTokenBonus; }

    public double getLowConfidenceThreshold() { return lowConfidenceThreshold; }

    public List<String>              getAlwaysIncludeIfPresent() { return alwaysIncludeIfPresent; }
    public Map<BlueprintLevel, Set<String>> getKeywords()        { return keywords; }

    // --- Setters (llamados desde ConfigParser) ---

    public void setMinCoverageThreshold(double v)   { minCoverageThreshold = v; }
    public void setMaxIdPenaltyThreshold(double v)  { maxIdPenaltyThreshold = v; }
    public void setMaxSelectedFields(int v)          { maxSelectedFields = v; }

    public void setIdRegexPatterns(List<String> v)  { idRegexPatterns = v; }
    public void setUniqueRatioThreshold(double v)   { uniqueRatioThreshold = v; }
    public void setPenaltyWeight(double v)           { penaltyWeight = v; }

    public void setMinAvgTokenLength(double v)  { minAvgTokenLength = v; }
    public void setMinAvgStringLength(double v) { minAvgStringLength = v; }
    public void setSpaceTokenBonus(double v)    { spaceTokenBonus = v; }

    public void setLowConfidenceThreshold(double v)  { lowConfidenceThreshold = v; }
    public void setAlwaysIncludeIfPresent(List<String> v) { alwaysIncludeIfPresent = v; }
    public void setKeywords(BlueprintLevel level, Set<String> kws) { keywords.put(level, kws); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AutoSelectConfig:\n");
        sb.append("  maxSelectedFields=").append(maxSelectedFields)
          .append(", minCoverage=").append(minCoverageThreshold)
          .append(", maxIdPenalty=").append(maxIdPenaltyThreshold).append("\n");
        sb.append("  lowConfidenceThreshold=").append(lowConfidenceThreshold).append("\n");
        sb.append("  alwaysInclude=").append(alwaysIncludeIfPresent).append("\n");
        int totalKw = 0;
        for (Set<String> kws : keywords.values()) totalKw += kws.size();
        sb.append("  keywords: ").append(totalKw).append(" total");
        return sb.toString();
    }
}

package org.processmining.serviceblueprint.classifier;

import org.processmining.serviceblueprint.model.AutoSelectConfig;
import org.processmining.serviceblueprint.model.BlueprintConfig;
import org.processmining.serviceblueprint.model.BlueprintLevel;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parser ligero de JSON para los archivos de configuración del Service Blueprint.
 * No requiere dependencias externas (GSON, Jackson, etc.).
 *
 * Detecta automáticamente el formato del JSON:
 *   - Formato MANUAL:    contiene "fields" → devuelve BlueprintConfig directamente.
 *   - Formato AUTO:      contiene "field_selection" → devuelve AutoSelectConfig
 *                        (parámetros de auto-selección + keywords).
 *
 * Uso:
 *   if (ConfigParser.isAutoSelectFormat(json)) {
 *       AutoSelectConfig asc = ConfigParser.parseAutoSelectConfig(json);
 *       // → AutoFieldSelector.select(log) → BlueprintConfig
 *   } else {
 *       BlueprintConfig cfg = ConfigParser.parse(json);
 *   }
 */
public class ConfigParser {

    // ── Patrones para formato MANUAL ─────────────────────────────────────────

    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"weight\"\\s*:\\s*([\\d.]+)\\s*\\}"
    );
    private static final Pattern LEVEL_PATTERN = Pattern.compile(
            "\"(CUSTOMER|FRONTSTAGE|BACKSTAGE|SUPPORT)\"\\s*:\\s*\\[([^\\]]+)\\]"
    );
    private static final Pattern WORD_PATTERN = Pattern.compile("\"([^\"]+)\"");

    // ── Patrones para formato AUTO ────────────────────────────────────────────

    private static final Pattern MIN_COVERAGE_PAT    =
            Pattern.compile("\"min_coverage_threshold\"\\s*:\\s*([\\d.]+)");
    private static final Pattern MAX_ID_PENALTY_PAT  =
            Pattern.compile("\"max_id_penalty_threshold\"\\s*:\\s*([\\d.]+)");
    private static final Pattern MAX_FIELDS_PAT      =
            Pattern.compile("\"max_selected_fields\"\\s*:\\s*(\\d+)");
    private static final Pattern UNIQUE_RATIO_PAT    =
            Pattern.compile("\"unique_ratio_threshold\"\\s*:\\s*([\\d.]+)");
    private static final Pattern PENALTY_WEIGHT_PAT  =
            Pattern.compile("\"penalty_weight\"\\s*:\\s*([\\d.]+)");
    private static final Pattern MIN_TOKEN_LEN_PAT   =
            Pattern.compile("\"min_avg_token_length\"\\s*:\\s*([\\d.]+)");
    private static final Pattern MIN_STR_LEN_PAT     =
            Pattern.compile("\"min_avg_string_length\"\\s*:\\s*([\\d.]+)");
    private static final Pattern SPACE_BONUS_PAT     =
            Pattern.compile("\"space_token_bonus\"\\s*:\\s*([\\d.]+)");
    private static final Pattern LOW_CONF_PAT        =
            Pattern.compile("\"low_confidence_threshold\"\\s*:\\s*([\\d.]+)");
    private static final Pattern REGEX_PATTERNS_PAT  =
            Pattern.compile("\"regex_patterns\"\\s*:\\s*\\[([^\\]]+)\\]");
    private static final Pattern ALWAYS_INCLUDE_PAT  =
            Pattern.compile("\"always_include_if_present\"\\s*:\\s*\\[([^\\]]+)\\]");

    // ── API pública ───────────────────────────────────────────────────────────

    public static BlueprintConfig parseFile(File file) throws IOException {
        return parse(readFile(file));
    }

    public static AutoSelectConfig parseAutoSelectConfigFile(File file) throws IOException {
        return parseAutoSelectConfig(readFile(file));
    }

    /**
     * Detecta si el JSON es formato auto-selección (contiene "field_selection").
     */
    public static boolean isAutoSelectFormat(String json) {
        return json.contains("\"field_selection\"");
    }

    // ── Formato MANUAL ────────────────────────────────────────────────────────

    public static BlueprintConfig parse(String json) {
        BlueprintConfig config = new BlueprintConfig();

        Matcher fieldMatcher = FIELD_PATTERN.matcher(json);
        while (fieldMatcher.find()) {
            String name   = fieldMatcher.group(1);
            double weight = Double.parseDouble(fieldMatcher.group(2));
            config.addField(name, weight);
        }

        parseKeywordsInto(json, config);

        if (config.getFields().isEmpty()) {
            config.addField("concept:name", 1.0);
            config.addField("org:role",     0.6);
            config.addField("org:resource", 0.6);
        }

        return config;
    }

    // ── Formato AUTO ──────────────────────────────────────────────────────────

    public static AutoSelectConfig parseAutoSelectConfig(String json) {
        AutoSelectConfig cfg = new AutoSelectConfig();

        Matcher m;

        m = MIN_COVERAGE_PAT.matcher(json);
        if (m.find()) cfg.setMinCoverageThreshold(Double.parseDouble(m.group(1)));

        m = MAX_ID_PENALTY_PAT.matcher(json);
        if (m.find()) cfg.setMaxIdPenaltyThreshold(Double.parseDouble(m.group(1)));

        m = MAX_FIELDS_PAT.matcher(json);
        if (m.find()) cfg.setMaxSelectedFields(Integer.parseInt(m.group(1)));

        m = UNIQUE_RATIO_PAT.matcher(json);
        if (m.find()) cfg.setUniqueRatioThreshold(Double.parseDouble(m.group(1)));

        m = PENALTY_WEIGHT_PAT.matcher(json);
        if (m.find()) cfg.setPenaltyWeight(Double.parseDouble(m.group(1)));

        m = MIN_TOKEN_LEN_PAT.matcher(json);
        if (m.find()) cfg.setMinAvgTokenLength(Double.parseDouble(m.group(1)));

        m = MIN_STR_LEN_PAT.matcher(json);
        if (m.find()) cfg.setMinAvgStringLength(Double.parseDouble(m.group(1)));

        m = SPACE_BONUS_PAT.matcher(json);
        if (m.find()) cfg.setSpaceTokenBonus(Double.parseDouble(m.group(1)));

        m = LOW_CONF_PAT.matcher(json);
        if (m.find()) cfg.setLowConfidenceThreshold(Double.parseDouble(m.group(1)));

        // regex_patterns array
        m = REGEX_PATTERNS_PAT.matcher(json);
        if (m.find()) {
            List<String> patterns = new ArrayList<>();
            Matcher wm = WORD_PATTERN.matcher(m.group(1));
            while (wm.find()) patterns.add(wm.group(1));
            if (!patterns.isEmpty()) cfg.setIdRegexPatterns(patterns);
        }

        // always_include_if_present array
        m = ALWAYS_INCLUDE_PAT.matcher(json);
        if (m.find()) {
            List<String> fields = new ArrayList<>();
            Matcher wm = WORD_PATTERN.matcher(m.group(1));
            while (wm.find()) fields.add(wm.group(1));
            if (!fields.isEmpty()) cfg.setAlwaysIncludeIfPresent(fields);
        }

        // keywords
        Matcher levelMatcher = LEVEL_PATTERN.matcher(json);
        while (levelMatcher.find()) {
            String levelName = levelMatcher.group(1);
            String wordsStr  = levelMatcher.group(2);
            BlueprintLevel level = BlueprintLevel.valueOf(levelName);
            Matcher wm = WORD_PATTERN.matcher(wordsStr);
            Set<String> words = new LinkedHashSet<>();
            while (wm.find()) words.add(wm.group(1).toLowerCase().trim());
            cfg.setKeywords(level, words);
        }

        return cfg;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void parseKeywordsInto(String json, BlueprintConfig config) {
        Matcher levelMatcher = LEVEL_PATTERN.matcher(json);
        while (levelMatcher.find()) {
            String levelName = levelMatcher.group(1);
            String wordsStr  = levelMatcher.group(2);
            BlueprintLevel level = BlueprintLevel.valueOf(levelName);
            Matcher wordMatcher = WORD_PATTERN.matcher(wordsStr);
            Set<String> words = new LinkedHashSet<>();
            while (wordMatcher.find()) words.add(wordMatcher.group(1).toLowerCase().trim());
            config.setKeywords(level, words);
        }
    }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        } finally {
            reader.close();
        }
        return sb.toString();
    }
}

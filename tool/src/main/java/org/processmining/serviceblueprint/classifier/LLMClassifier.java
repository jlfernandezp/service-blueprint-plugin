package org.processmining.serviceblueprint.classifier;

import org.processmining.serviceblueprint.model.BlueprintLevel;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LLMClassifier {

    private static final int BATCH_SIZE = 50;

    private static final long BATCH_DELAY_MS = 2_000;

    private static final long INITIAL_BACKOFF_MS = 10_000;

    private static final int MAX_RETRIES = 4;

    private static final String PROMPT_TEMPLATE =
        "You are an expert in process modelling and Service Blueprint.\n\n" +
        "Your task is to classify actions into the following regions:\n\n" +
        "1. Customer actions:\n" +
        "   - Activities performed directly by the customer.\n\n" +
        "2. Provider actions (frontstage):\n" +
        "   - Provider activities visible to the customer.\n\n" +
        "3. Provider actions (backstage):\n" +
        "   - Provider activities not visible to the customer.\n\n" +
        "4. Support actions:\n" +
        "   - Internal support activities that do not directly interact with the main service flow.\n\n" +
        "Important rules:\n" +
        "- Classify each action into ONE single category.\n" +
        "- Base your decision on the typical meaning of the action.\n" +
        "- If an action is unclear or you cannot determine its category, classify it as \"unclassified\".\n" +
        "- Be consistent across all classifications.\n\n" +
        "Actions to classify:\n" +
        "{{ACTION_LIST}}\n\n" +
        "Return the result in JSON format with this exact structure:\n\n" +
        "{\n" +
        "  \"actions\": [\n" +
        "    {\n" +
        "      \"name\": \"action\",\n" +
        "      \"category\": \"Customer actions | Provider actions (frontstage) | Provider actions (backstage) | Support actions | unclassified\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    private final String apiKey;
    private final String modelId;
    private final String apiUrl           = resolve(ENV_URL, PROP_URL, ENV_URL);
    private final String apiVersionHeader = resolve(ENV_VER_HEADER, PROP_VER_HEADER, ENV_VER_HEADER);
    private final String apiVersion       = resolve(ENV_VERSION, PROP_VERSION, ENV_VERSION);

    public interface ProgressListener {
        void onBatchComplete(int batchDone, int batchTotal, int activitiesClassified);
    }

    private ProgressListener progressListener;

    public LLMClassifier(String apiKey) {
        this(apiKey, loadModel());
    }

    public LLMClassifier(String apiKey, String modelId) {
        this.apiKey   = apiKey;
        this.modelId  = modelId;
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    // ── Configuración externa (sin codificarla en el binario) ─────────────────

    /** API key: variable de entorno / clave en .env. */
    private static final String ENV_KEY    = "LLM_API_KEY";
    /** API key: propiedad del sistema (-Dllm.api.key=...). */
    private static final String PROP_KEY   = "llm.api.key";
    /** Modelo: variable de entorno / clave en .env. */
    private static final String ENV_MODEL  = "LLM_MODEL";
    /** Modelo: propiedad del sistema (-Dllm.model=...). */
    private static final String PROP_MODEL = "llm.model";
    /** Endpoint: variable de entorno / clave en .env. */
    private static final String ENV_URL    = "LLM_API_URL";
    /** Endpoint: propiedad del sistema (-Dllm.api.url=...). */
    private static final String PROP_URL   = "llm.api.url";
    /** Nombre de la cabecera de versión de la API. */
    private static final String ENV_VER_HEADER  = "LLM_API_VERSION_HEADER";
    private static final String PROP_VER_HEADER = "llm.api.version.header";
    /** Valor de la cabecera de versión de la API. */
    private static final String ENV_VERSION  = "LLM_API_VERSION";
    private static final String PROP_VERSION = "llm.api.version";

    /**
     * Resuelve la API key sin codificarla en el binario. Orden:
     * variable de entorno → propiedad del sistema → .env (dir de trabajo y home).
     * Devuelve cadena vacía si no se encuentra.
     */
    public static String loadApiKey() {
        return resolve(ENV_KEY, PROP_KEY, ENV_KEY);
    }

    /**
     * Resuelve el identificador de modelo desde la misma configuración externa
     * (LLM_MODEL). Así no queda codificado en el binario. Vacío si no se define.
     */
    public static String loadModel() {
        return resolve(ENV_MODEL, PROP_MODEL, ENV_MODEL);
    }

    private static String resolve(String envName, String propName, String fileKey) {
        String env = System.getenv(envName);
        if (env != null && !env.trim().isEmpty()) return env.trim();

        String prop = System.getProperty(propName);
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();

        String[] dirs = { System.getProperty("user.dir"), System.getProperty("user.home") };
        for (String dir : dirs) {
            if (dir == null) continue;
            String fromFile = readEnvFile(new File(dir, ".env"), fileKey);
            if (fromFile != null && !fromFile.isEmpty()) return fromFile;
        }
        return "";
    }

    /** Lee {@code key} de un fichero .env (líneas KEY=VALUE, ignora # y prefijo export). */
    private static String readEnvFile(File file, String key) {
        if (file == null || !file.isFile()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.startsWith("export ")) t = t.substring(7).trim();
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (!t.substring(0, eq).trim().equals(key)) continue;
                String value = t.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.charAt(0) == '"'  && value.charAt(value.length() - 1) == '"')
                         || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException ignored) {
            // si no se puede leer, se trata como ausente
        }
        return null;
    }

    public Map<String, BlueprintLevel> classify(List<String> activityNames) throws IOException {
        if (activityNames == null || activityNames.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, BlueprintLevel> result = new LinkedHashMap<>();
        for (String name : activityNames) result.put(name, BlueprintLevel.UNCATEGORIZED);

        List<List<String>> batches = partition(activityNames, BATCH_SIZE);
        int classified = 0;

        for (int batchIdx = 0; batchIdx < batches.size(); batchIdx++) {
            List<String> batch = batches.get(batchIdx);

            String responseBody = callApiWithRetry(buildRequestBody(buildPrompt(batch)));
            Map<String, BlueprintLevel> partial = parseResponse(responseBody, batch);
            result.putAll(partial);
            classified += batch.size();

            if (progressListener != null) {
                progressListener.onBatchComplete(batchIdx + 1, batches.size(), classified);
            }

            if (batchIdx < batches.size() - 1) {
                sleep(BATCH_DELAY_MS);
            }
        }

        return result;
    }

    private String buildPrompt(List<String> names) {
        return PROMPT_TEMPLATE.replace("{{ACTION_LIST}}", buildList(names));
    }

    private String buildList(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) sb.append("- ").append(name).append("\n");
        return sb.toString().trim();
    }

    private String buildRequestBody(String prompt) {
        return "{\"model\":\"" + modelId + "\"," +
               "\"max_tokens\":8192," +
               "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapeJson(prompt) + "\"}]}";
    }

    private String callApiWithRetry(String requestBody) throws IOException {
        long backoffMs = INITIAL_BACKOFF_MS;
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callApi(requestBody);
            } catch (RateLimitException e) {
                lastException = e;
                long waitMs = e.retryAfterMs > 0 ? e.retryAfterMs : backoffMs;
                if (attempt < MAX_RETRIES) {
                    sleep(waitMs);
                    backoffMs *= 2;
                }
            }
        }
        throw new IOException("API de IA: límite de tasa superado tras " + MAX_RETRIES
                + " reintentos. " + (lastException != null ? lastException.getMessage() : ""),
                lastException);
    }

    private String callApi(String requestBody) throws IOException {
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("x-api-key",     apiKey);
            if (!apiVersionHeader.isEmpty() && !apiVersion.isEmpty()) {
                conn.setRequestProperty(apiVersionHeader, apiVersion);
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            if (status == 429 || status == 529) {
                long retryAfterMs = parseRetryAfter(conn.getHeaderField("Retry-After"));
                String body = readBody(conn.getErrorStream());
                throw new RateLimitException(
                        "HTTP " + status + " - demasiadas peticiones. Reintentando...\n" + body,
                        retryAfterMs);
            }

            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            String body = readBody(is);

            if (status < 200 || status >= 300) {
                throw new IOException("Error de la API de IA " + status + ": " + body.trim());
            }
            return body;

        } finally {
            conn.disconnect();
        }
    }

    private Map<String, BlueprintLevel> parseResponse(String responseBody,
                                                       List<String> activityNames) {
        Map<String, BlueprintLevel> result = new LinkedHashMap<>();
        for (String name : activityNames) result.put(name, BlueprintLevel.UNCATEGORIZED);

        String text = extractGeneratedText(responseBody);
        if (text == null || text.isEmpty()) return result;

        String json = extractJsonBlock(text);
        if (json == null) return result;

        Pattern p = Pattern.compile(
                "\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*\"category\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}",
                Pattern.DOTALL);
        Matcher m = p.matcher(json);
        while (m.find()) {
            String name    = unescapeJson(m.group(1));
            String category = unescapeJson(m.group(2));
            String matched   = findBestMatch(name, activityNames);
            if (matched != null) result.put(matched, mapCategory(category));
        }
        return result;
    }

    private String extractGeneratedText(String responseBody) {
        int pos = 0;
        while (pos < responseBody.length()) {
            int typeKeyIdx = findJsonKey(responseBody, "type", pos);
            if (typeKeyIdx < 0) break;
            String typeValue = readJsonStringValue(responseBody, typeKeyIdx + 6);
            if ("text".equals(typeValue)) {
                int textKeyIdx = findJsonKey(responseBody, "text", typeKeyIdx + 1);
                if (textKeyIdx >= 0) {
                    String textValue = readJsonStringValue(responseBody, textKeyIdx + 6);
                    if (textValue != null) return unescapeJson(textValue);
                }
            }
            pos = typeKeyIdx + 1;
        }
        return null;
    }

    private int findJsonKey(String s, String key, int from) {
        String quoted = "\"" + key + "\"";
        int pos = from;
        while (pos < s.length()) {
            int idx = s.indexOf(quoted, pos);
            if (idx < 0) return -1;
            int check = idx + quoted.length();
            while (check < s.length() && Character.isWhitespace(s.charAt(check))) check++;
            if (check < s.length() && s.charAt(check) == ':') return idx;
            pos = idx + 1;
        }
        return -1;
    }

    private String readJsonStringValue(String s, int afterKey) {
        int pos = afterKey;
        while (pos < s.length() && (Character.isWhitespace(s.charAt(pos)) || s.charAt(pos) == ':')) pos++;
        if (pos >= s.length() || s.charAt(pos) != '"') return null;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\' && pos + 1 < s.length()) {
                sb.append(c).append(s.charAt(pos + 1));
                pos += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                pos++;
            }
        }
        return sb.toString();
    }

    private String extractJsonBlock(String text) {
        Matcher md = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").matcher(text);
        if (md.find()) return md.group(1);

        int start = text.indexOf("{");
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private BlueprintLevel mapCategory(String category) {
        if (category == null) return BlueprintLevel.UNCATEGORIZED;
        String lower = category.toLowerCase(Locale.ROOT).trim();
        if (lower.contains("customer"))   return BlueprintLevel.CUSTOMER;
        if (lower.contains("frontstage")) return BlueprintLevel.FRONTSTAGE;
        if (lower.contains("backstage"))  return BlueprintLevel.BACKSTAGE;
        if (lower.contains("support"))    return BlueprintLevel.SUPPORT;
        return BlueprintLevel.UNCATEGORIZED;
    }

    private String findBestMatch(String aiName, List<String> activityNames) {
        for (String a : activityNames) if (a.equals(aiName)) return a;
        String lower = aiName.toLowerCase(Locale.ROOT);
        for (String a : activityNames) if (a.toLowerCase(Locale.ROOT).equals(lower)) return a;
        for (String a : activityNames) {
            String al = a.toLowerCase(Locale.ROOT);
            if (al.contains(lower) || lower.contains(al)) return a;
        }
        return null;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size)
            result.add(list.subList(i, Math.min(i + size, list.size())));
        return result;
    }

    private String readBody(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private long parseRetryAfter(String header) {
        if (header == null || header.isEmpty()) return 0;
        try { return Long.parseLong(header.trim()) * 1000L; }
        catch (NumberFormatException e) { return 0; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static class RateLimitException extends IOException {
        final long retryAfterMs;
        RateLimitException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }
}

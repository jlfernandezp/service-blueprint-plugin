package org.processmining.serviceblueprint.plugin;

import org.deckfour.xes.extension.std.XConceptExtension;
import org.deckfour.xes.extension.std.XOrganizationalExtension;
import org.deckfour.xes.extension.std.XTimeExtension;
import org.deckfour.xes.model.XAttribute;
import org.deckfour.xes.model.XEvent;
import org.deckfour.xes.model.XLog;
import org.deckfour.xes.model.XTrace;
import org.processmining.contexts.uitopia.annotations.UITopiaVariant;
import org.processmining.framework.plugin.PluginContext;
import org.processmining.framework.plugin.annotations.Plugin;
import org.processmining.framework.plugin.annotations.PluginVariant;
import org.processmining.serviceblueprint.classifier.AutoFieldSelector;
import org.processmining.serviceblueprint.classifier.ConfigParser;
import org.processmining.serviceblueprint.classifier.KeywordClassifier;
import org.processmining.serviceblueprint.classifier.LLMClassifier;
import org.processmining.serviceblueprint.model.AutoSelectConfig;
import org.processmining.serviceblueprint.model.BlueprintActivity;
import org.processmining.serviceblueprint.model.BlueprintConfig;
import org.processmining.serviceblueprint.model.BlueprintLevel;
import org.processmining.serviceblueprint.model.ServiceBlueprint;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Frame;
import java.io.File;
import java.util.*;

@Plugin(
        name = "Discover Service Blueprint 1.0.0",
        parameterLabels = {"Event Log"},
        returnLabels = {"Service Blueprint"},
        returnTypes = {ServiceBlueprint.class},
        userAccessible = true,
        help = "Discover a Service Blueprint from an event log XES, classifying activities " +
               "on four levels (Client, Frontstage, Backstage, Support). " +
               "Four modes: AI, field auto-selection, manual JSON configuration, or default configuration."
)
public class ServiceBlueprintDiscoveryPlugin {

    private static final int OPT_AI       = 0;
    private static final int OPT_AUTO     = 1;
    private static final int OPT_MANUAL   = 2;
    private static final int OPT_DEFAULT  = 3;

    @UITopiaVariant(
            affiliation = "Service Blueprint",
            author = "ProM Service Blueprint",
            email = ""
    )
    @PluginVariant(
            variantLabel = "Discover Service Blueprint from Event Log",
            requiredParameterLabels = {0}
    )
    public ServiceBlueprint discover(PluginContext context, XLog log) {

        // ── Paso 1: Seleccionar modo ──────────────────────────────────────────
        int mode = showModeDialog();
        if (mode < 0) mode = OPT_DEFAULT;

        // ── Paso 2: Clasificación y configuración ─────────────────────────────
        // aiMap: resultado del clasificador IA, puede ser null
        Map<String, BlueprintLevel> aiMap = null;
        BlueprintConfig config = null;
        boolean useConfidence = false;

        if (mode == OPT_AI) {
            aiMap = runAiClassification(log);
            if (aiMap == null) config = BlueprintConfig.createDefault();
        } else if (mode == OPT_AUTO) {
            config = runAutoSelection(log);
            useConfidence = (config != null);
            if (config == null) config = BlueprintConfig.createDefault();
        } else if (mode == OPT_MANUAL) {
            config = loadManualConfig();
            if (config == null) config = BlueprintConfig.createDefault();
        } else {
            config = BlueprintConfig.createDefault();
        }

        // ── Paso 3: Discovery ─────────────────────────────────────────────────
        KeywordClassifier classifier = (config != null) ? new KeywordClassifier(config) : null;

        context.getProgress().setMinimum(0);
        context.getProgress().setMaximum(log.size());

        String logName = XConceptExtension.instance().extractName(log);
        if (logName == null || logName.isEmpty()) logName = "Service Blueprint";

        ServiceBlueprint blueprint = new ServiceBlueprint(logName);

        for (int i = 0; i < log.size(); i++) {
            XTrace trace = log.get(i);
            BlueprintActivity previousActivity = null;
            Date previousTimestamp = null;
            List<String> traceNames = new ArrayList<>();

            for (XEvent event : trace) {
                String conceptName = XConceptExtension.instance().extractName(event);
                if (conceptName == null || conceptName.isEmpty()) continue;
                traceNames.add(conceptName);

                Date timestamp = XTimeExtension.instance().extractTimestamp(event);

                BlueprintActivity activity = blueprint.getOrCreateActivity(conceptName);
                activity.incrementFrequency();

                // Clasificación según el modo elegido
                if (aiMap != null) {
                    BlueprintLevel level = aiMap.get(conceptName);
                    activity.setLevel(level != null ? level : BlueprintLevel.UNCATEGORIZED);
                } else if (useConfidence) {
                    KeywordClassifier.ClassificationResult result =
                            classifier.classifyWithConfidence(event);
                    activity.setLevel(result.level);
                    activity.accumulateConfidence(result.confidence);
                } else {
                    activity.setLevel(classifier.classify(event));
                }

                // Atributos del evento
                for (Map.Entry<String, XAttribute> attrEntry : event.getAttributes().entrySet()) {
                    activity.addAttributeValue(attrEntry.getKey(), attrEntry.getValue().toString());
                }

                // Recurso
                String resource = XOrganizationalExtension.instance().extractResource(event);
                if (resource != null && !resource.isEmpty()) activity.setResource(resource);

                // Métricas por capa
                blueprint.recordEvent(activity.getLevel(), resource);

                // Duración + handoff
                if (previousActivity != null && previousTimestamp != null && timestamp != null) {
                    double duration = timestamp.getTime() - previousTimestamp.getTime();
                    if (duration > 0) {
                        activity.addDuration(duration);
                        blueprint.recordTime(previousActivity.getLevel(),
                                             activity.getLevel(), duration);
                    }
                }

                // Conexión directly-follows
                if (previousActivity != null) blueprint.addConnection(previousActivity, activity);

                previousActivity  = activity;
                previousTimestamp = timestamp;
            }

            blueprint.recordTrace(traceNames);
            context.getProgress().setValue(i + 1);
        }

        blueprint.organizeByLevel();
        context.getFutureResult(0).setLabel("Service Blueprint: " + logName);
        return blueprint;
    }

    // ── Diálogo de modo ───────────────────────────────────────────────────────

    private int showModeDialog() {
        String[] options = {
            "IA (automatic classification)",
            "Field Auto-Selection (JSON)",
            "Manual configuration (JSON)",
            "Default configuration"
        };
        return JOptionPane.showOptionDialog(
                null,
                "Select sort mode:\n\n"
                + "  IA:               sends activity names to an AI model\n"
                + "                    to classify them automatically.\n\n"
                + "  Auto-seleccion:   the plugin analyzes the log and chooses the\n"
                + "                    better fields automatically.\n"
                + "                    Requires a JSON with parameters + keywords.\n\n"
                + "  Manual:           you define the fields, weights and keywords\n"
                + "                    in a JSON.\n\n"
                + "  Default:          concept:name (1.0), org:role (0.6),\n"
                + "                    org:resource (0.6) with predefined keywords.",
                "Service Blueprint - Configuration",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );
    }

    // ── Modo IA ─────────────────────────────────────────────────────────────

    private Map<String, BlueprintLevel> runAiClassification(XLog log) {
        String apiKey = (String) JOptionPane.showInputDialog(
                null,
                "Enter the API key:",
                "API Key",
                JOptionPane.PLAIN_MESSAGE,
                null, null,
                LLMClassifier.loadApiKey());

        if (apiKey == null || apiKey.trim().isEmpty()) return null;
        apiKey = apiKey.trim();

        List<String> uniqueNames = extractUniqueActivityNames(log);
        if (uniqueNames.isEmpty()) {
            JOptionPane.showMessageDialog(null,
                    "The log does not contain activities with concept:name.",
                    "Empty Log", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        int totalBatches = (int) Math.ceil(uniqueNames.size() / 50.0);
        int estimatedSecs = totalBatches > 1 ? totalBatches * 2 + 8 : 8;

        int confirm = JOptionPane.showConfirmDialog(null,
                uniqueNames.size() + " unique activities detected.\n"
                + "They will be sent in " + totalBatches + " batch(es) of 50.\n"
                + "Estimated time: ~" + estimatedSecs + " seconds.\n\n"
                + "Automatic classification with AI\n\n"
                + "¿Continue?",
                "Classification with AI",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return null;

        final JDialog progressDialog = new JDialog((Frame) null, "IA - Classifying...", false);
        final JLabel progressLabel   = new JLabel("Starting...");
        final JProgressBar progressBar = new JProgressBar(0, totalBatches);
        progressBar.setStringPainted(true);
        progressLabel.setBorder(BorderFactory.createEmptyBorder(10, 16, 4, 16));
        progressBar.setBorder(BorderFactory.createEmptyBorder(4, 16, 10, 16));
        JPanel pPanel = new JPanel(new BorderLayout());
        pPanel.add(progressLabel, BorderLayout.NORTH);
        pPanel.add(progressBar,   BorderLayout.CENTER);
        progressDialog.setContentPane(pPanel);
        progressDialog.setSize(380, 100);
        progressDialog.setLocationRelativeTo(null);
        progressDialog.setVisible(true);

        try {
            final String finalApiKey = apiKey;
            LLMClassifier ai = new LLMClassifier(finalApiKey);

            ai.setProgressListener(new LLMClassifier.ProgressListener() {
                @Override
                public void onBatchComplete(int batchDone, int batchTotal, int activitiesClassified) {
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override public void run() {
                            progressBar.setValue(batchDone);
                            progressLabel.setText("Lote " + batchDone + "/" + batchTotal
                                    + " — " + activitiesClassified + " actividades clasificadas"
                                    + (batchDone < batchTotal ? " — esperando 2 s..." : " — completado"));
                        }
                    });
                }
            });

            Map<String, BlueprintLevel> result = ai.classify(uniqueNames);

            progressDialog.dispose();
            showAiSummary(result, "IA");
            return result;

        } catch (Exception e) {
            progressDialog.dispose();
            JOptionPane.showMessageDialog(null,
                    "Error calling the AI:\n" + e.getMessage()
                    + "\n\nThe default settings will be used.",
                    "Error IA", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /**
     * Extrae los concept:name únicos del log (preserva el orden de primera aparición).
     */
    private List<String> extractUniqueActivityNames(XLog log) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (XTrace trace : log) {
            for (XEvent event : trace) {
                String name = XConceptExtension.instance().extractName(event);
                if (name != null && !name.isEmpty()) seen.add(name);
            }
        }
        return new ArrayList<>(seen);
    }

    private void showAiSummary(Map<String, BlueprintLevel> result, String modelLabel) {
        Map<BlueprintLevel, Integer> counts = new EnumMap<>(BlueprintLevel.class);
        for (BlueprintLevel level : BlueprintLevel.values()) counts.put(level, 0);
        for (BlueprintLevel level : result.values()) counts.put(level, counts.get(level) + 1);

        StringBuilder sb = new StringBuilder("Ranking completed.\n\n");
        for (BlueprintLevel level : BlueprintLevel.values()) {
            int c = counts.get(level);
            if (c > 0) {
                sb.append(String.format("  %-22s %d actividades%n",
                        level.getDisplayName() + ":", c));
            }
        }
        JOptionPane.showMessageDialog(null, sb.toString(),
                modelLabel + " - Resultado", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Modo AUTO ─────────────────────────────────────────────────────────────

    private BlueprintConfig runAutoSelection(XLog log) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Seleccionar JSON de auto-seleccion (autofieldselect + keywords)");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));

        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;

        File file = chooser.getSelectedFile();
        try {
            String json = readFileContent(file);

            if (!ConfigParser.isAutoSelectFormat(json)) {
                JOptionPane.showMessageDialog(null,
                        "The file does not appear to be an auto-selecting JSON file.\n"
                        + "(does not contain the key \"field_selection\").\n\n"
                        + "The default settings will be used.",
                        "Unrecognized format", JOptionPane.WARNING_MESSAGE);
                return null;
            }

            AutoSelectConfig autoConfig = ConfigParser.parseAutoSelectConfig(json);

            JOptionPane.showMessageDialog(null,
                    "Analyzing the log to select fields...\n"
                    + "(This process may take a few seconds for large logs)",
                    "Auto-selection in progress", JOptionPane.INFORMATION_MESSAGE);

            AutoFieldSelector selector = new AutoFieldSelector(autoConfig);
            BlueprintConfig config = selector.select(log);

            showAutoSelectionSummary(config, autoConfig);
            return config;

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Error processing the JSON:\n" + e.getMessage()
                    + "\n\nThe default settings will be used.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    private void showAutoSelectionSummary(BlueprintConfig config, AutoSelectConfig autoConfig) {
        StringBuilder sb = new StringBuilder();
        sb.append("Auto-seleccion complete.\n\n");
        sb.append("Selected fields and calculated weights:\n");
        for (BlueprintConfig.FieldConfig f : config.getFields()) {
            sb.append(String.format("  %-22s peso: %.2f%n", f.getName(), f.getWeight()));
        }
        sb.append("\nKeywords by Level:\n");
        for (Map.Entry<BlueprintLevel, Set<String>> e : config.getKeywords().entrySet()) {
            if (e.getKey() == BlueprintLevel.UNCATEGORIZED) continue;
            sb.append("  ").append(e.getKey().getDisplayName())
              .append(": ").append(e.getValue().size()).append(" keywords\n");
        }
        sb.append("\nLow confidence threshold: ")
          .append(autoConfig.getLowConfidenceThreshold())
          .append("\n(nodes below → dashed line in the visualization)");

        JOptionPane.showMessageDialog(null, sb.toString(),
                "Auto-seleccion complete", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Modo MANUAL ───────────────────────────────────────────────────────────

    private BlueprintConfig loadManualConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select configuration file (.json)");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));

        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;

        File file = chooser.getSelectedFile();
        try {
            BlueprintConfig config = ConfigParser.parseFile(file);

            StringBuilder sb = new StringBuilder("Manual configuration loaded:\n\n");
            sb.append("Fields:\n");
            for (BlueprintConfig.FieldConfig f : config.getFields()) {
                sb.append(String.format("  %-22s peso: %.2f%n", f.getName(), f.getWeight()));
            }
            sb.append("\nKeywords by level:\n");
            for (Map.Entry<BlueprintLevel, Set<String>> e : config.getKeywords().entrySet()) {
                if (e.getKey() == BlueprintLevel.UNCATEGORIZED) continue;
                sb.append("  ").append(e.getKey().getDisplayName())
                  .append(": ").append(e.getValue().size()).append(" keywords\n");
            }
            JOptionPane.showMessageDialog(null, sb.toString(),
                    "Settings loaded", JOptionPane.INFORMATION_MESSAGE);
            return config;

        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Error loading settings:\n" + e.getMessage()
                    + "\n\nThe default settings will be used.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    // ── Utilidad ──────────────────────────────────────────────────────────────

    private String readFileContent(File file) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        } finally {
            reader.close();
        }
        return sb.toString();
    }
}

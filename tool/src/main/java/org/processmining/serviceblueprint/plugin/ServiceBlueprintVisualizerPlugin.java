package org.processmining.serviceblueprint.plugin;

import org.processmining.contexts.uitopia.annotations.UITopiaVariant;
import org.processmining.contexts.uitopia.annotations.Visualizer;
import org.processmining.framework.plugin.PluginContext;
import org.processmining.framework.plugin.annotations.Plugin;
import org.processmining.framework.plugin.annotations.PluginVariant;
import org.processmining.serviceblueprint.model.BlueprintActivity;
import org.processmining.serviceblueprint.model.BlueprintConnection;
import org.processmining.serviceblueprint.model.BlueprintLevel;
import org.processmining.serviceblueprint.model.HandoffMetrics;
import org.processmining.serviceblueprint.model.LayerMetrics;
import org.processmining.serviceblueprint.model.ServiceBlueprint;
import org.processmining.serviceblueprint.visualization.BlueprintPanel;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

@Plugin(
        name = "Visualize Service Blueprint",
        parameterLabels = {"Service Blueprint"},
        returnLabels = {"Service Blueprint Visualization"},
        returnTypes = {JComponent.class},
        userAccessible = true,
        help = "Visualiza un Service Blueprint interactivo con cuatro niveles."
)
@Visualizer
public class ServiceBlueprintVisualizerPlugin {

    @UITopiaVariant(affiliation = "Service Blueprint",
                    author = "ProM Service Blueprint", email = "")
    @PluginVariant(variantLabel = "Visualize Service Blueprint",
                   requiredParameterLabels = {0})
    public JComponent visualize(PluginContext context, ServiceBlueprint blueprint) {
        JPanel mainPanel = new JPanel(new BorderLayout());

        BlueprintPanel blueprintPanel = new BlueprintPanel(blueprint);
        blueprintPanel.setHappyPathEdges(blueprint.getHappyPathEdges());

        JScrollPane scrollPane = new JScrollPane(blueprintPanel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        mainPanel.add(createToolbar(blueprint, blueprintPanel), BorderLayout.NORTH);
        mainPanel.add(scrollPane,                               BorderLayout.CENTER);
        mainPanel.add(createMetricsBar(blueprint),              BorderLayout.SOUTH);

        return mainPanel;
    }

    // =========================================================================
    // TOOLBAR
    // =========================================================================

    private JPanel createToolbar(final ServiceBlueprint blueprint,
                                 final BlueprintPanel blueprintPanel) {
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        toolbar.setBackground(new Color(245, 245, 245));

        // ── ROW 1: filtro ────────────────────────────────────────────────────
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row1.setOpaque(false);

        Set<String> allFields = new TreeSet<>();
        for (BlueprintActivity a : blueprint.getActivities().values())
            allFields.addAll(a.getAttributeFields());

        row1.add(new JLabel("Filter by:"));
        final JComboBox<String> fieldCombo = new JComboBox<>();
        fieldCombo.addItem("-- Select field --");
        for (String f : allFields) fieldCombo.addItem(f);
        fieldCombo.setPreferredSize(new Dimension(180, 24));
        row1.add(fieldCombo);
        row1.add(new JLabel("="));

        final JComboBox<String> valueCombo = new JComboBox<>();
        valueCombo.setEditable(true);
        valueCombo.setPreferredSize(new Dimension(210, 24));
        valueCombo.setEnabled(false);
        row1.add(valueCombo);

        final JButton filterButton = new JButton("Filter");
        filterButton.setEnabled(false);
        row1.add(filterButton);
        final JButton clearButton = new JButton("Clear");
        clearButton.setEnabled(false);
        row1.add(clearButton);
        final JLabel infoLabel = new JLabel("");
        infoLabel.setForeground(new Color(0, 100, 200));
        row1.add(infoLabel);

        fieldCombo.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                valueCombo.removeAllItems();
                String sel = (String) fieldCombo.getSelectedItem();
                if (sel == null || sel.startsWith("--")) {
                    valueCombo.setEnabled(false); filterButton.setEnabled(false); return;
                }
                valueCombo.setEnabled(true); filterButton.setEnabled(true);
                Set<String> vals = new TreeSet<>();
                for (BlueprintActivity a : blueprint.getActivities().values()) {
                    Set<String> v = a.getAttributeValues().get(sel);
                    if (v != null) vals.addAll(v);
                }
                for (String val : vals) valueCombo.addItem(val);
            }
        });

        ActionListener filterAction = new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                String field = (String) fieldCombo.getSelectedItem();
                Object vo = valueCombo.getSelectedItem();
                String value = vo != null ? vo.toString().trim() : "";
                if (field == null || field.startsWith("--") || value.isEmpty()) return;
                blueprintPanel.setFilter(field, value);
                clearButton.setEnabled(true);
                infoLabel.setText("Showing: " + field + " = \"" + value + "\"");
            }
        };
        filterButton.addActionListener(filterAction);
        valueCombo.getEditor().addActionListener(filterAction);

        clearButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                blueprintPanel.clearFilter();
                fieldCombo.setSelectedIndex(0);
                valueCombo.removeAllItems();
                valueCombo.setEnabled(false);
                filterButton.setEnabled(false);
                clearButton.setEnabled(false);
                infoLabel.setText("");
            }
        });

        // ── ROW 2: conexiones + informes + métricas ──────────────────────────
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row2.setOpaque(false);

        int maxFreq = 1;
        for (BlueprintConnection c : blueprint.getConnections())
            if (c.getFrequency() > maxFreq) maxFreq = c.getFrequency();
        final int MAX_FREQ = maxFreq;

        row2.add(new JLabel("Min. freq. conexions:"));
        int step = Math.max(1, MAX_FREQ / 50);
        final JSpinner freqSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_FREQ, step));
        freqSpinner.setPreferredSize(new Dimension(80, 24));
        row2.add(freqSpinner);

        JLabel maxLabel = new JLabel("(max: " + MAX_FREQ + ")");
        maxLabel.setFont(new Font("SansSerif", Font.PLAIN, 10));
        maxLabel.setForeground(new Color(120, 120, 120));
        row2.add(maxLabel);

        JButton applyFreqBtn = new JButton("Apply");
        applyFreqBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                blueprintPanel.setMinConnectionFreq((Integer) freqSpinner.getValue());
            }
        });
        row2.add(applyFreqBtn);

        JSeparator sep1 = new JSeparator(SwingConstants.VERTICAL);
        sep1.setPreferredSize(new Dimension(2, 20));
        row2.add(sep1);

        final JCheckBox selfLoopsCheck = new JCheckBox("Auto-bucles");
        selfLoopsCheck.setOpaque(false);
        selfLoopsCheck.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                blueprintPanel.setShowSelfLoops(selfLoopsCheck.isSelected());
            }
        });
        row2.add(selfLoopsCheck);

        final JCheckBox labelsCheck = new JCheckBox("Etiq. freq.");
        labelsCheck.setSelected(true);
        labelsCheck.setOpaque(false);
        labelsCheck.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                blueprintPanel.setShowConnectionLabels(labelsCheck.isSelected());
            }
        });
        row2.add(labelsCheck);

        JSeparator sep2 = new JSeparator(SwingConstants.VERTICAL);
        sep2.setPreferredSize(new Dimension(2, 20));
        row2.add(sep2);

        final int happyPathFreq = blueprint.getHappyPathFrequency();
        final JCheckBox happyPathCheck = new JCheckBox(
                "Happy path" + (happyPathFreq > 0 ? " (" + happyPathFreq + "x)" : ""));
        happyPathCheck.setOpaque(false);
        happyPathCheck.setForeground(new Color(150, 100, 0));
        happyPathCheck.setFont(new Font("SansSerif", Font.BOLD, 11));
        happyPathCheck.setToolTipText("Highlight the most common log entry");
        happyPathCheck.setEnabled(happyPathFreq > 0);
        happyPathCheck.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                blueprintPanel.setShowHappyPath(happyPathCheck.isSelected());
            }
        });
        row2.add(happyPathCheck);

        JSeparator sep2b = new JSeparator(SwingConstants.VERTICAL);
        sep2b.setPreferredSize(new Dimension(2, 20));
        row2.add(sep2b);

        JButton reportButton = new JButton("Keywords Report");
        reportButton.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                generateKeywordReport(blueprint, blueprintPanel);
            }
        });
        row2.add(reportButton);

        JSeparator sep3 = new JSeparator(SwingConstants.VERTICAL);
        sep3.setPreferredSize(new Dimension(2, 20));
        row2.add(sep3);

        JButton metricsBtn = new JButton("View detailed metrics");
        metricsBtn.setToolTipText("Table showing metrics by layer and handoffs between layers");
        metricsBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                showMetricsDialog(blueprint, blueprintPanel);
            }
        });
        row2.add(metricsBtn);

        JSeparator sep4 = new JSeparator(SwingConstants.VERTICAL);
        sep4.setPreferredSize(new Dimension(2, 20));
        row2.add(sep4);

        JButton exportPngBtn = new JButton("Export as PNG");
        exportPngBtn.setToolTipText("Save the current diagram as a PNG image");
        exportPngBtn.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                exportAsPng(blueprintPanel, blueprintPanel);
            }
        });
        row2.add(exportPngBtn);

        toolbar.add(row1);
        toolbar.add(row2);
        return toolbar;
    }

    // =========================================================================
    // EXPORTACION PNG
    // =========================================================================

    private void exportAsPng(BlueprintPanel panel, Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Diagram as PNG");
        chooser.setSelectedFile(new File("service_blueprint.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Images (*.png)", "png"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".png"))
            file = new File(file.getAbsolutePath() + ".png");

        try {
            BufferedImage img = panel.toImage();
            ImageIO.write(img, "PNG", file);
            JOptionPane.showMessageDialog(parent,
                    "Saved PNG:\n  " + file.getName()
                    + "\n  " + img.getWidth() + " x " + img.getHeight() + " px",
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Error exporting PNG:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // PANEL COMPACTO DE METRICAS (SOUTH)
    // =========================================================================

    private JPanel createMetricsBar(ServiceBlueprint blueprint) {
        Map<BlueprintLevel, LayerMetrics> lm = blueprint.getLayerMetrics();

        long   totalEvents = 0;
        double totalTime   = 0;
        for (LayerMetrics m : lm.values()) {
            totalEvents += m.getEventCount();
            totalTime   += m.getTotalTimeMs();
        }

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        bar.setBackground(new Color(250, 250, 250));

        JLabel title = new JLabel("Metrics per layer:  ");
        title.setFont(new Font("SansSerif", Font.BOLD, 11));
        bar.add(title, BorderLayout.WEST);

        JPanel cells = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        cells.setOpaque(false);

        for (BlueprintLevel level : BlueprintLevel.values()) {
            LayerMetrics m = lm.get(level);
            if (m == null || m.getEventCount() == 0) continue;

            double pctEvt  = totalEvents > 0 ? 100.0 * m.getEventCount()  / totalEvents : 0;
            double pctTime = totalTime   > 0 ? 100.0 * m.getTotalTimeMs() / totalTime   : 0;

            JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
            cell.setOpaque(false);

            JLabel dot = new JLabel("\u25A0");
            dot.setForeground(level.getColor().darker());
            dot.setFont(new Font("SansSerif", Font.BOLD, 13));
            cell.add(dot);

            String text = String.format(
                "<html><b>%s</b>&nbsp;%.0f%% ev.&nbsp;%.0f%% t.&nbsp;<i>%s</i></html>",
                level.getDisplayName(), pctEvt, pctTime, m.getTopResource());
            JLabel lbl = new JLabel(text);
            lbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
            cell.add(lbl);
            cells.add(cell);

            JLabel sep = new JLabel("|");
            sep.setForeground(Color.LIGHT_GRAY);
            cells.add(sep);
        }

        bar.add(cells, BorderLayout.CENTER);
        return bar;
    }

    // =========================================================================
    // DIALOGO DE METRICAS DETALLADAS
    // =========================================================================

    private void showMetricsDialog(ServiceBlueprint blueprint, Component parent) {
        Window ancestor = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = (ancestor instanceof Frame)
                ? new JDialog((Frame) ancestor, "Metrics: " + blueprint.getName(), false)
                : new JDialog((Frame) null,    "Metrics: " + blueprint.getName(), false);
        dialog.setLayout(new BorderLayout(8, 8));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Por capa",  buildLayerMetricsTab(blueprint));
        tabs.addTab("Handoffs",  buildHandoffsTab(blueprint));
        dialog.add(tabs, BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); }
        });
        JPanel btn = new JPanel(); btn.add(close);
        dialog.add(btn, BorderLayout.SOUTH);

        dialog.setSize(700, 420);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private JScrollPane buildLayerMetricsTab(ServiceBlueprint blueprint) {
        Map<BlueprintLevel, LayerMetrics> lm = blueprint.getLayerMetrics();
        long   totalEvents = 0;
        double totalTime   = 0;
        for (LayerMetrics m : lm.values()) { totalEvents += m.getEventCount(); totalTime += m.getTotalTimeMs(); }

        String[] cols = {"Capa", "N. Eventos", "% Eventos", "Tiempo total", "% Tiempo", "Recurso top"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        final BlueprintLevel[] levels = BlueprintLevel.values();
        for (BlueprintLevel level : levels) {
            LayerMetrics m = lm.get(level);
            if (m == null) continue;
            double pctEvt  = totalEvents > 0 ? 100.0 * m.getEventCount()  / totalEvents : 0;
            double pctTime = totalTime   > 0 ? 100.0 * m.getTotalTimeMs() / totalTime   : 0;
            model.addRow(new Object[]{
                level.getDisplayName(),
                m.getEventCount(),
                String.format("%.1f%%", pctEvt),
                formatDuration(m.getTotalTimeMs()),
                String.format("%.1f%%", pctTime),
                m.getTopResource()
            });
        }

        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(new Font("SansSerif", Font.PLAIN, 11));
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(130);

        // Colorear filas por nivel
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object val, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                if (!sel && row < levels.length) {
                    Color bg = levels[row].getColor();
                    c.setBackground(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 160));
                }
                return c;
            }
        });

        return new JScrollPane(table);
    }

    private JScrollPane buildHandoffsTab(ServiceBlueprint blueprint) {
        Map<String, HandoffMetrics> handoffs = blueprint.getHandoffs();

        String[] cols = {"Origen", "Destino", "Handoffs", "Tiempo medio", "Min.", "Max."};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        java.util.List<HandoffMetrics> sorted = new ArrayList<>(handoffs.values());
        Collections.sort(sorted, new Comparator<HandoffMetrics>() {
            @Override public int compare(HandoffMetrics a, HandoffMetrics b) {
                return Integer.compare(b.getCount(), a.getCount());
            }
        });

        for (HandoffMetrics hm : sorted) {
            model.addRow(new Object[]{
                hm.getFrom().getDisplayName(),
                hm.getTo().getDisplayName(),
                hm.getCount(),
                formatDuration(hm.getAvgTimeMs()),
                formatDuration(hm.getMinTimeMs()),
                formatDuration(hm.getMaxTimeMs())
            });
        }

        if (sorted.isEmpty())
            model.addRow(new Object[]{"Sin datos", "-", "-", "-", "-", "-"});

        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(new Font("SansSerif", Font.PLAIN, 11));
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(70);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);

        return new JScrollPane(table);
    }

    // =========================================================================
    // INFORME KEYWORDS
    // =========================================================================

    private void generateKeywordReport(ServiceBlueprint blueprint, Component parent) {
        Map<String, Map<String, Integer>> globalCounts = new LinkedHashMap<>();
        for (BlueprintActivity activity : blueprint.getActivities().values()) {
            for (Map.Entry<String, Map<String, Integer>> fe :
                    activity.getAttributeValueCounts().entrySet()) {
                String field = fe.getKey();
                Map<String, Integer> fc = globalCounts.get(field);
                if (fc == null) { fc = new LinkedHashMap<>(); globalCounts.put(field, fc); }
                for (Map.Entry<String, Integer> ve : fe.getValue().entrySet()) {
                    Integer cur = fc.get(ve.getKey());
                    fc.put(ve.getKey(), (cur != null ? cur : 0) + ve.getValue());
                }
            }
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Guardar informe de keywords (.csv)");
        chooser.setSelectedFile(new File("keyword_report.csv"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv"))
            file = new File(file.getAbsolutePath() + ".csv");

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            writer.println("Campo,Keyword,Apariciones");
            java.util.List<String> sortedFields = new ArrayList<>(globalCounts.keySet());
            Collections.sort(sortedFields);
            int totalRows = 0;
            for (String field : sortedFields) {
                java.util.List<Map.Entry<String, Integer>> sorted =
                        new ArrayList<>(globalCounts.get(field).entrySet());
                Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
                    @Override public int compare(Map.Entry<String, Integer> a,
                                                 Map.Entry<String, Integer> b) {
                        return Integer.compare(b.getValue(), a.getValue());
                    }
                });
                for (Map.Entry<String, Integer> ve : sorted) {
                    writer.println(escapeCsv(field) + "," + escapeCsv(ve.getKey())
                            + "," + ve.getValue());
                    totalRows++;
                }
            }
            writer.flush();
            JOptionPane.showMessageDialog(parent,
                    "Informe generado:\n  " + file.getName()
                    + "\n  Campos: " + sortedFields.size() + "\n  Filas: " + totalRows,
                    "Informe generado", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Error:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (writer != null) writer.close();
        }
    }

    private String formatDuration(double millis) {
        if (millis <= 0)       return "N/A";
        if (millis < 1000)     return String.format("%.0f ms", millis);
        if (millis < 60000)    return String.format("%.1f s", millis / 1000);
        if (millis < 3600000)  return String.format("%.1f min", millis / 60000);
        if (millis < 86400000) return String.format("%.1f h", millis / 3600000);
        return String.format("%.1f dias", millis / 86400000);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }
}

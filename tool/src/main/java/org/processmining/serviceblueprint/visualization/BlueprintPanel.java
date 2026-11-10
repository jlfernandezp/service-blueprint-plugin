package org.processmining.serviceblueprint.visualization;

import org.processmining.serviceblueprint.model.*;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

/**
 * Panel gráfico interactivo del Service Blueprint.
 *
 * Funcionalidades:
 *  - Layout multi-fila por carril (evita overflow horizontal)
 *  - Filtrado de conexiones por frecuencia mínima
 *  - Ocultación de auto-bucles
 *  - Toggle de etiquetas de frecuencia
 *  - Enrutado de arcos mejorado
 *  - Baja confianza: nodo pálido con borde naranja discontinuo
 *  - Drag & drop entre carriles para reclasificar actividades manualmente
 *  - Happy path: resaltado de la traza más frecuente
 */
public class BlueprintPanel extends JPanel {

    // --- Layout constants ---
    private static final int NODE_WIDTH    = 130;
    private static final int NODE_HEIGHT   = 50;
    private static final int NODE_GAP_H    = 40;
    private static final int NODE_GAP_V    = 28;
    private static final int LANE_PAD_V    = 24;
    private static final int MIN_LANE_H    = 88;
    private static final int NODES_PER_ROW = 7;
    private static final int PADDING       = 30;
    private static final int LANE_LABEL_W  = 110;
    private static final int HEADER_H      = 40;

    private static final String[] SEPARATOR_LABELS = {
        "Line of interaction",
        "Line of visibility",
        "Line of internal interaction"
    };

    /** Por debajo de este umbral el nodo se dibuja como "baja confianza". */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.2;

    /** Píxeles mínimos de desplazamiento para considerar un drag (no un click). */
    private static final int DRAG_THRESHOLD = 8;

    private final ServiceBlueprint blueprint;

    /** Hit-test map rebuilt on every paint cycle. */
    private final Map<BlueprintActivity, Rectangle2D> activityBounds = new LinkedHashMap<>();

    // --- Filter state ---
    private String filterField = null;
    private String filterValue = null;

    // --- Connection display knobs ---
    private int     minConnectionFreq    = 1;
    private boolean showSelfLoops        = false;
    private boolean showConnectionLabels = true;

    // --- Happy path ---
    private Set<String> happyPathEdges = Collections.emptySet();
    private boolean showHappyPath = false;

    // --- Drag & drop state ---
    private BlueprintActivity draggedActivity = null;
    private int dragStartX, dragStartY;
    private int dragCurrentX, dragCurrentY;
    private boolean isDragging = false;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public BlueprintPanel(ServiceBlueprint blueprint) {
        this.blueprint = blueprint;
        setBackground(Color.WHITE);
        setToolTipText("");
        recalculateSize();

        MouseInputAdapter mouseHandler = new MouseInputAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStartX = e.getX();
                dragStartY = e.getY();
                draggedActivity = null;
                isDragging = false;

                for (Map.Entry<BlueprintActivity, Rectangle2D> entry : activityBounds.entrySet()) {
                    if (entry.getValue().contains(e.getPoint())) {
                        draggedActivity = entry.getKey();
                        break;
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedActivity == null) return;
                dragCurrentX = e.getX();
                dragCurrentY = e.getY();

                int dx = dragCurrentX - dragStartX;
                int dy = dragCurrentY - dragStartY;
                if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                    isDragging = true;
                }
                if (isDragging) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggedActivity != null && isDragging) {
                    // Determinar carril destino
                    BlueprintLevel targetLevel = getLaneAtY(e.getY());
                    if (targetLevel != null && targetLevel != draggedActivity.getLevel()) {
                        draggedActivity.setLevel(targetLevel);
                        blueprint.organizeByLevel();
                        recalculateSize();
                        revalidate();
                    }
                } else if (draggedActivity != null) {
                    // No hubo drag real → tratar como click (detalle de actividad)
                    showActivityDetail(draggedActivity);
                }

                draggedActivity = null;
                isDragging = false;
                setCursor(Cursor.getDefaultCursor());
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                for (Map.Entry<BlueprintActivity, Rectangle2D> entry : activityBounds.entrySet()) {
                    if (entry.getValue().contains(e.getPoint())) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        return;
                    }
                }
                setCursor(Cursor.getDefaultCursor());
            }
        };

        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public ServiceBlueprint getBlueprint() { return blueprint; }

    /**
     * Renderiza el diagrama completo (tamaño preferido, sin scroll) a una imagen.
     * Usada para la exportación PNG.
     */
    public BufferedImage toImage() {
        Dimension size = getPreferredSize();
        BufferedImage img = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, size.width, size.height);
        Dimension savedSize = getSize();
        setSize(size);
        paintComponent(g2);
        setSize(savedSize);
        g2.dispose();
        return img;
    }

    public void setFilter(String field, String value) {
        this.filterField = field;
        this.filterValue = value;
        recalculateSize();
        revalidate();
        repaint();
    }

    public void clearFilter() {
        this.filterField = null;
        this.filterValue = null;
        recalculateSize();
        revalidate();
        repaint();
    }

    public void setMinConnectionFreq(int freq) {
        this.minConnectionFreq = Math.max(1, freq);
        repaint();
    }

    public void setShowSelfLoops(boolean show) {
        this.showSelfLoops = show;
        repaint();
    }

    public void setShowConnectionLabels(boolean show) {
        this.showConnectionLabels = show;
        repaint();
    }

    /** Establece las aristas del happy path (formato "origen::destino"). */
    public void setHappyPathEdges(Set<String> edges) {
        this.happyPathEdges = edges != null ? edges : Collections.<String>emptySet();
    }

    /** Activa/desactiva la visualización del happy path. */
    public void setShowHappyPath(boolean show) {
        this.showHappyPath = show;
        repaint();
    }

    // -----------------------------------------------------------------------
    // Tooltip
    // -----------------------------------------------------------------------

    @Override
    public String getToolTipText(MouseEvent e) {
        for (Map.Entry<BlueprintActivity, Rectangle2D> entry : activityBounds.entrySet()) {
            if (entry.getValue().contains(e.getPoint())) {
                BlueprintActivity a = entry.getKey();
                String confStr = "";
                if (a.getConfidence() >= 0) {
                    int pct = (int) Math.round(a.getConfidence() * 100);
                    String warn = a.getConfidence() < LOW_CONFIDENCE_THRESHOLD
                            ? " <font color='orange'>&#9888; low confidence</font>" : "";
                    confStr = "Confidence: " + pct + "%" + warn + "<br>";
                }
                return "<html><b>" + a.getName() + "</b><br>"
                        + "Level: " + a.getLevel().getDisplayName() + "<br>"
                        + "Freq: " + a.getFrequency() + "<br>"
                        + confStr
                        + "<i>Click to inspect · Drag to move to another layer</i></html>";
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Layout helpers
    // -----------------------------------------------------------------------

    private boolean isActivityVisible(BlueprintActivity a) {
        if (filterField == null || filterValue == null || filterValue.isEmpty()) return true;
        return a.matchesFilter(filterField, filterValue);
    }

    private List<BlueprintActivity> getVisibleActivities(BlueprintLevel level) {
        List<BlueprintActivity> result = new ArrayList<>();
        for (BlueprintActivity a : blueprint.getActivitiesByLevel(level)) {
            if (isActivityVisible(a)) result.add(a);
        }
        return result;
    }

    private int getLaneHeight(BlueprintLevel level) {
        int n = getVisibleActivities(level).size();
        if (n == 0) return MIN_LANE_H;
        int rows = (int) Math.ceil((double) n / NODES_PER_ROW);
        return Math.max(MIN_LANE_H,
                LANE_PAD_V * 2 + rows * NODE_HEIGHT + (rows - 1) * NODE_GAP_V);
    }

    private int getLaneY(BlueprintLevel target) {
        int y = HEADER_H + PADDING;
        for (BlueprintLevel l : BlueprintLevel.values()) {
            if (l == target) break;
            y += getLaneHeight(l);
        }
        return y;
    }

    /**
     * Devuelve el nivel (carril) al que corresponde la coordenada Y,
     * o null si está fuera de los carriles.
     */
    private BlueprintLevel getLaneAtY(int y) {
        for (BlueprintLevel level : BlueprintLevel.values()) {
            int laneY = getLaneY(level);
            int laneH = getLaneHeight(level);
            if (y >= laneY && y < laneY + laneH) return level;
        }
        return null;
    }

    private void recalculateSize() {
        int totalH = HEADER_H + 2 * PADDING + 60;
        for (BlueprintLevel l : BlueprintLevel.values()) totalH += getLaneHeight(l);

        int maxCols = 0;
        for (BlueprintLevel l : BlueprintLevel.values()) {
            int n = getVisibleActivities(l).size();
            maxCols = Math.max(maxCols, Math.min(n, NODES_PER_ROW));
        }
        int width = Math.max(900,
                LANE_LABEL_W + maxCols * (NODE_WIDTH + NODE_GAP_H) + 2 * PADDING);
        setPreferredSize(new Dimension(width, totalH));
    }

    // -----------------------------------------------------------------------
    // Painting
    // -----------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        activityBounds.clear();

        drawTitle(g2);
        drawLanes(g2);

        // Si hay drag activo, resaltar el carril destino
        if (isDragging) {
            drawDragTargetHighlight(g2);
        }

        drawActivities(g2);
        drawConnections(g2);
        drawSeparatorLines(g2);
        drawLegend(g2);

        if (filterField != null && filterValue != null && !filterValue.isEmpty()) {
            drawFilterIndicator(g2);
        }

        // Ghost del nodo arrastrado
        if (isDragging && draggedActivity != null) {
            drawDragGhost(g2);
        }

        g2.dispose();
    }

    private void drawTitle(Graphics2D g2) {
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.BOLD, 16));
        g2.drawString("Service Blueprint: " + blueprint.getName(), PADDING, PADDING);
    }

    private void drawLanes(Graphics2D g2) {
        for (BlueprintLevel level : BlueprintLevel.values()) {
            int y = getLaneY(level);
            int h = getLaneHeight(level);

            g2.setColor(level.getColor());
            g2.fillRect(0, y, getWidth(), h);

            g2.setColor(Color.GRAY);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(0, y, getWidth(), h);

            g2.setColor(new Color(50, 50, 50));
            g2.setFont(new Font("SansSerif", Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(level.getDisplayName(), 8, y + h / 2 - 4);

            int total   = blueprint.getActivitiesByLevel(level).size();
            int visible = getVisibleActivities(level).size();
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g2.setColor(new Color(90, 90, 90));
            String cnt = visible == total
                    ? "(" + total + " act.)"
                    : "(" + visible + "/" + total + ")";
            g2.drawString(cnt, 8, y + h / 2 + fm.getAscent() / 2 + 4);
        }
    }

    /** Resalta el carril sobre el que está pasando el nodo arrastrado. */
    private void drawDragTargetHighlight(Graphics2D g2) {
        BlueprintLevel target = getLaneAtY(dragCurrentY);
        if (target == null || target == draggedActivity.getLevel()) return;

        int y = getLaneY(target);
        int h = getLaneHeight(target);
        g2.setColor(new Color(100, 180, 255, 60));
        g2.fillRect(0, y, getWidth(), h);
        g2.setColor(new Color(50, 120, 220, 180));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(0, y, getWidth() - 1, h - 1);
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawActivities(Graphics2D g2) {
        int startX = LANE_LABEL_W + PADDING;

        for (BlueprintLevel level : BlueprintLevel.values()) {
            List<BlueprintActivity> activities = getVisibleActivities(level);
            int laneY = getLaneY(level);

            for (int j = 0; j < activities.size(); j++) {
                BlueprintActivity activity = activities.get(j);
                int col = j % NODES_PER_ROW;
                int row = j / NODES_PER_ROW;
                int x = startX + col * (NODE_WIDTH + NODE_GAP_H);
                int y = laneY + LANE_PAD_V + row * (NODE_HEIGHT + NODE_GAP_V);

                activityBounds.put(activity, new Rectangle2D.Double(x, y, NODE_WIDTH, NODE_HEIGHT));

                // Si está siendo arrastrado, dibujarlo semitransparente en su posición original
                boolean beingDragged = isDragging && activity == draggedActivity;

                boolean lowConfidence = activity.getConfidence() >= 0
                        && activity.getConfidence() < LOW_CONFIDENCE_THRESHOLD;

                // Shadow
                int shadowAlpha = beingDragged ? 6 : (lowConfidence ? 12 : 22);
                g2.setColor(new Color(0, 0, 0, shadowAlpha));
                g2.fill(new RoundRectangle2D.Double(x + 2, y + 2, NODE_WIDTH, NODE_HEIGHT, 10, 10));

                // Node background
                Color baseColor = level.getColor().darker();
                int alpha = beingDragged ? 60 : (lowConfidence ? 100 : 255);
                g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(),
                        baseColor.getBlue(), alpha));
                g2.fill(new RoundRectangle2D.Double(x, y, NODE_WIDTH, NODE_HEIGHT, 10, 10));

                // Node border
                if (lowConfidence) {
                    g2.setColor(new Color(200, 100, 0, beingDragged ? 80 : 255));
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND, 0, new float[]{4, 3}, 0));
                } else {
                    g2.setColor(new Color(64, 64, 64, beingDragged ? 80 : 255));
                    g2.setStroke(new BasicStroke(1.4f));
                }
                g2.draw(new RoundRectangle2D.Double(x, y, NODE_WIDTH, NODE_HEIGHT, 10, 10));
                g2.setStroke(new BasicStroke(1f));

                if (!beingDragged) {
                    g2.setColor(Color.BLACK);
                    drawNodeName(g2, activity.getName(), x, y);

                    g2.setFont(new Font("SansSerif", Font.BOLD, 9));
                    g2.setColor(new Color(55, 55, 55));
                    String freqTxt = "freq: " + activity.getFrequency();
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(freqTxt, x + (NODE_WIDTH - fm.stringWidth(freqTxt)) / 2, y + 43);
                }
            }
        }
    }

    /** Dibuja el ghost semitransparente del nodo mientras se arrastra. */
    private void drawDragGhost(Graphics2D g2) {
        int gx = dragCurrentX - NODE_WIDTH / 2;
        int gy = dragCurrentY - NODE_HEIGHT / 2;

        // Determinar color del carril destino (si hay)
        BlueprintLevel target = getLaneAtY(dragCurrentY);
        Color baseColor = (target != null ? target : draggedActivity.getLevel())
                .getColor().darker();

        g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(),
                baseColor.getBlue(), 180));
        g2.fill(new RoundRectangle2D.Double(gx, gy, NODE_WIDTH, NODE_HEIGHT, 10, 10));

        g2.setColor(new Color(50, 50, 200, 200));
        g2.setStroke(new BasicStroke(2f));
        g2.draw(new RoundRectangle2D.Double(gx, gy, NODE_WIDTH, NODE_HEIGHT, 10, 10));
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(Color.WHITE);
        drawNodeName(g2, draggedActivity.getName(), gx, gy);
    }

    private void drawNodeName(Graphics2D g2, String name, int x, int y) {
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        FontMetrics fm = g2.getFontMetrics();
        int maxW = NODE_WIDTH - 8;

        if (fm.stringWidth(name) <= maxW) {
            g2.drawString(name, x + (NODE_WIDTH - fm.stringWidth(name)) / 2, y + 20);
            return;
        }

        int mid = name.length() / 2;
        int split = name.lastIndexOf(' ', mid);
        if (split < 1) split = name.indexOf(' ', mid);

        if (split > 0) {
            String line1 = name.substring(0, split);
            String line2 = name.substring(split + 1);
            if (fm.stringWidth(line2) > maxW) line2 = truncate(line2, 17);
            g2.drawString(line1, x + (NODE_WIDTH - fm.stringWidth(line1)) / 2, y + 16);
            g2.drawString(line2, x + (NODE_WIDTH - fm.stringWidth(line2)) / 2, y + 27);
        } else {
            String s = truncate(name, 17);
            g2.drawString(s, x + (NODE_WIDTH - fm.stringWidth(s)) / 2, y + 20);
        }
    }

    private void drawConnections(Graphics2D g2) {
        // Primer paso: aristas normales
        for (BlueprintConnection conn : blueprint.getConnections()) {
            if (!isActivityVisible(conn.getSource()) || !isActivityVisible(conn.getTarget())) continue;
            if (conn.getFrequency() < minConnectionFreq) continue;
            if (!showSelfLoops && conn.getSource().equals(conn.getTarget())) continue;

            // Si happy path activo, las aristas del happy path se dibujan en un segundo paso
            String edgeKey = conn.getSource().getName() + "::" + conn.getTarget().getName();
            if (showHappyPath && happyPathEdges.contains(edgeKey)) continue;

            Rectangle2D src = activityBounds.get(conn.getSource());
            Rectangle2D tgt = activityBounds.get(conn.getTarget());
            if (src == null || tgt == null) continue;

            if (conn.getSource().equals(conn.getTarget())) {
                drawSelfLoop(g2, src, conn.getFrequency());
                continue;
            }

            drawConnectionCurve(g2, conn, src, tgt, false);
        }

        // Segundo paso: aristas del happy path encima
        if (showHappyPath && !happyPathEdges.isEmpty()) {
            for (BlueprintConnection conn : blueprint.getConnections()) {
                if (!isActivityVisible(conn.getSource()) || !isActivityVisible(conn.getTarget())) continue;
                String edgeKey = conn.getSource().getName() + "::" + conn.getTarget().getName();
                if (!happyPathEdges.contains(edgeKey)) continue;

                Rectangle2D src = activityBounds.get(conn.getSource());
                Rectangle2D tgt = activityBounds.get(conn.getTarget());
                if (src == null || tgt == null) continue;

                drawConnectionCurve(g2, conn, src, tgt, true);
            }
        }

        g2.setStroke(new BasicStroke(1f));
    }

    private void drawConnectionCurve(Graphics2D g2, BlueprintConnection conn,
                                     Rectangle2D src, Rectangle2D tgt,
                                     boolean isHappyPath) {
        boolean srcAbove = src.getCenterY() < tgt.getCenterY() - 5;
        boolean srcBelow = src.getCenterY() > tgt.getCenterY() + 5;
        boolean sameRow  = !srcAbove && !srcBelow;
        boolean srcLeft  = src.getCenterX() < tgt.getCenterX();

        double x1, y1, x2, y2, ctrlX, ctrlY;

        if (sameRow) {
            if (srcLeft) {
                x1 = src.getMaxX();    y1 = src.getCenterY();
                x2 = tgt.getMinX();    y2 = tgt.getCenterY();
                ctrlX = (x1 + x2) / 2;
                ctrlY = Math.min(y1, y2) - 30;
            } else {
                x1 = src.getCenterX(); y1 = src.getMaxY();
                x2 = tgt.getCenterX(); y2 = tgt.getMaxY();
                ctrlX = (x1 + x2) / 2;
                ctrlY = Math.max(y1, y2) + 35;
            }
        } else {
            if (srcAbove) {
                x1 = src.getCenterX(); y1 = src.getMaxY();
                x2 = tgt.getCenterX(); y2 = tgt.getMinY();
            } else {
                x1 = src.getCenterX(); y1 = src.getMinY();
                x2 = tgt.getCenterX(); y2 = tgt.getMaxY();
            }
            double dx = x2 - x1;
            ctrlX = (x1 + x2) / 2 + (Math.abs(dx) < 15 ? 45 : dx * 0.35);
            ctrlY = (y1 + y2) / 2;
        }

        if (isHappyPath) {
            // Happy path: doble trazo — primero halo dorado grueso, luego línea amarillo intenso
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(255, 200, 0, 100));
            g2.draw(new QuadCurve2D.Double(x1, y1, ctrlX, ctrlY, x2, y2));

            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(220, 160, 0, 230));
            g2.draw(new QuadCurve2D.Double(x1, y1, ctrlX, ctrlY, x2, y2));
            drawArrowHead(g2, ctrlX, ctrlY, x2, y2);

            if (showConnectionLabels) {
                g2.setFont(new Font("SansSerif", Font.BOLD, 8));
                g2.setColor(new Color(160, 100, 0));
                String freqStr = String.valueOf(conn.getFrequency());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(freqStr,
                        (int) ctrlX - fm.stringWidth(freqStr) / 2,
                        (int) ctrlY - 3);
            }
        } else {
            if (conn.isCrossLevel()) {
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        0, new float[]{5, 4}, 0));
                g2.setColor(new Color(210, 55, 55, 155));
            } else {
                g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(65, 65, 65, 140));
            }
            g2.draw(new QuadCurve2D.Double(x1, y1, ctrlX, ctrlY, x2, y2));
            drawArrowHead(g2, ctrlX, ctrlY, x2, y2);

            if (showConnectionLabels) {
                g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
                g2.setColor(new Color(70, 70, 70, 200));
                String freqStr = String.valueOf(conn.getFrequency());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(freqStr,
                        (int) ctrlX - fm.stringWidth(freqStr) / 2,
                        (int) ctrlY - 3);
            }
        }
    }

    private void drawSelfLoop(Graphics2D g2, Rectangle2D bounds, int freq) {
        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(new Color(140, 140, 140, 160));
        double cx = bounds.getCenterX();
        double ty = bounds.getMinY();
        Arc2D arc = new Arc2D.Double(cx - 18, ty - 26, 36, 26, 0, 270, Arc2D.OPEN);
        g2.draw(arc);
        if (showConnectionLabels) {
            g2.setFont(new Font("SansSerif", Font.PLAIN, 7));
            g2.setColor(new Color(100, 100, 100));
            g2.drawString(String.valueOf(freq), (int) cx + 18, (int) ty - 8);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawArrowHead(Graphics2D g2, double fromX, double fromY, double toX, double toY) {
        double angle = Math.atan2(toY - fromY, toX - fromX);
        int size = 7;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(toX, toY);
        arrow.lineTo(toX - size * Math.cos(angle - Math.PI / 6),
                     toY - size * Math.sin(angle - Math.PI / 6));
        arrow.lineTo(toX - size * Math.cos(angle + Math.PI / 6),
                     toY - size * Math.sin(angle + Math.PI / 6));
        arrow.closePath();
        g2.fill(arrow);
    }

    private void drawSeparatorLines(Graphics2D g2) {
        BlueprintLevel[] levels = BlueprintLevel.values();
        g2.setFont(new Font("SansSerif", Font.ITALIC, 10));
        for (int i = 0; i < levels.length - 1 && i < SEPARATOR_LABELS.length; i++) {
            int y = getLaneY(levels[i + 1]);
            g2.setColor(new Color(160, 0, 0, 90));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{10, 5}, 0));
            g2.drawLine(0, y, getWidth(), y);
            g2.setColor(new Color(160, 0, 0, 150));
            g2.setStroke(new BasicStroke(1f));
            g2.drawString(SEPARATOR_LABELS[i], getWidth() - 210, y - 4);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawLegend(Graphics2D g2) {
        BlueprintLevel last = BlueprintLevel.values()[BlueprintLevel.values().length - 1];
        int legendY = getLaneY(last) + getLaneHeight(last) + 14;
        int lx = PADDING;

        g2.setFont(new Font("SansSerif", Font.BOLD, 10));
        g2.setColor(Color.DARK_GRAY);
        g2.drawString("Legend:", lx, legendY);

        // Mismo nivel
        g2.setColor(new Color(65, 65, 65));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawLine(lx + 60, legendY - 4, lx + 90, legendY - 4);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g2.drawString("Same level", lx + 95, legendY);

        // Entre niveles
        g2.setColor(new Color(210, 55, 55));
        g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{5, 4}, 0));
        g2.drawLine(lx + 195, legendY - 4, lx + 225, legendY - 4);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.DARK_GRAY);
        g2.drawString("Cross-level", lx + 230, legendY);

        // Baja confianza
        g2.setColor(new Color(200, 100, 0));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{4, 3}, 0));
        g2.drawRoundRect(lx + 340, legendY - 10, 16, 10, 4, 4);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g2.drawString("Low confidence (<20%)", lx + 360, legendY);

        // Happy path
        if (showHappyPath) {
            g2.setColor(new Color(220, 160, 0));
            g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(lx + 510, legendY - 4, lx + 540, legendY - 4);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(Color.DARK_GRAY);
            g2.drawString("Happy path", lx + 545, legendY);
        }

        // Filtro conexiones activo
        if (minConnectionFreq > 1) {
            g2.setColor(new Color(0, 120, 0));
            g2.setFont(new Font("SansSerif", Font.BOLD, 9));
            g2.drawString("Connection filter active: freq \\u2265 " + minConnectionFreq,
                    lx + (showHappyPath ? 650 : 530), legendY);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawFilterIndicator(Graphics2D g2) {
        int vis = 0;
        for (BlueprintLevel l : BlueprintLevel.values()) vis += getVisibleActivities(l).size();
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.setColor(new Color(0, 100, 200));
        g2.drawString("Filter: " + filterField + " = \"" + filterValue
                + "\"  (" + vis + "/" + blueprint.getTotalActivities() + " activities)",
                PADDING, HEADER_H + PADDING - 10);
    }

    // -----------------------------------------------------------------------
    // Activity detail dialog
    // -----------------------------------------------------------------------

    private void showActivityDetail(BlueprintActivity activity) {
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = (ancestor instanceof Frame)
                ? new JDialog((Frame) ancestor, "Detalle: " + activity.getName(), true)
                : new JDialog((Frame) null, "Detalle: " + activity.getName(), true);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel header = new JPanel(new GridLayout(0, 2, 8, 4));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Summary"),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        header.add(new JLabel("Activity:"));  header.add(new JLabel(activity.getName()));
        header.add(new JLabel("Level:"));
        JLabel lvl = new JLabel("  " + activity.getLevel().getDisplayName() + "  ");
        lvl.setOpaque(true); lvl.setBackground(activity.getLevel().getColor());
        header.add(lvl);
        header.add(new JLabel("Frecuency:"));  header.add(new JLabel(String.valueOf(activity.getFrequency())));
        header.add(new JLabel("Av. Duration:"));  header.add(new JLabel(formatDuration(activity.getAvgDuration())));
        header.add(new JLabel("Total Duration:"));  header.add(new JLabel(formatDuration(activity.getTotalDuration())));
        if (activity.getResource() != null && !activity.getResource().isEmpty()) {
            header.add(new JLabel("Resource:"));  header.add(new JLabel(activity.getResource()));
        }
        dialog.add(header, BorderLayout.NORTH);

        String[] cols = {"Campo", "Valor", "Apariciones"};
        DefaultTableModel tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (Map.Entry<String, Map<String, Integer>> fe : activity.getAttributeValueCounts().entrySet()) {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fe.getValue().entrySet());
            Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
                @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return Integer.compare(b.getValue(), a.getValue());
                }
            });
            for (Map.Entry<String, Integer> ve : sorted) {
                tableModel.addRow(new Object[]{fe.getKey(), ve.getKey(), ve.getValue()});
            }
        }
        JTable table = new JTable(tableModel);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createTitledBorder("Atributos observados en eventos"));
        sp.setPreferredSize(new Dimension(550, 260));
        dialog.add(sp, BorderLayout.CENTER);

        JButton close = new JButton("Cerrar");
        close.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) { dialog.dispose(); }
        });
        JPanel btnPanel = new JPanel(); btnPanel.add(close);
        dialog.add(btnPanel, BorderLayout.SOUTH);

        dialog.setSize(620, 520);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }

    private String formatDuration(double millis) {
        if (millis <= 0)        return "N/A";
        if (millis < 1000)      return String.format("%.0f ms", millis);
        if (millis < 60000)     return String.format("%.1f s", millis / 1000);
        if (millis < 3600000)   return String.format("%.1f min", millis / 60000);
        if (millis < 86400000)  return String.format("%.1f h", millis / 3600000);
        return String.format("%.1f dias", millis / 86400000);
    }
}

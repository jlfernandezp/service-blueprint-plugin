package org.processmining.serviceblueprint.model;

/**
 * Métricas de un handoff entre dos niveles del Service Blueprint.
 *
 * Un handoff se produce cuando en una traza el evento i está en el nivel A
 * y el evento i+1 está en el nivel B (A ≠ B).
 * La duración del handoff = timestamp(i+1) - timestamp(i).
 */
public class HandoffMetrics {

    private final BlueprintLevel from;
    private final BlueprintLevel to;
    private int count = 0;
    private double totalTimeMs = 0.0;
    private double minTimeMs = Double.MAX_VALUE;
    private double maxTimeMs = 0.0;

    public HandoffMetrics(BlueprintLevel from, BlueprintLevel to) {
        this.from = from;
        this.to   = to;
    }

    public HandoffMetrics(BlueprintLevel from, BlueprintLevel to,
                          int count, double totalTimeMs, double minTimeMs, double maxTimeMs) {
        this.from       = from;
        this.to         = to;
        this.count      = count;
        this.totalTimeMs = totalTimeMs;
        this.minTimeMs  = minTimeMs > 0 ? minTimeMs : Double.MAX_VALUE;
        this.maxTimeMs  = maxTimeMs;
    }

    public void addOccurrence(double durationMs) {
        count++;
        totalTimeMs += durationMs;
        if (durationMs < minTimeMs) minTimeMs = durationMs;
        if (durationMs > maxTimeMs) maxTimeMs = durationMs;
    }

    public BlueprintLevel getFrom()  { return from; }
    public BlueprintLevel getTo()    { return to; }
    public int            getCount() { return count; }

    public double getTotalTimeMs() { return totalTimeMs; }
    public double getAvgTimeMs()   { return count > 0 ? totalTimeMs / count : 0.0; }
    public double getMinTimeMs()   { return count > 0 && minTimeMs < Double.MAX_VALUE ? minTimeMs : 0.0; }
    public double getMaxTimeMs()   { return maxTimeMs; }

    /** Clave única para el mapa de handoffs en ServiceBlueprint. */
    public static String key(BlueprintLevel from, BlueprintLevel to) {
        return from.name() + "->" + to.name();
    }
}

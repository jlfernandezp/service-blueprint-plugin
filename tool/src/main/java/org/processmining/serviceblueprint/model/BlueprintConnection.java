package org.processmining.serviceblueprint.model;

/**
 * Conexión (directly-follows) entre dos actividades del blueprint.
 */
public class BlueprintConnection {

    private final BlueprintActivity source;
    private final BlueprintActivity target;
    private int frequency;

    public BlueprintConnection(BlueprintActivity source, BlueprintActivity target) {
        this.source = source;
        this.target = target;
        this.frequency = 0;
    }

    public BlueprintActivity getSource() {
        return source;
    }

    public BlueprintActivity getTarget() {
        return target;
    }

    public int getFrequency() {
        return frequency;
    }

    public void incrementFrequency() {
        this.frequency++;
    }

    public void setFrequency(int frequency) { this.frequency = frequency; }

    public boolean isCrossLevel() {
        return source.getLevel() != target.getLevel();
    }

    @Override
    public String toString() {
        return source.getName() + " -> " + target.getName() + " (freq=" + frequency + ")";
    }
}

package org.mmtk.plan.concurrent.copying;

public enum SpaceState {
    NOT_USED, TO_SPACE, FROM_SPACE;

    public boolean inUse() {
        return this == FROM_SPACE || this == TO_SPACE;
    }

    SpaceState inverseOf() {
        switch (this) {
        case FROM_SPACE:
            return TO_SPACE;
        case TO_SPACE:
            return FROM_SPACE;
        default:
            return NOT_USED;
        }
    }
}
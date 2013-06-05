package org.mmtk.plan.concurrent.copying;

import org.mmtk.harness.lang.Trace.Item;
import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;

import static org.mmtk.harness.lang.Trace.trace;

/**
 * Represents the per-thread objects trace.
 *
 * @author Ovidiu Maja
 * @version 03.06.2013
 *
 */
@Uninterruptible
public class CMCTraceLocal extends TraceLocal {

    /**
     * Collector's copy space.
     */
    private final CopyLocal toSpace;

    public CMCTraceLocal(Trace trace, CopyLocal toSpace) {
        super(trace);
        this.toSpace = toSpace;
    }

    @Override
    public boolean isLive(ObjectReference object) {
        if (object.isNull()) {
            return false;
        }
        for (CopySpace space : CMC.usedFlagBySpace.keySet()) {
            if (Space.isInSpace(space.getDescriptor(), object)) {
                return space.isLive(object);
            }
        }
        return super.isLive(object);
    }

    @Override
    @Inline
    public ObjectReference traceObject(ObjectReference object) {
        if (object.isNull()) {
            return object;
        }
        for (CopySpace space : CMC.usedFlagBySpace.keySet()) {
            trace(Item.DEBUG, space.toString() + ": " + Space.isInSpace(space.getDescriptor(), object) +  "  " + object.toAddress());
            if (Space.isInSpace(space.getDescriptor(), object)) {
                return space.traceObject(this, object, CMC.CMC_ALLOC);
            }
        }
        trace(Item.DEBUG, "Last space: " + toSpace.getSpace().toString());
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        return Space.isInSpace(toSpace.getSpace().getDescriptor(), object);
    }
}
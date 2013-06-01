package org.mmtk.plan.concurrent.copy;

import java.util.Map.Entry;

import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.vmmagic.pragma.Inline;
import org.vmmagic.unboxed.ObjectReference;

public class CMCTraceLocal extends TraceLocal {

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
        for (Entry<CopySpace, Integer> mcEntry : CMC.idBySpace.entrySet()) {
            if (Space.isInSpace(mcEntry.getValue(), object)) {
                return mcEntry.getKey().isLive(object);
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
        for (Entry<CopySpace, Integer> mcEntry : CMC.idBySpace.entrySet()) {
            if (Space.isInSpace(mcEntry.getValue(), object)) {
                return mcEntry.getKey().traceObject(this, object, CMC.CMC_ALLOC);
            }
        }
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        return Space.isInSpace(toSpace.getSpace().getDescriptor(), object);
    }
}
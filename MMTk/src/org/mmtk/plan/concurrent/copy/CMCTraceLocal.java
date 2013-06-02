package org.mmtk.plan.concurrent.copy;

import org.mmtk.plan.Trace;
import org.mmtk.plan.TraceLocal;
import org.mmtk.policy.CopyLocal;
import org.mmtk.policy.CopySpace;
import org.mmtk.policy.Space;
import org.mmtk.vm.VM;
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
            if (Space.isInSpace(space.getDescriptor(), object)) {
                return space.traceObject(this, object, CMC.CMC_ALLOC);
            }
        }
        VM.activePlan.global().printPreStats();
        VM.activePlan.global().printUsedPages();
        return super.traceObject(object);
    }

    @Override
    public boolean willNotMoveInCurrentCollection(ObjectReference object) {
        return Space.isInSpace(toSpace.getSpace().getDescriptor(), object);
    }
}
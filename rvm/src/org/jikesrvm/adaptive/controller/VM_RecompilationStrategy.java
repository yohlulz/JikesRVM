/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.adaptive.controller;

import org.jikesrvm.VM;
import org.jikesrvm.adaptive.recompilation.VM_CompilerDNA;
import org.jikesrvm.adaptive.util.VM_AOSLogging;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.VM_NormalMethod;
import org.jikesrvm.compilers.common.VM_CompiledMethod;
import org.jikesrvm.compilers.opt.OptOptions;
import org.jikesrvm.compilers.opt.driver.CompilationPlan;
import org.jikesrvm.compilers.opt.driver.InstrumentationPlan;
import org.jikesrvm.compilers.opt.driver.OptimizationPlanElement;
import org.jikesrvm.compilers.opt.driver.OptimizationPlanner;
import org.jikesrvm.compilers.opt.runtimesupport.OptCompiledMethod;

/**
 * An abstract class providing the interface to the decision making
 * component of the controller.
 */
public abstract class VM_RecompilationStrategy {

  //------  Interface -------

  /**
   * A hot method has been passed to the controller by an organizer
   */
  VM_ControllerPlan considerHotMethod(VM_CompiledMethod cmpMethod, VM_HotMethodEvent hme) {
    // Default behavior, do nothing.
    return null;
  }

  /**
   * A hot call edge has been passed to the controller by an organizer
   */
  void considerHotCallEdge(VM_CompiledMethod cmpMethod, VM_AINewHotEdgeEvent event) {
    // Default behavior, do nothing.
  }

  // Functionality common to all recompilation strategies
  // (at least for now)

  /**
   *  Initialize the recompilation strategy.
   *
   *  Note: This uses the command line options to set up the
   *  optimization plans, so this must be run after the command line
   *  options are available.
   */
  void init() {
    createOptimizationPlans();
  }

  /**
   * This helper method creates a ControllerPlan, which contains a
   * CompilationPlan, for the passed method using the passed optimization
   * level and instrumentation plan.
   *
   * @param method the RVMMethod for the plan
   * @param optLevel the optimization level to use in the plan
   * @param instPlan the instrumentation plan to use
   * @param prevCMID the previous compiled method ID
   * @param expectedSpeedup  expected speedup from this recompilation
   * @param priority a measure of the oveall benefit we expect to see
   *                 by executing this plan.
   * @return the compilation plan to be used
   */
  VM_ControllerPlan createControllerPlan(RVMMethod method, int optLevel, InstrumentationPlan instPlan, int prevCMID,
                                         double expectedSpeedup, double expectedCompilationTime, double priority) {

    // Construct the compilation plan (varies depending on strategy)
    CompilationPlan compPlan = createCompilationPlan((VM_NormalMethod) method, optLevel, instPlan);

    // Create the controller plan
    return new VM_ControllerPlan(compPlan,
                                 VM_Controller.controllerClock,
                                 prevCMID,
                                 expectedSpeedup,
                                 expectedCompilationTime,
                                 priority);
  }

  /**
   * Construct a compilation plan that will compile the given method
   * with instrumentation.
   *
   * @param method The method to be compiled with instrumentation
   * @param optLevel The opt-level to recompile at
   * @param instPlan The instrumentation plan
   */
  public CompilationPlan createCompilationPlan(VM_NormalMethod method, int optLevel,
                                                   InstrumentationPlan instPlan) {

    // Construct a plan from the basic pre-computed opt-levels
    return new CompilationPlan(method, _optPlans[optLevel], null, _options[optLevel]);
  }

  /**
   * Should we consider the hme for recompilation?
   *
   * @param hme the VM_HotMethodEvent
   * @param plan the VM_ControllerPlan for the compiled method (may be null)
   * @return true/false value
   */
  boolean considerForRecompilation(VM_HotMethodEvent hme, VM_ControllerPlan plan) {
    RVMMethod method = hme.getMethod();
    if (plan == null) {
      // Our caller did not find a matching plan for this compiled method.
      // Therefore the code was not generated by the AOS recompilation subsystem.
      if (VM_ControllerMemory.shouldConsiderForInitialRecompilation(method)) {
        // AOS has not already taken action to address the situation
        // (or it attempted to take action, and the attempt failed in a way
        //  that doesn't preclude trying again,
        //  for example the compilation queue could have been full).
        return true;
      } else {
        // AOS has already taken action to address the situation, and thus
        // we need to handle this as an old compiled version of a
        // method still being live on some thread's stack.
        transferSamplesToNewPlan(hme);
        return false;
      }
    } else {
      // A matching plan was found.
      if (plan.getStatus() == VM_ControllerPlan.OUTDATED ||
          VM_ControllerMemory.planWithStatus(method, VM_ControllerPlan.IN_PROGRESS)) {
        // (a) The HotMethodEvent actually corresponds to an
        // old compiled version of the method
        // that is still live on some thread's stack or
        // (b) AOS has already initiated a plan that hasn't
        // completed yet to address the situation.
        // Therefore don't initiate a new recompilation action.
        transferSamplesToNewPlan(hme);
        return false;
      }
      // if AOS failed to successfully recompile this method before.
      // Don't try it again.
      return !VM_ControllerMemory.planWithStatus(method, VM_ControllerPlan.ABORTED_COMPILATION_ERROR);
    }
  }

  private void transferSamplesToNewPlan(VM_HotMethodEvent hme) {
    VM_AOSLogging.oldVersionStillHot(hme);
    double oldNumSamples = VM_Controller.methodSamples.getData(hme.getCMID());
    VM_ControllerPlan activePlan = VM_ControllerMemory.findLatestPlan(hme.getMethod());
    if (activePlan == null) return; // shouldn't happen.
    int newCMID = activePlan.getCMID();
    if (newCMID > 0) {
      // If we have a valid CMID then transfer the samples.
      // If the CMID isn't valid, it means the compilation hasn't completed yet and
      // the samples will be transfered by the compilation thread when it does (so we do nothing).
      VM_Controller.methodSamples.reset(hme.getCMID());
      double expectedSpeedup = activePlan.getExpectedSpeedup();
      double newNumSamples = oldNumSamples / expectedSpeedup;
      VM_Controller.methodSamples.augmentData(newCMID, newNumSamples);
    }
  }

  /**
   *  This method returns true if we've already tried to recompile the
   *  passed method.  It does not guarantee that the compilation was
   *  successful.
   *
   *  @param method the method of interest
   *  @return whether we've tried to recompile this method
   */
  boolean previousRecompilationAttempted(RVMMethod method) {
    return VM_ControllerMemory.findLatestPlan(method) != null;
  }

  /**
   *  This method retrieves the previous compiler constant.
   */
  int getPreviousCompiler(VM_CompiledMethod cmpMethod) {
    switch (cmpMethod.getCompilerType()) {
      case VM_CompiledMethod.TRAP:
      case VM_CompiledMethod.JNI:
        return -1; // don't try to optimize these guys!
      case VM_CompiledMethod.BASELINE: {
        // Prevent the adaptive system from recompiling certain classes
        // of baseline compiled methods.
        if (cmpMethod.getMethod().getDeclaringClass().hasDynamicBridgeAnnotation()) {
          // The opt compiler does not implement this calling convention.
          return -1;
        }
        if (cmpMethod.getMethod().getDeclaringClass().hasBridgeFromNativeAnnotation()) {
          // The opt compiler does not implement this calling convention.
          return -1;
        }
        if (cmpMethod.getMethod().hasNoOptCompileAnnotation()) {
          // Explict declaration that the method should not be opt compiled.
          return -1;
        }
        if (!cmpMethod.getMethod().isInterruptible()) {
          // A crude filter to identify the subset of core VM methods that
          // can't be recompiled because we require their code to be non-moving.
          // We really need to do a better job of this to avoid missing too many opportunities.
          // NOTE: it doesn't matter whether or not the GC is non-moving here,
          //       because recompiling effectively moves the code to a new location even if
          //       GC never moves it again!!!
          //      (C code may have a return address or other naked pointer into the old instruction array)
          return -1;
        }
        return 0;
      }
      case VM_CompiledMethod.OPT:
        OptCompiledMethod optMeth = (OptCompiledMethod) cmpMethod;
        return VM_CompilerDNA.getCompilerConstant(optMeth.getOptLevel());
      default:
        if (VM.VerifyAssertions) VM._assert(false, "Unknown Compiler");
        return -1;
    }
  }

  /**
   * What is the maximum opt level that is vallid according to this strategy?
   */
  int getMaxOptLevel() {
    return VM_Controller.options.DERIVED_MAX_OPT_LEVEL;
  }

  private OptimizationPlanElement[][] _optPlans;
  private OptOptions[] _options;

  /**
   * Create the default set of <optimization plan, options> pairs
   * Process optimizing compiler command line options.
   */
  void createOptimizationPlans() {
    OptOptions options = new OptOptions();

    int maxOptLevel = getMaxOptLevel();
    _options = new OptOptions[maxOptLevel + 1];
    _optPlans = new OptimizationPlanElement[maxOptLevel + 1][];
    String[] optCompilerOptions = VM_Controller.getOptCompilerOptions();
    for (int i = 0; i <= maxOptLevel; i++) {
      _options[i] = options.dup();
      _options[i].setOptLevel(i);               // set optimization level specific optimiations
      processCommandLineOptions(_options[i], i, maxOptLevel, optCompilerOptions);
      _optPlans[i] = OptimizationPlanner.createOptimizationPlan(_options[i]);
      if (_options[i].PRELOAD_CLASS != null) {
        VM.sysWrite("PRELOAD_CLASS should be specified with -X:irc not -X:recomp\n");
        VM.sysExit(VM.EXIT_STATUS_BOGUS_COMMAND_LINE_ARG);
      }
    }
  }

  /**
   * Process the command line arguments and pass the appropriate ones to the
   * Options
   * Called by sampling and counters recompilation strategy.
   *
   * @param options The options being constructed
   * @param optLevel The level of the options being constructed
   * @param maxOptLevel The maximum valid opt level
   * @param optCompilerOptions The list of command line options
   */
  public static void processCommandLineOptions(OptOptions options, int optLevel, int maxOptLevel,
                                               String[] optCompilerOptions) {

    String prefix = "opt" + optLevel + ":";
    for (String optCompilerOption : optCompilerOptions) {
      if (optCompilerOption.startsWith("opt:")) {
        String option = optCompilerOption.substring(4);
        if (!options.processAsOption("-X:recomp:", option)) {
          VM.sysWrite("vm: Unrecognized optimizing compiler command line argument: \"" +
                      option +
                      "\" passed in as " +
                      optCompilerOption +
                      "\n");
        }
      } else if (optCompilerOption.startsWith(prefix)) {
        String option = optCompilerOption.substring(5);
        if (!options.processAsOption("-X:recomp:" + prefix, option)) {
          VM.sysWrite("vm: Unrecognized optimizing compiler command line argument: \"" +
                      option +
                      "\" passed in as " +
                      optCompilerOption +
                      "\n");
        }
      }
    }
    // TODO: check for optimization levels that are invalid; that is,
    // greater than optLevelMax.
    //
    for (String optCompilerOption1 : optCompilerOptions) {
      if (!optCompilerOption1.startsWith("opt")) {
        // This should never be the case!
        continue;
      }
      if (!optCompilerOption1.startsWith("opt:")) {
        // must specify optimization level!
        int endPoint = optCompilerOption1.indexOf(":");
        if (endPoint == -1) {
          VM.sysWrite("vm: Unrecognized optimization level in optimizing compiler command line argument: \"" +
                      optCompilerOption1 +
                      "\"\n");
        }
        String optLevelS;
        try {
          optLevelS = optCompilerOption1.substring(3, endPoint);
        } catch (IndexOutOfBoundsException e) {
          VM.sysWrite("vm internal error: trying to find opt level has thrown indexOutOfBoundsException\n");
          e.printStackTrace();
          continue;
        }
        try {
          Integer optLevelI = Integer.valueOf(optLevelS);
          int cmdOptLevel = optLevelI;
          if (cmdOptLevel > maxOptLevel) {
            VM.sysWrite("vm: Invalid optimization level in optimizing compiler command line argument: \"" +
                        optCompilerOption1 +
                        "\"\n" +
                        "  Specified optimization level " +
                        cmdOptLevel +
                        " must be less than " +
                        maxOptLevel +
                        "\n");
          }
        } catch (NumberFormatException e) {
          VM.sysWrite("vm: Unrecognized optimization level in optimizing compiler command line argument: \"" +
                      optCompilerOption1 +
                      "\"\n");
        }
      }
    }
  }
}





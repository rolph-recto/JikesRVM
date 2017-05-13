/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.adaptive.measurements.listeners;

import org.jikesrvm.VM;
import org.jikesrvm.adaptive.AosEntrypoints;
import org.jikesrvm.architecture.StackFrameLayout;
import org.jikesrvm.compilers.common.CompiledMethod;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.Synchronization;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;

/**
 * A EdgeListener defines a listener
 * that computes a call graph edge from the call stack.
 * After a parameterized number of edges are collected,
 * it notifies its organizer that the threshold is reached.
 * <p>
 * Defines update's interface.
 * <p>
 * EdgeListener communicates with an organizer through a
 * integer array, buffer.  Each time this listener is called,
 * it places a triple of integers in buffer that correspond to
 * the callee, caller, and machine code offset of the call site
 */
@Uninterruptible
public class EdgeListener extends ContextListener {

  protected static final boolean DEBUG = false;

  /**
   * buffer provides the communication channel between the listener and the
   * organizer.
   * The buffer contains an array of triples &lt;callee, caller, address&gt; where
   * the caller and callee are CompiledMethodID's.
   * Initially, buffer contains zeros.  The listener adds triples.
   * When the listener hits the end of the buffer, notify the organizer.
   */
  private int[] buffer;

  /**
   * Number of samples to be taken before issuing callback to controller
   */
  private int desiredSamples;

  /**
   * Number of samples taken so far
   */
  @Entrypoint
  protected int samplesTaken;

  /**
   * Number of times update is called
   */
  @Entrypoint
  protected int updateCalled;

  /**
   * Constructor
   */
  public EdgeListener() {
    buffer = null;
    desiredSamples = 0;
  }

  /**
   * @return the number of times that update has been called
   */
  int getTimesUpdateCalled() {
    return updateCalled;
  }

  /**
   * Setup buffer and buffer size.
   * This method must be called before any data can be written to
   * the buffer.
   *
   * @param buffer the allocated buffer to contain the samples, size should
   *      be a muliple of 3
   */
  public void setBuffer(int[] buffer) {
    // ensure buffer is proper length
    if (VM.VerifyAssertions) {
      VM._assert(buffer.length % 3 == 0);
    }

    if (DEBUG) {
      VM.sysWriteln("EdgeListener.setBuffer(", buffer.length, "): enter");
    }

    this.buffer = buffer;
    desiredSamples = buffer.length / 3;
    resetBuffer();
  }

  /**
   * This method is called when a call stack edge needs to be
   * sampled.  Expect the sfp argument to point to the stack frame that
   * contains the target of the edge to be sampled.
   * <p>
   * NOTE: This method is uninterruptible, therefore we don't need to disable
   *       thread switching during stackframe inspection.
   *
   * @param sfp  a pointer to the stack frame that corresponds to the callee of
   *             the call graph edge that is to be sampled.
   * @param whereFrom Was this a yieldpoint in a PROLOGUE, BACKEDGE, or
   *         EPILOGUE?
   */
  @Override
  public final void update(Address sfp, int whereFrom) {
    if (DEBUG) {
      VM.sysWrite("EdgeListener.update(", sfp, ",", whereFrom);
      VM.sysWriteln("): enter ", samplesTaken);
    }

    Synchronization.fetchAndAdd(this, AosEntrypoints.edgeListenerUpdateCalledField.getOffset(), 1);

    // don't take a sample for back edge yield points
    if (whereFrom == RVMThread.BACKEDGE) return;

    int calleeCMID = 0;
    int callerCMID = 0;
    Address returnAddress = Address.zero();

    if (sfp.loadAddress().EQ(StackFrameLayout.getStackFrameSentinelFP())) {
      if (DEBUG) VM.sysWriteln(" Walking off end of stack!");
      return;
    }

    calleeCMID = Magic.getCompiledMethodID(sfp);
    if (calleeCMID == StackFrameLayout.getInvisibleMethodID()) {
      if (DEBUG) {
        VM.sysWrite(" INVISIBLE_METHOD_ID  (assembler code) ");
        VM.sysWrite(calleeCMID);
        VM.sysWriteln();
      }
      return;
    }

    returnAddress = Magic.getReturnAddress(sfp); // return address in caller
    sfp = Magic.getCallerFramePointer(sfp);      // caller's frame pointer
    if (sfp.loadAddress().EQ(StackFrameLayout.getStackFrameSentinelFP())) {
      if (DEBUG) VM.sysWriteln(" Walking off end of stack");
      return;
    }
    callerCMID = Magic.getCompiledMethodID(sfp);
    if (callerCMID == StackFrameLayout.getInvisibleMethodID()) {
      if (DEBUG) {
        VM.sysWrite(" INVISIBLE_METHOD_ID  (assembler code) ");
        VM.sysWrite(callerCMID);
        VM.sysWriteln();
      }
      return;
    }

    // store the offset of the return address from the beginning of the
    // instruction
    CompiledMethod callerCM = CompiledMethods.getCompiledMethod(callerCMID);
    if (callerCM.getCompilerType() == CompiledMethod.TRAP) {
      if (DEBUG) {
        VM.sysWriteln(" HARDWARE TRAP FRAME ");
      }
      return;
    }
    Offset callSite = callerCM.getInstructionOffset(returnAddress);

    if (DEBUG) {
      VM.sysWrite("  <");
      VM.sysWrite(calleeCMID);
      VM.sysWrite(",");
      VM.sysWrite(callerCMID);
      VM.sysWrite(",");
      VM.sysWrite(returnAddress);
      VM.sysWriteln(">");
    }

    // Find out what sample we are.
    int sampleNumber =
        Synchronization.fetchAndAdd(this, AosEntrypoints.edgeListenerSamplesTakenField.getOffset(), 1);
    int idx = 3 * sampleNumber;

    // If we got buffer slots that are beyond the end of the buffer, that means
    // that we're actually not supposed to take the sample at all (the system
    // is in the process of activating our organizer and processing the buffer).
    if (idx < buffer.length) {
      buffer[idx + 1] = callerCMID;
      buffer[idx + 2] = callSite.toInt();
      Magic.sync();
      buffer[idx + 0] = calleeCMID;

      // If we are the last sample, we need to activate the organizer.
      if (sampleNumber + 1 == desiredSamples) {
        activateOrganizer();
      }
    }
  }

  /**
   *  No-op.
   */
  @Override
  public final void report() {}

  @Override
  public void reset() {
    if (DEBUG) VM.sysWriteln("EdgeListener.reset(): enter");
    samplesTaken = 0;
    updateCalled = 0;
    resetBuffer();
  }

  /**
   *  Reset the buffer
   */
  private void resetBuffer() {
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = 0;
    }
  }
}

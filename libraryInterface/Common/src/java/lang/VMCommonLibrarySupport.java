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
package java.lang;

import static org.jikesrvm.runtime.VM_SysCall.sysCall;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.VM_Array;
import org.jikesrvm.classloader.VM_Atom;
import org.jikesrvm.classloader.VM_Class;
import org.jikesrvm.classloader.VM_Field;
import org.jikesrvm.classloader.VM_Member;
import org.jikesrvm.memorymanagers.mminterface.MM_Interface;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.runtime.VM_Entrypoints;
import org.jikesrvm.scheduler.VM_Synchronization;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.unboxed.Offset;

/**
 * Common utilities for Jikes RVM implementations of the java.lang API
 */
final class VMCommonLibrarySupport {
  /* ---- Non-inlined Exception Throwing Methods --- */
  /**
   * Method just to throw an illegal access exception without being inlined
   */
  @NoInline
  private static void throwNewIllegalAccessException(VM_Member member, VM_Class accessingClass) throws IllegalAccessException{
    throw new IllegalAccessException("Access to " + member + " is denied to " + accessingClass);
  }
  /* ---- General Reflection Support ---- */
  /**
   * Check to see if a method declared by the accessingClass
   * should be allowed to access the argument VM_Member.
   * Assumption: member is not public.  This trivial case should
   * be approved by the caller without needing to call this method.
   */
  static void checkAccess(VM_Member member, VM_Class accessingClass) throws IllegalAccessException {
    VM_Class declaringClass = member.getDeclaringClass();
    if (member.isPrivate()) {
      // access from the declaringClass is allowed
      if (accessingClass == declaringClass) return;
    } else if (member.isProtected()) {
      // access within the package is allowed.
      if (declaringClass.getClassLoader() == accessingClass.getClassLoader() && declaringClass.getPackageName().equals(accessingClass.getPackageName())) return;

      // access by subclasses is allowed.
      for (VM_Class cls = accessingClass; cls != null; cls = cls.getSuperClass()) {
        if (accessingClass == declaringClass) return;
      }
    } else {
      // default: access within package is allowed
      if (declaringClass.getClassLoader() == accessingClass.getClassLoader() && declaringClass.getPackageName().equals(accessingClass.getPackageName())) return;
    }
    throwNewIllegalAccessException(member, accessingClass);
  }
  /* ---- Runtime Support ---- */
  /**
   * Class responsible for acquiring and call the gc method. If a call has
   * taken place the gc method will just return.
   */
  private static final class GCLock {
    @SuppressWarnings("unused") // Accessed from VM_EntryPoints
    @Entrypoint
    private int gcLock;
    private final Offset gcLockOffset = VM_Entrypoints.gcLockField.getOffset();
    GCLock() {}
    void gc() {
      if (VM_Synchronization.testAndSet(this, gcLockOffset, 1)) {
        MM_Interface.gc();
        VM_Synchronization.fetchAndStore(this, gcLockOffset, 0);
      }
    }
  }
  private static final GCLock gcLockSingleton = new GCLock();

  /**
   * Request GC
   */
  static void gc() {
    gcLockSingleton.gc();
  }

  /**
   * Copy src array to dst array from location srcPos for length len to dstPos
   *
   * @param src array
   * @param srcPos position within source array
   * @param dst array
   * @param dstPos position within destination array
   * @param len amount of elements to copy
   */
  static void arraycopy(Object src, int srcPos, Object dst, int dstPos, int len) {
    if (src == null || dst == null) {
      RuntimeEntrypoints.raiseNullPointerException();
    } else if ((src instanceof char[]) && (dst instanceof char[])) {
      VM_Array.arraycopy((char[])src, srcPos, (char[])dst, dstPos, len);
    } else if ((src instanceof Object[]) && (dst instanceof Object[])) {
      VM_Array.arraycopy((Object[])src, srcPos, (Object[])dst, dstPos, len);
    } else if ((src instanceof byte[]) && (dst instanceof byte[])) {
      VM_Array.arraycopy((byte[])src, srcPos, (byte[])dst, dstPos, len);
    } else if ((src instanceof boolean[]) && (dst instanceof boolean[])) {
      VM_Array.arraycopy((boolean[])src, srcPos, (boolean[])dst, dstPos, len);
    } else if ((src instanceof short[]) && (dst instanceof short[])) {
      VM_Array.arraycopy((short[])src, srcPos, (short[])dst, dstPos, len);
    } else if ((src instanceof int[]) && (dst instanceof int[])) {
      VM_Array.arraycopy((int[])src, srcPos, (int[])dst, dstPos, len);
    } else if ((src instanceof long[]) && (dst instanceof long[])) {
      VM_Array.arraycopy((long[])src, srcPos, (long[])dst, dstPos, len);
    } else if ((src instanceof float[]) && (dst instanceof float[])) {
      VM_Array.arraycopy((float[])src, srcPos, (float[])dst, dstPos, len);
    } else if ((src instanceof double[]) && (dst instanceof double[])) {
      VM_Array.arraycopy((double[])src, srcPos, (double[])dst, dstPos, len);
    } else {
      RuntimeEntrypoints.raiseArrayStoreException();
    }
  }

  /**
   * Set the value of a static final stream field of the System class
   * @param fieldName name of field to set
   * @param stream value
   */
  static void setSystemStreamField(String fieldName, Object stream) {
    try {
      VM_Field field = ((VM_Class)JikesRVMSupport.getTypeForClass(System.class))
        .findDeclaredField(VM_Atom.findOrCreateUnicodeAtom(fieldName));
      field.setObjectValueUnchecked(null, stream);
    } catch (Exception e) {
      throw new Error(e.toString());
    }
  }
  /**
   * Apply library prefixes and suffixes as necessary to libname to produce a
   * full file name. For example, on linux "rvm" would become "librvm.so".
   *
   * @param libname name of library without any prefix or suffix
   * @return complete name of library
   */
  static String mapLibraryName(String libname) {
    String libSuffix;
    if (VM.BuildForLinux || VM.BuildForSolaris) {
      libSuffix = ".so";
    } else if (VM.BuildForOsx) {
      libSuffix = ".jnilib";
    } else {
      libSuffix = ".a";
    }
    return "lib" + libname + libSuffix;
  }
  /**
   * Get the value of an environment variable.
   */
  static String getenv(String envarName) {
    byte[] buf = new byte[128]; // Modest amount of space for starters.

    byte[] nameBytes = envarName.getBytes();

    // sysCall is uninterruptible so passing buf is safe
    int len = sysCall.sysGetenv(nameBytes, buf, buf.length);

    if (len < 0)                // not set.
      return null;

    if (len > buf.length) {
      buf = new byte[len];
      sysCall.sysGetenv(nameBytes, buf, len);
    }
    return new String(buf, 0, len);
  }
}

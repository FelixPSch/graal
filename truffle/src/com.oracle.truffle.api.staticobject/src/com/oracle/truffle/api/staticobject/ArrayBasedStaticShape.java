/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.staticobject;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import sun.misc.Unsafe;

import static com.oracle.truffle.api.staticobject.StaticPropertyKind.N_PRIMITIVES;

final class ArrayBasedStaticShape<T> extends StaticShape<T> {
    @CompilationFinal(dimensions = 1) //
    private final StaticShape<T>[] superShapes;
    private final ArrayBasedPropertyLayout propertyLayout;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArrayBasedStaticShape(ArrayBasedStaticShape<T> parentShape, Class<?> storageClass, ArrayBasedPropertyLayout propertyLayout, boolean safetyChecks) {
        super(storageClass, safetyChecks);
        if (parentShape == null) {
            superShapes = new StaticShape[]{this};
        } else {
            int depth = parentShape.superShapes.length;
            superShapes = new StaticShape[depth + 1];
            System.arraycopy(parentShape.superShapes, 0, superShapes, 0, depth);
            superShapes[depth] = this;
        }
        this.propertyLayout = propertyLayout;
    }

    static <T> ArrayBasedStaticShape<T> create(Class<?> generatedStorageClass, Class<? extends T> generatedFactoryClass, ArrayBasedStaticShape<T> parentShape,
                    Collection<StaticProperty> staticProperties, int byteArrayOffset, int objectArrayOffset, int shapeOffset, boolean checkShapes) {
        try {
            ArrayBasedPropertyLayout parentPropertyLayout = parentShape == null ? null : parentShape.getPropertyLayout();
            ArrayBasedPropertyLayout propertyLayout = new ArrayBasedPropertyLayout(parentPropertyLayout, staticProperties, byteArrayOffset, objectArrayOffset, shapeOffset);
            ArrayBasedStaticShape<T> shape = new ArrayBasedStaticShape<>(parentShape, generatedStorageClass, propertyLayout, checkShapes);
            T factory = generatedFactoryClass.cast(
                            generatedFactoryClass.getConstructor(Object.class, int.class, int.class).newInstance(shape, propertyLayout.getPrimitiveArraySize(), propertyLayout.getObjectArraySize()));
            shape.setFactory(factory);
            return shape;
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    Object getStorage(Object obj, boolean primitive) {
        Object receiverObject = cast(obj, storageClass);
        if (safetyChecks) {
            checkShape(receiverObject);
        } else {
            assert checkShape(receiverObject);
        }
        return UNSAFE.getObject(receiverObject, (long) (primitive ? propertyLayout.byteArrayOffset : propertyLayout.objectArrayOffset));
    }

    private boolean checkShape(Object receiverObject) {
        ArrayBasedStaticShape<?> receiverShape = cast(UNSAFE.getObject(receiverObject, (long) propertyLayout.shapeOffset), ArrayBasedStaticShape.class);
        if (this != receiverShape && (receiverShape.superShapes.length < superShapes.length || receiverShape.superShapes[superShapes.length - 1] != this)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Incompatible shape on property access. Expected '" + this + "' got '" + receiverShape + "'.");
        }
        return true;
    }

    private ArrayBasedPropertyLayout getPropertyLayout() {
        return propertyLayout;
    }

    /**
     * <p>
     * Creates a layout for the primitive fields of a given class, and assigns to each field the raw
     * offset in the byte array that represents the data. The layout tries to be as compact as
     * possible. The rules for determining the layout are as follow:
     *
     * <li>The Top klass (j.l.Object) will have its field offset corresponding the point where the
     * data in the byte array begins (the first offset after the array header)</li>
     * <li>If this offset is not long-aligned, then start further so that this new offset is aligned
     * to a long. Register that there is some space between the start of the raw data and the first
     * field offset (perhaps a byte could be squeezed in).</li>
     * <li>Other klasses will inherit their super klass' layout, and start appending their own
     * declared field at the first offset aligned with the biggest primitive a given class has.</li>
     * <li>If there are known holes in the parents layout, the klass will attempt to squeeze its own
     * fields in these holes.</li>
     * </p>
     * <p>
     * For example, suppose we have the following hierarchy, and that the first offset of the data
     * in a byte array is at 14:
     * </p>
     *
     * <pre>
     * class A {
     *     long l;
     *     int i;
     * }
     *
     * class B extends A {
     *     double d;
     * }
     *
     * class C extends B {
     *     float f;
     *     short s;
     * }
     * </pre>
     *
     * Then, the resulting layout for A would be:
     *
     * <pre>
     * - 0-13: header
     * - 14-15: unused  -> Padding for aligned long
     * - 16-23: l
     * - 24-27: i
     * </pre>
     *
     * the resulting layout for B would be:
     *
     * <pre>
     * - 0-13: header   }
     * - 14-15: unused  }   Same as
     * - 16-23: l       }      A
     * - 24-27: i       }
     * - 28-31: unused  -> Padding for aligned double
     * - 32-39: d
     * </pre>
     *
     * the resulting layout for C would be:
     *
     * <pre>
     * - 0-13: header
     * - 14-15: s       ->   hole filled
     * - 16-23: l
     * - 24-27: i
     * - 28-31: f       ->   hole filled
     * - 32-39: d
     * </pre>
     */
    static class ArrayBasedPropertyLayout {
        private final int primitiveArraySize;
        private final int objectArraySize;

        // Stats about primitive fields
        @CompilationFinal(dimensions = 2) //
        private final int[][] leftoverHoles;
        private final int lastOffset;

        private final int byteArrayOffset;
        private final int objectArrayOffset;
        private final int shapeOffset;

        ArrayBasedPropertyLayout(ArrayBasedPropertyLayout parentLayout, Collection<StaticProperty> staticProperties, int byteArrayOffset, int objectArrayOffset, int shapeOffset) {
            this.byteArrayOffset = byteArrayOffset;
            this.objectArrayOffset = objectArrayOffset;
            this.shapeOffset = shapeOffset;

            // Stats about primitive fields
            int superTotalByteCount;
            int[][] parentLeftoverHoles;
            int objArraySize;
            if (parentLayout == null) {
                // Align the starting offset to a long.
                superTotalByteCount = base() + alignmentCorrection();
                // Register a hole if we had to realign.
                if (alignmentCorrection() > 0) {
                    parentLeftoverHoles = new int[][]{{base(), base() + alignmentCorrection()}};
                } else {
                    parentLeftoverHoles = new int[0][];
                }
                objArraySize = 0;
            } else {
                superTotalByteCount = parentLayout.lastOffset;
                parentLeftoverHoles = parentLayout.leftoverHoles;
                objArraySize = parentLayout.objectArraySize;
            }

            int[] primitiveFields = new int[N_PRIMITIVES];
            for (StaticProperty staticProperty : staticProperties) {
                byte propertyKind = staticProperty.getInternalKind();
                if (propertyKind != StaticPropertyKind.Object.toByte()) {
                    primitiveFields[propertyKind]++;
                }
            }

            PrimitiveFieldIndexes primitiveFieldIndexes = new PrimitiveFieldIndexes(primitiveFields, superTotalByteCount, parentLeftoverHoles);
            for (StaticProperty staticProperty : staticProperties) {
                byte propertyKind = staticProperty.getInternalKind();
                int index;
                if (propertyKind == StaticPropertyKind.Object.toByte()) {
                    index = Unsafe.ARRAY_OBJECT_BASE_OFFSET + Unsafe.ARRAY_OBJECT_INDEX_SCALE * objArraySize++;
                } else {
                    index = primitiveFieldIndexes.getIndex(propertyKind);
                }
                staticProperty.initOffset(index);
            }
            lastOffset = primitiveFieldIndexes.offsets[N_PRIMITIVES - 1];
            primitiveArraySize = getSizeToAlloc(parentLayout == null ? 0 : parentLayout.primitiveArraySize, primitiveFieldIndexes);
            objectArraySize = objArraySize;
            leftoverHoles = primitiveFieldIndexes.schedule.nextLeftoverHoles;
        }

        /*
         * If the object model does not start on a long-aligned offset. To manage, we will align our
         * indexes to the actual relative address to the start of the object. Note that we still
         * make a pretty strong assumption here: All arrays are allocated at an address aligned with
         * a *long*
         *
         * Note that we cannot use static final fields here, as SVM initializes them at build time
         * using HotSpot's values, and thus the values for base and alignment would not be correct.
         */
        private static int base() {
            return Unsafe.ARRAY_BYTE_BASE_OFFSET;
        }

        private static int alignmentCorrection() {
            int misalignment = Unsafe.ARRAY_BYTE_BASE_OFFSET % Unsafe.ARRAY_LONG_INDEX_SCALE;
            return misalignment == 0 ? 0 : Unsafe.ARRAY_LONG_INDEX_SCALE - misalignment;
        }

        private static int getSizeToAlloc(int superToAlloc, PrimitiveFieldIndexes fieldIndexes) {
            int toAlloc = fieldIndexes.offsets[N_PRIMITIVES - 1] - base();
            assert toAlloc >= 0;
            if (toAlloc == alignmentCorrection() && fieldIndexes.schedule.isEmpty()) {
                // If superKlass has fields in the alignment hole, we will need to allocate. If not,
                // we
                // can save an array. Note that if such a field exists, we will allocate an array of
                // size at least the alignment correction, since we fill holes from the right to the
                // left.
                toAlloc = superToAlloc;
            }
            return toAlloc;
        }

        int getPrimitiveArraySize() {
            return primitiveArraySize;
        }

        int getObjectArraySize() {
            return objectArraySize;
        }

        private static final class PrimitiveFieldIndexes {
            final int[] offsets;
            final FillingSchedule schedule;

            // To ignore leftoverHoles, pass FillingSchedule.EMPTY_INT_ARRAY_ARRAY.
            // This is used for static fields, where the gain would be negligible.
            PrimitiveFieldIndexes(int[] primitiveFields, int superTotalByteCount, int[][] leftoverHoles) {
                offsets = new int[N_PRIMITIVES];
                offsets[0] = startOffset(superTotalByteCount, primitiveFields);
                this.schedule = FillingSchedule.create(superTotalByteCount, offsets[0], primitiveFields, leftoverHoles);
                // FillingSchedule.create() modifies primitiveFields.
                // Only offsets[0] must be initialized before creating the filling schedule.
                for (int i = 1; i < N_PRIMITIVES; i++) {
                    offsets[i] = offsets[i - 1] + primitiveFields[i - 1] * StaticPropertyKind.getByteCount(i - 1);
                }
            }

            int getIndex(byte propertyKind) {
                ScheduleEntry entry = schedule.query(propertyKind);
                if (entry != null) {
                    return entry.offset;
                } else {
                    int prevOffset = offsets[propertyKind];
                    offsets[propertyKind] += StaticPropertyKind.getByteCount(propertyKind);
                    return prevOffset;
                }
            }

            // Find first primitive to set, and align on it.
            private static int startOffset(int superTotalByteCount, int[] primitiveCounts) {
                int i = 0;
                while (i < N_PRIMITIVES && primitiveCounts[i] == 0) {
                    i++;
                }
                if (i == N_PRIMITIVES) {
                    return superTotalByteCount;
                }
                int r = superTotalByteCount % StaticPropertyKind.getByteCount(i);
                if (r == 0) {
                    return superTotalByteCount;
                }
                return superTotalByteCount + StaticPropertyKind.getByteCount(i) - r;
            }
        }

        /**
         * Greedily tries to fill the space between a parent's fields and its child.
         */
        private static final class FillingSchedule {
            static final int[][] EMPTY_INT_ARRAY_ARRAY = new int[0][];

            final List<ScheduleEntry> schedule;
            int[][] nextLeftoverHoles;

            final boolean isEmpty;

            boolean isEmpty() {
                return isEmpty;
            }

            static FillingSchedule create(int holeStart, int holeEnd, int[] counts, int[][] leftoverHoles) {
                List<ScheduleEntry> schedule = new ArrayList<>();
                if (leftoverHoles == EMPTY_INT_ARRAY_ARRAY) {
                    // packing static fields is not as interesting as instance fields: the array
                    // created
                    // to remember the hole would be bigger than what we would gain. Only schedule
                    // for
                    // direct parent.
                    scheduleHole(holeStart, holeEnd, counts, schedule);
                    return new FillingSchedule(schedule);
                } else {
                    List<int[]> nextHoles = new ArrayList<>();
                    scheduleHole(holeStart, holeEnd, counts, schedule, nextHoles);
                    if (leftoverHoles != null) {
                        for (int[] hole : leftoverHoles) {
                            scheduleHole(hole[0], hole[1], counts, schedule, nextHoles);
                        }
                    }
                    return new FillingSchedule(schedule, nextHoles);
                }
            }

            private static void scheduleHole(int holeStart, int holeEnd, int[] counts, List<ScheduleEntry> schedule, List<int[]> nextHoles) {
                int end = holeEnd;
                int holeSize = holeEnd - holeStart;
                byte i = 0;

                mainloop: while (holeSize > 0 && i < N_PRIMITIVES) {
                    int byteCount = StaticPropertyKind.getByteCount(i);
                    while (counts[i] > 0 && byteCount <= holeSize) {
                        int newEnd = end - byteCount;
                        if (newEnd % byteCount != 0) {
                            int misalignment = newEnd % byteCount;
                            int aligned = newEnd - misalignment;
                            if (aligned < holeStart) {
                                // re-aligning the store makes it overlap with somethig else: abort.
                                i++;
                                continue mainloop;
                            }
                            schedule.add(new ScheduleEntry(i, aligned));
                            counts[i]--;
                            // We created a new hole of size `misaligned`. Try to fill it.
                            scheduleHole(end - misalignment, end, counts, schedule, nextHoles);
                            newEnd = aligned;
                        } else {
                            counts[i]--;
                            schedule.add(new ScheduleEntry(i, newEnd));
                        }
                        end = newEnd;
                        holeSize = end - holeStart;
                    }
                    i++;
                }
                if (holeSize > 0) {
                    nextHoles.add(new int[]{holeStart, end});
                }
            }

            private static void scheduleHole(int holeStart, int holeEnd, int[] counts, List<ScheduleEntry> schedule) {
                int end = holeEnd;
                int holeSize = holeEnd - holeStart;
                byte i = 0;

                while (holeSize > 0 && i < N_PRIMITIVES) {
                    int primitiveByteCount = StaticPropertyKind.getByteCount(i);
                    while (counts[i] > 0 && primitiveByteCount <= holeSize) {
                        counts[i]--;
                        end -= primitiveByteCount;
                        holeSize -= primitiveByteCount;
                        schedule.add(new ScheduleEntry(i, end));
                    }
                    i++;
                }
                assert holeSize >= 0;
            }

            private FillingSchedule(List<ScheduleEntry> schedule) {
                this.schedule = schedule;
                this.isEmpty = schedule == null || schedule.isEmpty();
            }

            private FillingSchedule(List<ScheduleEntry> schedule, List<int[]> nextHoles) {
                this.schedule = schedule;
                this.nextLeftoverHoles = nextHoles.isEmpty() ? null : nextHoles.toArray(EMPTY_INT_ARRAY_ARRAY);
                this.isEmpty = schedule != null && schedule.isEmpty();
            }

            ScheduleEntry query(byte propertyKind) {
                for (ScheduleEntry e : schedule) {
                    if (e.propertyKind == propertyKind) {
                        schedule.remove(e);
                        return e;
                    }
                }
                return null;
            }
        }

        private static class ScheduleEntry {
            final byte propertyKind;
            final int offset;

            ScheduleEntry(byte propertyKind, int offset) {
                this.propertyKind = propertyKind;
                this.offset = offset;
            }
        }
    }
}

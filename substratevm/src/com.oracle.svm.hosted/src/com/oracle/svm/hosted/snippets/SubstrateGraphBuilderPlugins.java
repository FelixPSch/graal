/*
 * Copyright (c) 2012, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.snippets;

import java.io.ObjectInputFilter;
import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.stream.Stream;

import org.graalvm.nativeimage.AnnotationAccess;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.hosted.RuntimeSerialization;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;

import com.oracle.graal.pointsto.AbstractAnalysisEngine;
import com.oracle.graal.pointsto.infrastructure.OriginalClassProvider;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.nodes.UnsafePartitionLoadNode;
import com.oracle.graal.pointsto.nodes.UnsafePartitionStoreNode;
import com.oracle.svm.common.meta.MultiMethod;
import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.MissingRegistrationSupport;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.RuntimeAssertionsSupport;
import com.oracle.svm.core.StaticFieldsSupport;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.classinitialization.EnsureClassInitializedNode;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.graal.GraalEdgeUnsafePartition;
import com.oracle.svm.core.graal.jdk.SubstrateObjectCloneWithExceptionNode;
import com.oracle.svm.core.graal.nodes.DeoptEntryNode;
import com.oracle.svm.core.graal.nodes.FarReturnNode;
import com.oracle.svm.core.graal.nodes.FieldOffsetNode;
import com.oracle.svm.core.graal.nodes.ReadCallerStackPointerNode;
import com.oracle.svm.core.graal.nodes.ReadReservedRegister;
import com.oracle.svm.core.graal.nodes.ReadReturnAddressNode;
import com.oracle.svm.core.graal.nodes.SubstrateCompressionNode;
import com.oracle.svm.core.graal.nodes.SubstrateNarrowOopStamp;
import com.oracle.svm.core.graal.nodes.SubstrateReflectionGetCallerClassNode;
import com.oracle.svm.core.graal.nodes.TestDeoptimizeNode;
import com.oracle.svm.core.graal.stackvalue.LateStackValueNode;
import com.oracle.svm.core.graal.stackvalue.StackValueNode;
import com.oracle.svm.core.graal.stackvalue.UnsafeLateStackValue;
import com.oracle.svm.core.graal.stackvalue.UnsafeStackValue;
import com.oracle.svm.core.heap.ReferenceAccess;
import com.oracle.svm.core.heap.ReferenceAccessImpl;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.identityhashcode.SubstrateIdentityHashCodeNode;
import com.oracle.svm.core.jdk.proxy.DynamicProxyRegistry;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.FallbackFeature;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.ReachabilityRegistrationNode;
import com.oracle.svm.hosted.nodes.DeoptProxyNode;
import com.oracle.svm.hosted.substitute.AnnotationSubstitutionProcessor;
import com.oracle.svm.util.ClassUtil;

import jdk.graal.compiler.core.common.CompressEncoding;
import jdk.graal.compiler.core.common.type.AbstractObjectStamp;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Edges;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeList;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.java.BytecodeParser;
import jdk.graal.compiler.java.LambdaUtils;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ComputeObjectAddressNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.DynamicPiNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FullInfopointNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PiNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.NarrowNode;
import jdk.graal.compiler.nodes.calc.ZeroExtendNode;
import jdk.graal.compiler.nodes.extended.BytecodeExceptionNode;
import jdk.graal.compiler.nodes.extended.LoadHubNode;
import jdk.graal.compiler.nodes.extended.StateSplitProxyNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin.RequiredInlineOnlyInvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin.RequiredInvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import jdk.graal.compiler.nodes.java.DynamicNewInstanceNode;
import jdk.graal.compiler.nodes.java.InstanceOfDynamicNode;
import jdk.graal.compiler.nodes.java.NewArrayNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.spi.ArrayLengthProvider;
import jdk.graal.compiler.nodes.spi.Replacements;
import jdk.graal.compiler.nodes.type.NarrowOopStamp;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.virtual.AllocatedObjectNode;
import jdk.graal.compiler.nodes.virtual.CommitAllocationNode;
import jdk.graal.compiler.nodes.virtual.VirtualArrayNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.replacements.StandardGraphBuilderPlugins;
import jdk.graal.compiler.replacements.StandardGraphBuilderPlugins.AllocateUninitializedArrayPlugin;
import jdk.graal.compiler.replacements.StandardGraphBuilderPlugins.CounterModeCryptPlugin;
import jdk.graal.compiler.replacements.StandardGraphBuilderPlugins.ReachabilityFencePlugin;
import jdk.graal.compiler.replacements.nodes.AESNode;
import jdk.graal.compiler.replacements.nodes.CipherBlockChainingAESNode;
import jdk.graal.compiler.replacements.nodes.CounterModeAESNode;
import jdk.graal.compiler.replacements.nodes.MacroNode.MacroParams;
import jdk.graal.compiler.replacements.nodes.VectorizedHashCodeNode;
import jdk.graal.compiler.replacements.nodes.VectorizedMismatchNode;
import jdk.graal.compiler.word.WordCastNode;
import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.LocalVariableTable;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class SubstrateGraphBuilderPlugins {

    /** Collection of debug options for SubstrateGraphBuilderPlugins. */
    public static class Options {
        @Option(help = "Enable trace logging for dynamic proxy.")//
        public static final HostedOptionKey<Boolean> DynamicProxyTracing = new HostedOptionKey<>(false);
    }

    public static void registerInvocationPlugins(AnnotationSubstitutionProcessor annotationSubstitutions,
                    ImageClassLoader loader,
                    InvocationPlugins plugins,
                    Replacements replacements,
                    ParsingReason parsingReason,
                    Architecture architecture,
                    boolean supportsStubBasedPlugins) {

        // register the substratevm plugins
        registerSystemPlugins(plugins);
        registerReflectionPlugins(plugins, replacements);
        registerImageInfoPlugins(plugins);
        registerProxyPlugins(annotationSubstitutions, plugins, parsingReason);
        registerSerializationPlugins(loader, plugins, parsingReason);
        registerAtomicUpdaterPlugins(plugins);
        registerObjectPlugins(plugins);
        registerUnsafePlugins(plugins);
        registerKnownIntrinsicsPlugins(plugins);
        registerStackValuePlugins(plugins);
        registerArrayPlugins(plugins);
        registerClassPlugins(plugins);
        registerEdgesPlugins(plugins);
        registerVMConfigurationPlugins(plugins);
        registerPlatformPlugins(plugins);
        registerSizeOfPlugins(plugins);
        registerReferencePlugins(plugins, parsingReason);
        registerReferenceAccessPlugins(plugins);
        if (supportsStubBasedPlugins) {
            registerAESPlugins(plugins, replacements, architecture);
            registerArraysSupportPlugins(plugins, replacements, architecture);
        }
    }

    private static void registerSerializationPlugins(ImageClassLoader loader, InvocationPlugins plugins, ParsingReason reason) {
        if (reason.duringAnalysis() && reason != ParsingReason.JITCompilation) {
            Registration serializationFilter = new Registration(plugins, ObjectInputFilter.Config.class);
            serializationFilter.register(new RequiredInvocationPlugin("createFilter", String.class) {
                @Override
                public boolean isDecorator() {
                    return true;
                }

                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode patternNode) {
                    if (nonNullJavaConstants(patternNode)) {
                        String pattern = b.getSnippetReflection().asObject(String.class, patternNode.asJavaConstant());
                        b.add(ReachabilityRegistrationNode.create(() -> parsePatternAndRegister(pattern), reason));
                        return true;
                    }
                    return false;
                }
            });

            if (ModuleLayer.boot().findModule("jdk.unsupported").isPresent()) {
                Registration customConstructor = new Registration(plugins, loader.findClassOrFail("sun.reflect.ReflectionFactory"));
                customConstructor.register(new RequiredInvocationPlugin("newConstructorForSerialization", Receiver.class, Class.class) {
                    @Override
                    public boolean isDecorator() {
                        return true;
                    }

                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clazz) {
                        if (nonNullJavaConstants(receiver.get(), clazz)) {
                            b.add(ReachabilityRegistrationNode.create(() -> RuntimeSerialization.register(b.getSnippetReflection().asObject(Class.class, clazz.asJavaConstant())), reason));
                            return true;
                        }
                        return false;
                    }
                });

                customConstructor.register(new RequiredInvocationPlugin("newConstructorForSerialization", Receiver.class, Class.class, Constructor.class) {
                    @Override
                    public boolean isDecorator() {
                        return true;
                    }

                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clazz, ValueNode constructor) {
                        if (nonNullJavaConstants(receiver.get(), clazz, constructor)) {
                            var constructorDeclaringClass = b.getSnippetReflection().asObject(Constructor.class, constructor.asJavaConstant()).getDeclaringClass();
                            b.add(ReachabilityRegistrationNode
                                            .create(() -> RuntimeSerialization.registerWithTargetConstructorClass(b.getSnippetReflection().asObject(Class.class, clazz.asJavaConstant()),
                                                            constructorDeclaringClass), reason));
                            return true;
                        }
                        return false;
                    }
                });
            }
        }
    }

    private static boolean nonNullJavaConstants(ValueNode... valueNodes) {
        for (ValueNode valueNode : valueNodes) {
            if (!valueNode.isJavaConstant() || valueNode.isNullConstant()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLimitPattern(String pattern) {
        int eqNdx = pattern.indexOf('=');
        // not a limit pattern
        return eqNdx >= 0;
    }

    /**
     * Extract the target class name from the <code>pattern</code>. We support two formats:
     * <ul>
     * <li>A concrete class name (pattern doesn't end with .* or .**), e.g.:
     * <code>com.foo.Bar</code>. In this case we register the concrete class for
     * serialization/deserialization.</li>
     * <li>A concrete class name that ends with a <code>$$Lambda$*</code>. In this case, we register
     * all lambdas that originate in the methods of the target class for
     * serialization/deserialization.</li>
     * </ul>
     */
    private static void parsePatternAndRegister(String pattern) {
        String[] patterns = pattern.split(";");
        for (String p : patterns) {
            int nameLen = p.length();
            if (nameLen == 0) {
                continue;
            }
            if (isLimitPattern(p)) {
                continue;
            }
            boolean negate = p.charAt(0) == '!';
            int poffset = negate ? 1 : 0;

            // isolate module name, if any
            int slash = p.indexOf('/', poffset);
            if (slash == poffset) {
                continue; // Module name is missing.
            }
            poffset = (slash >= 0) ? slash + 1 : poffset;

            if (p.endsWith("*")) {
                // Wildcard cases
                if (!(p.endsWith(".*") || p.endsWith(".**"))) {
                    // Pattern is a classname (possibly empty) with a trailing wildcard
                    final String className = p.substring(poffset, nameLen - 1);
                    if (!negate) {
                        if (className.endsWith(LambdaUtils.SERIALIZATION_TEST_LAMBDA_CLASS_SUBSTRING)) {
                            try {
                                String lambdaHolderName = LambdaUtils.capturingClass(className);
                                RuntimeSerialization.registerLambdaCapturingClass(Class.forName(lambdaHolderName, false, Thread.currentThread().getContextClassLoader()));
                            } catch (ClassNotFoundException e) {
                                // no class, no registration
                            }
                        }
                    }
                }
            } else {
                String name = p.substring(poffset);
                if (name.isEmpty()) {
                    return;
                }
                // Pattern is a class name
                if (!negate) {
                    try {
                        /* Support arrays of non-primitive types */
                        if (name.startsWith("[") && name.contains("[L") && !name.endsWith(";")) {
                            name += ";";
                        }
                        RuntimeSerialization.register(Class.forName(name, false, Thread.currentThread().getContextClassLoader()));
                    } catch (ClassNotFoundException e) {
                        // no class, no registration
                    }
                }
            }
        }
    }

    private static void registerSystemPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, System.class);
        if (SubstrateOptions.FoldSecurityManagerGetter.getValue()) {
            r.register(new RequiredInvocationPlugin("getSecurityManager") {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    /* System.getSecurityManager() always returns null. */
                    b.addPush(JavaKind.Object, ConstantNode.forConstant(b.getSnippetReflection().forObject(null), b.getMetaAccess(), b.getGraph()));
                    return true;
                }
            });
        }

        r.register(new RequiredInvocationPlugin("identityHashCode", Object.class) {

            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                b.addPush(JavaKind.Int, SubstrateIdentityHashCodeNode.create(object, b.bci()));
                return true;
            }

        });
    }

    private static void registerReflectionPlugins(InvocationPlugins plugins, Replacements replacements) {
        Registration r = new Registration(plugins, "jdk.internal.reflect.Reflection", replacements);
        r.register(new RequiredInlineOnlyInvocationPlugin("getCallerClass") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, new SubstrateReflectionGetCallerClassNode(MacroParams.of(b, targetMethod)));
                return true;
            }
        });
    }

    private static void registerImageInfoPlugins(InvocationPlugins plugins) {
        Registration proxyRegistration = new Registration(plugins, ImageInfo.class);
        proxyRegistration.register(new RequiredInvocationPlugin("inImageCode") {
            /** See {@link ImageInfo#inImageCode()}. */
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.push(JavaKind.Boolean, ConstantNode.forConstant(JavaConstant.TRUE, b.getMetaAccess(), b.getGraph()));
                return true;
            }
        });
        proxyRegistration.register(new RequiredInvocationPlugin("inImageBuildtimeCode") {
            /** See {@link ImageInfo#inImageBuildtimeCode()}. */
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.push(JavaKind.Boolean, ConstantNode.forConstant(JavaConstant.FALSE, b.getMetaAccess(), b.getGraph()));
                return true;
            }
        });
        proxyRegistration.register(new RequiredInvocationPlugin("inImageRuntimeCode") {
            /** See {@link ImageInfo#inImageRuntimeCode()}. */
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.push(JavaKind.Boolean, ConstantNode.forConstant(JavaConstant.TRUE, b.getMetaAccess(), b.getGraph()));
                return true;
            }
        });
    }

    private static void registerProxyPlugins(AnnotationSubstitutionProcessor annotationSubstitutions, InvocationPlugins plugins, ParsingReason reason) {
        Registration proxyRegistration = new Registration(plugins, Proxy.class);
        registerProxyPlugin(proxyRegistration, annotationSubstitutions, reason, "getProxyClass", ClassLoader.class, Class[].class);
        registerProxyPlugin(proxyRegistration, annotationSubstitutions, reason, "newProxyInstance", ClassLoader.class, Class[].class, InvocationHandler.class);
    }

    private static void registerProxyPlugin(Registration proxyRegistration, AnnotationSubstitutionProcessor annotationSubstitutions, ParsingReason reason,
                    String name, Class<?>... parameterTypes) {
        proxyRegistration.register(new RequiredInvocationPlugin(name, parameterTypes) {
            @Override
            public boolean isDecorator() {
                return true;
            }

            @Override
            public boolean defaultHandler(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode... args) {
                Runnable proxyRegistrationRunnable = interceptProxyInterfaces(b, targetMethod, annotationSubstitutions, args[1]);
                if (proxyRegistrationRunnable != null) {
                    Class<?> callerClass = OriginalClassProvider.getJavaClass(b.getMethod().getDeclaringClass());
                    boolean callerInScope = MissingRegistrationSupport.singleton().reportMissingRegistrationErrors(callerClass.getModule().getName(), callerClass.getPackageName(),
                                    callerClass.getName());
                    if (callerInScope && reason.duringAnalysis() && reason != ParsingReason.JITCompilation) {
                        b.add(ReachabilityRegistrationNode.create(proxyRegistrationRunnable, reason));
                        return true;
                    }

                    proxyRegistrationRunnable.run();
                    return false;
                }
                return false;
            }
        });
    }

    /**
     * Try to intercept proxy interfaces passed in as literal constants, and register the interfaces
     * in the {@link DynamicProxyRegistry}.
     */
    private static Runnable interceptProxyInterfaces(GraphBuilderContext b, ResolvedJavaMethod targetMethod, AnnotationSubstitutionProcessor annotationSubstitutions, ValueNode interfacesNode) {
        Class<?>[] interfaces = extractClassArray(b, annotationSubstitutions, interfacesNode);
        if (interfaces != null) {
            var caller = b.getGraph().method();
            var method = b.getMethod();
            var bci = b.bci();

            return () -> {
                /* The interfaces array can be empty. The java.lang.reflect.Proxy API allows it. */
                RuntimeProxyCreation.register(interfaces);
                if (ImageSingletons.contains(FallbackFeature.class)) {
                    ImageSingletons.lookup(FallbackFeature.class).addAutoProxyInvoke(method, bci);
                }
                if (Options.DynamicProxyTracing.getValue()) {
                    System.out.println("Successfully determined constant value for interfaces argument of call to " + targetMethod.format("%H.%n(%p)") +
                                    " reached from " + caller.format("%H.%n(%p)") + ". " + "Registered proxy class for " + Arrays.toString(interfaces) + ".");
                }
            };
        }
        if (Options.DynamicProxyTracing.getValue() && !b.parsingIntrinsic()) {
            System.out.println("Could not determine constant value for interfaces argument of call to " + targetMethod.format("%H.%n(%p)") +
                            " reached from " + b.getGraph().method().format("%H.%n(%p)") + ".");
        }
        return null;
    }

    /**
     * Try to extract a Class array from a ValueNode. It does not guarantee that the array content
     * will not change.
     */
    static Class<?>[] extractClassArray(GraphBuilderContext b, AnnotationSubstitutionProcessor annotationSubstitutions, ValueNode arrayNode) {
        Class<?>[] classes = extractClassArray(b, annotationSubstitutions, arrayNode, false);
        /*
         * If any of the element is null just bailout, this is probably a situation where the array
         * will be filled in later and we don't track that.
         */
        return classes == null ? null : Stream.of(classes).allMatch(Objects::nonNull) ? classes : null;
    }

    /**
     * Try to extract a Class array from a ValueNode. There are two situations:
     *
     * 1. The node is a ConstantNode. Then we get its initial value. However, since Java doesn't
     * have immutable arrays this method cannot guarantee that the array content will not change.
     * Therefore, if <code>exact</code> is set to true we return null.
     *
     * 2. The node is a NewArrayNode. Then we track the stores in the array as long as all are
     * constants and there is no control flow split. If the content of the array cannot be
     * determined a null value is returned.
     */
    static Class<?>[] extractClassArray(GraphBuilderContext b, AnnotationSubstitutionProcessor annotationSubstitutions, ValueNode arrayNode, boolean exact) {
        /* Use the original value in case we are in a deopt target method. */
        ValueNode originalArrayNode = getDeoptProxyOriginalValue(arrayNode);
        if (originalArrayNode.isJavaConstant() && !exact) {
            /*
             * The array is a constant, however that doesn't make the array immutable, i.e., its
             * elements can still be changed. We assume that will not happen.
             */
            return b.getSnippetReflection().asObject(Class[].class, originalArrayNode.asJavaConstant());

        } else if (originalArrayNode instanceof AllocatedObjectNode && StampTool.isAlwaysArray(originalArrayNode)) {
            AllocatedObjectNode allocatedObjectNode = (AllocatedObjectNode) originalArrayNode;
            if (!allocatedObjectNode.getVirtualObject().type().equals(b.getMetaAccess().lookupJavaType(Class[].class))) {
                /* Not allocating a Class[] array. */
                return null;
            }
            CommitAllocationNode commitAllocationNode = allocatedObjectNode.getCommit();
            if (skipNonInterferingNodes(commitAllocationNode.next()) != null) {
                /* Nodes after the array materialization could interfere with the array. */
                return null;
            }

            int objectStartIndex = 0;
            for (VirtualObjectNode virtualObject : commitAllocationNode.getVirtualObjects()) {
                if (virtualObject == allocatedObjectNode.getVirtualObject()) {
                    /* We found the begin of the object we were looking for. */
                    assert virtualObject instanceof VirtualArrayNode : virtualObject;

                    Class<?>[] result = new Class<?>[virtualObject.entryCount()];
                    for (int i = 0; i < result.length; i++) {
                        JavaConstant valueConstant = commitAllocationNode.getValues().get(objectStartIndex + i).asJavaConstant();
                        if (!storeClassArrayConstant(b, result, i, valueConstant, annotationSubstitutions)) {
                            return null;
                        }
                    }
                    return result;
                }
                objectStartIndex += virtualObject.entryCount();
            }
            throw VMError.shouldNotReachHere("Must have found the virtual object");

        } else if (originalArrayNode instanceof NewArrayNode) {
            /*
             * Find the elements written to the array. If the array length is a constant, all
             * written elements are constants and all array elements are filled then return the
             * array elements.
             */
            NewArrayNode newArray = (NewArrayNode) originalArrayNode;
            if (!newArray.elementType().equals(b.getMetaAccess().lookupJavaType(Class.class))) {
                /* Not allocating a Class[] array. */
                return null;
            }
            ValueNode newArrayLengthNode = newArray.length();
            if (!newArrayLengthNode.isJavaConstant()) {
                /*
                 * If the array size is not a constant we bail out early since we cannot check that
                 * all array elements are filled.
                 */
                return null;
            }
            assert newArrayLengthNode.asJavaConstant().getJavaKind() == JavaKind.Int;
            int newArrayLength = newArrayLengthNode.asJavaConstant().asInt();

            /*
             * Walk down the control flow successor as long as we find StoreIndexedNode. Those are
             * values written in the array.
             */
            Class<?>[] result = new Class<?>[newArrayLength];
            FixedNode successor = unwrapNode(newArray.next());
            while (successor instanceof StoreIndexedNode) {
                StoreIndexedNode store = (StoreIndexedNode) successor;
                if (getDeoptProxyOriginalValue(store.array()).equals(newArray)) {
                    if (!store.index().isJavaConstant()) {
                        return null;
                    }
                    int index = store.index().asJavaConstant().asInt();
                    JavaConstant valueConstant = store.value().asJavaConstant();
                    if (!storeClassArrayConstant(b, result, index, valueConstant, annotationSubstitutions)) {
                        return null;
                    }
                }
                successor = unwrapNode(store.next());
            }

            if (successor != null && exact) {
                /* Nodes after the array store could interfere with the array. */
                return null;
            }
            return result;
        }
        return null;
    }

    private static boolean storeClassArrayConstant(GraphBuilderContext b, Class<?>[] result, int index, JavaConstant valueConstant, AnnotationSubstitutionProcessor annotationSubstitutions) {
        if (valueConstant == null || valueConstant.getJavaKind() != JavaKind.Object) {
            return false;
        }
        if (valueConstant.isNull()) {
            result[index] = null;
        } else {
            Class<?> clazz = b.getSnippetReflection().asObject(Class.class, valueConstant);
            if (clazz == null) {
                return false;
            }
            /*
             * It is possible that the returned class is a substitution class, e.g., DynamicHub
             * returned for a Class.class constant. Get the target class of the substitution class.
             */
            result[index] = annotationSubstitutions == null ? clazz : annotationSubstitutions.getTargetClass(clazz);
        }
        return true;
    }

    /**
     * The graph decoding used for inlining before static analysis creates unnecessary block
     * {@link BeginNode}s. Similarly, {@link FullInfopointNode}s are inserted for debugging. We can
     * just ignore them.
     */
    private static FixedNode skipNonInterferingNodes(FixedNode node) {
        FixedNode cur = node;
        while (cur instanceof AbstractBeginNode || cur instanceof FullInfopointNode) {
            cur = ((FixedWithNextNode) cur).next();
        }
        return cur;
    }

    private static ValueNode getDeoptProxyOriginalValue(ValueNode node) {
        ValueNode original = node;
        while (original instanceof DeoptProxyNode) {
            original = ((DeoptProxyNode) original).getOriginalNode();
        }
        return original;
    }

    /**
     * Ignore nodes in the control flow graph that are not important for the Class[] elements
     * analysis.
     */
    private static FixedNode unwrapNode(FixedNode node) {
        FixedNode successor = node;
        while (true) {
            if (successor instanceof EnsureClassInitializedNode) {
                successor = ((EnsureClassInitializedNode) successor).next();
            } else if (successor instanceof FullInfopointNode) {
                successor = ((FullInfopointNode) successor).next();
            } else if (successor instanceof DeoptEntryNode) {
                assert MultiMethod.isDeoptTarget(successor.graph().method());
                successor = ((DeoptEntryNode) successor).next();
            } else if (successor instanceof AbstractBeginNode) {
                /* Useless block begins can occur during parsing or graph decoding. */
                successor = ((AbstractBeginNode) successor).next();
            } else {
                return successor;
            }
        }
    }

    private static void registerAtomicUpdaterPlugins(InvocationPlugins plugins) {
        Registration referenceUpdaterRegistration = new Registration(plugins, AtomicReferenceFieldUpdater.class);
        referenceUpdaterRegistration.register(new RequiredInvocationPlugin("newUpdater", Class.class, Class.class, String.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode tclassNode, ValueNode vclassNode, ValueNode fieldNameNode) {
                interceptUpdaterInvoke(b, tclassNode, fieldNameNode);
                /* Always return false; the call is not replaced. */
                return false;
            }
        });

        Registration integerUpdaterRegistration = new Registration(plugins, AtomicIntegerFieldUpdater.class);
        integerUpdaterRegistration.register(new RequiredInvocationPlugin("newUpdater", Class.class, String.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode tclassNode, ValueNode fieldNameNode) {
                interceptUpdaterInvoke(b, tclassNode, fieldNameNode);
                /* Always return false; the call is not replaced. */
                return false;
            }
        });

        Registration longUpdaterRegistration = new Registration(plugins, AtomicLongFieldUpdater.class);
        longUpdaterRegistration.register(new RequiredInvocationPlugin("newUpdater", Class.class, String.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode tclassNode, ValueNode fieldNameNode) {
                interceptUpdaterInvoke(b, tclassNode, fieldNameNode);
                /* Always return false; the call is not replaced. */
                return false;
            }
        });
    }

    /**
     * Intercept the invoke to newUpdater. If the holder class and field name are constant register
     * them for reflection/unsafe access.
     */
    @SuppressWarnings("unused")
    private static void interceptUpdaterInvoke(GraphBuilderContext b, ValueNode tclassNode, ValueNode fieldNameNode) {
        if (tclassNode.isConstant() && fieldNameNode.isConstant()) {
            Class<?> tclass = b.getSnippetReflection().asObject(Class.class, tclassNode.asJavaConstant());
            String fieldName = b.getSnippetReflection().asObject(String.class, fieldNameNode.asJavaConstant());
            try {
                Field field = tclass.getDeclaredField(fieldName);
                /*
                 * Register the holder class and the field for reflection. This also registers the
                 * field for unsafe access.
                 */
                RuntimeReflection.register(tclass);
                RuntimeReflection.register(field);
            } catch (NoSuchFieldException e) {
                /*
                 * Ignore the exception. If the field does not exist, there will be an error at run
                 * time. That is then the same behavior as on HotSpot. The allocation of the
                 * AtomicReferenceFieldUpdater could be in a never-executed path, in which case, if
                 * we threw the exception during image building, we would wrongly prohibit image
                 * generation.
                 */
            }
        }
    }

    private static void registerObjectPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Object.class);
        r.register(new RequiredInvocationPlugin("clone", Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Object, new SubstrateObjectCloneWithExceptionNode(MacroParams.of(b, targetMethod, object)));
                return true;
            }
        });

        r.register(new RequiredInvocationPlugin("hashCode", Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                ValueNode object = receiver.get();
                b.addPush(JavaKind.Int, SubstrateIdentityHashCodeNode.create(object, b.bci()));
                return true;
            }
        });
    }

    private static void registerUnsafePlugins(InvocationPlugins plugins) {
        registerUnsafePlugins(new Registration(plugins, "sun.misc.Unsafe"), true);
        Registration r = new Registration(plugins, "jdk.internal.misc.Unsafe");
        registerUnsafePlugins(r, false);

        r.register(new RequiredInvocationPlugin("objectFieldOffset", Receiver.class, Class.class, String.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode classNode, ValueNode nameNode) {
                if (classNode.isConstant() && nameNode.isConstant()) {
                    // Emits a null-check for the otherwise unused receiver
                    receiver.get();

                    /* If the class and field name arguments are constant. */
                    Class<?> clazz = b.getSnippetReflection().asObject(Class.class, classNode.asJavaConstant());
                    String fieldName = b.getSnippetReflection().asObject(String.class, nameNode.asJavaConstant());
                    try {
                        Field targetField = clazz.getDeclaredField(fieldName);
                        return processFieldOffset(b, targetField, false);
                    } catch (NoSuchFieldException | LinkageError e) {
                        return false;
                    }
                }
                return false;
            }
        });
        // We intrinsify allocateUninitializedArray instead of the
        // HotSpotIntrinsicCandidate-annotated method allocateUninitializedArray0, because when
        // intrinsifying the latter, the Class argument is never a compile-time constant without
        // method inlining, while allocateUninitializedArray is too large and not inlined before
        // static analysis.
        r.register(new AllocateUninitializedArrayPlugin("allocateUninitializedArray", true));
    }

    private static void registerUnsafePlugins(Registration r, boolean isSunMiscUnsafe) {
        r.register(new RequiredInvocationPlugin("staticFieldOffset", Receiver.class, Field.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode fieldNode) {
                if (fieldNode.isConstant()) {
                    // Emits a null-check for the otherwise unused receiver
                    receiver.get();

                    Field targetField = b.getSnippetReflection().asObject(Field.class, fieldNode.asJavaConstant());
                    return processFieldOffset(b, targetField, isSunMiscUnsafe);
                }
                return false;
            }
        });
        r.register(new RequiredInvocationPlugin("staticFieldBase", Receiver.class, Field.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode fieldNode) {
                if (fieldNode.isConstant()) {
                    // Emits a null-check for the otherwise unused receiver
                    receiver.get();

                    Field targetField = b.getSnippetReflection().asObject(Field.class, fieldNode.asJavaConstant());
                    return processStaticFieldBase(b, targetField, isSunMiscUnsafe);
                }
                return false;

            }
        });
        r.register(new RequiredInvocationPlugin("objectFieldOffset", Receiver.class, Field.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode fieldNode) {
                if (fieldNode.isConstant()) {
                    // Emits a null-check for the otherwise unused receiver
                    receiver.get();

                    Field targetField = b.getSnippetReflection().asObject(Field.class, fieldNode.asJavaConstant());
                    return processFieldOffset(b, targetField, isSunMiscUnsafe);
                }
                return false;
            }
        });
        r.register(new RequiredInvocationPlugin("allocateInstance", Receiver.class, Class.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unsafe, ValueNode clazz) {
                /* Emits a null-check for the otherwise unused receiver. */
                unsafe.get();
                /*
                 * The allocated class must be null-checked already before the class initialization
                 * check.
                 */
                ValueNode clazzNonNull = b.nullCheckedValue(clazz, DeoptimizationAction.None);
                EnsureClassInitializedNode ensureInitialized = b.append(new EnsureClassInitializedNode(clazzNonNull));
                ensureInitialized.setStateAfter(b.getInvocationPluginBeforeState());
                DynamicNewInstanceNode.createAndPush(b, clazzNonNull);
                /* Capture the correct state after these operations. */
                b.add(new StateSplitProxyNode());
                return true;
            }
        });
    }

    private static boolean processFieldOffset(GraphBuilderContext b, Field targetField, boolean isSunMiscUnsafe) {
        if (!isValidField(targetField, isSunMiscUnsafe)) {
            return false;
        }

        /*
         * The static analysis registers the field for unsafe access if the node remains in the
         * graph until then.
         */
        b.addPush(JavaKind.Long, FieldOffsetNode.create(JavaKind.Long, b.getMetaAccess().lookupJavaField(targetField)));
        return true;
    }

    private static boolean isValidField(Field targetField, boolean isSunMiscUnsafe) {
        if (targetField == null) {
            /* A NullPointerException will be thrown at run time for this call. */
            return false;
        }
        if (isSunMiscUnsafe && (targetField.getDeclaringClass().isRecord() || targetField.getDeclaringClass().isHidden())) {
            /*
             * sun.misc.Unsafe performs a few more checks than jdk.internal.misc.Unsafe to
             * explicitly disallow hidden classes and records.
             */
            return false;
        }
        return true;
    }

    private static boolean processStaticFieldBase(GraphBuilderContext b, Field targetField, boolean isSunMiscUnsafe) {
        if (!isValidField(targetField, isSunMiscUnsafe)) {
            return false;
        }

        b.addPush(JavaKind.Object, StaticFieldsSupport.createStaticFieldBaseNode(targetField.getType().isPrimitive()));
        return true;
    }

    @SuppressWarnings("unused")
    private static void registerArrayPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Array.class).setAllowOverwrite(true);
        r.register(new RequiredInvocationPlugin("newInstance", Class.class, int[].class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode clazzNode, ValueNode dimensionsNode) {
                /*
                 * There is no Graal node for dynamic multi array allocation, and it is also not
                 * necessary for performance reasons. But when the arguments are constant, we can
                 * register the array types as instantiated so that the allocation succeeds at run
                 * time without manual registration.
                 */
                ValueNode dimensionCountNode = GraphUtil.arrayLength(dimensionsNode, ArrayLengthProvider.FindLengthMode.SEARCH_ONLY, b.getConstantReflection());
                if (clazzNode.isConstant() && !clazzNode.isNullConstant() && dimensionCountNode != null && dimensionCountNode.isConstant()) {
                    Class<?> clazz = b.getSnippetReflection().asObject(Class.class, clazzNode.asJavaConstant());
                    int dimensionCount = dimensionCountNode.asJavaConstant().asInt();

                    AnalysisType type = (AnalysisType) b.getMetaAccess().lookupJavaType(clazz);
                    for (int i = 0; i < dimensionCount; i++) {
                        type = type.getArrayClass();
                        type.registerAsAllocated(AbstractAnalysisEngine.sourcePosition(clazzNode));
                    }
                }
                return false;
            }
        });
    }

    private static void registerKnownIntrinsicsPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, KnownIntrinsics.class);
        r.register(new RequiredInvocationPlugin("heapBase") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, ReadReservedRegister.createReadHeapBaseNode(b.getGraph()));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("readHub", Object.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                ValueNode nonNullObject = b.nullCheckedValue(object);
                b.addPush(JavaKind.Object, new LoadHubNode(b.getStampProvider(), nonNullObject));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("nonNullPointer", Pointer.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object) {
                b.addPush(JavaKind.Object, new PiNode(object, nonZeroWord()));
                return true;
            }
        });

        r.register(new RequiredInvocationPlugin("readStackPointer") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Object, ReadReservedRegister.createReadStackPointerNode(b.getGraph()));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("readCallerStackPointer") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                checkNeverInline(b);
                b.addPush(JavaKind.Object, new ReadCallerStackPointerNode());
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("readReturnAddress") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                checkNeverInline(b);
                b.addPush(JavaKind.Object, new ReadReturnAddressNode());
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("farReturn", Object.class, Pointer.class, CodePointer.class, boolean.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode result, ValueNode sp, ValueNode ip,
                            ValueNode fromMethodWithCalleeSavedRegisters) {

                if (!fromMethodWithCalleeSavedRegisters.isConstant()) {
                    throw b.bailout("parameter fromMethodWithCalleeSavedRegisters is not a compile time constant for call to " +
                                    targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
                }
                b.add(new FarReturnNode(result, sp, ip, fromMethodWithCalleeSavedRegisters.asJavaConstant().asInt() != 0));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("testDeoptimize") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.add(new TestDeoptimizeNode());
                return true;
            }
        });

        r.register(new RequiredInvocationPlugin("isDeoptimizationTarget") {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(MultiMethod.isDeoptTarget(b.getGraph().method())));
                return true;
            }
        });

        registerCastExact(r);
    }

    public static void registerCastExact(Registration r) {
        r.register(new RequiredInvocationPlugin("castExact", Object.class, Class.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode object, ValueNode javaClass) {
                ValueNode nullCheckedClass = b.nullCheckedValue(javaClass);
                LogicNode condition = b.append(InstanceOfDynamicNode.create(b.getAssumptions(), b.getConstantReflection(), nullCheckedClass, object, true, true));
                AbstractBeginNode guard = b.emitBytecodeExceptionCheck(condition, true, BytecodeExceptionNode.BytecodeExceptionKind.CLASS_CAST, object, nullCheckedClass);
                if (guard != null) {
                    b.addPush(JavaKind.Object, DynamicPiNode.create(b.getAssumptions(), b.getConstantReflection(), object, guard, nullCheckedClass, true, true));
                } else {
                    b.addPush(JavaKind.Object, object);
                }
                return true;
            }
        });
    }

    private static void checkNeverInline(GraphBuilderContext b) {
        if (!AnnotationAccess.isAnnotationPresent(b.getMethod(), NeverInline.class)) {
            throw VMError.shouldNotReachHere("Accessing the stack pointer or instruction pointer of the caller frame is only safe and deterministic if the method is not inlined. " +
                            "Therefore, the method " + b.getMethod().format("%H.%n(%p)") + " must be annotated with @" + NeverInline.class.getSimpleName());
        }
    }

    private static IntegerStamp nonZeroWord() {
        return StampFactory.forUnsignedInteger(64, 1, 0xffffffffffffffffL);
    }

    private static void registerStackValuePlugins(InvocationPlugins plugins) {
        registerStackValuePlugins(new Registration(plugins, StackValue.class), true);
        registerStackValuePlugins(new Registration(plugins, UnsafeStackValue.class), false);

        Registration unsafeLateStackValue = new Registration(plugins, UnsafeLateStackValue.class);
        unsafeLateStackValue.register(new RequiredInvocationPlugin("get", int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode sizeNode) {
                b.addPush(JavaKind.Object, LateStackValueNode.create(sizeNode, b.getGraph().method(), b.bci(), false));
                return true;
            }
        });
    }

    private static void registerStackValuePlugins(Registration r, boolean disallowVirtualThread) {
        r.register(new RequiredInvocationPlugin("get", int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode sizeNode) {
                long size = longValue(b, targetMethod, sizeNode, "size");
                b.addPush(JavaKind.Object, StackValueNode.create(1, size, b, disallowVirtualThread));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("get", Class.class) {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<? extends PointerBase> clazz = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                int size = SizeOf.get(clazz);
                b.addPush(JavaKind.Object, StackValueNode.create(1, size, b, disallowVirtualThread));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("get", int.class, int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode numElementsNode, ValueNode elementSizeNode) {
                long numElements = longValue(b, targetMethod, numElementsNode, "numElements");
                long elementSize = longValue(b, targetMethod, elementSizeNode, "elementSize");
                b.addPush(JavaKind.Object, StackValueNode.create(numElements, elementSize, b, disallowVirtualThread));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("get", int.class, Class.class) {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode numElementsNode, ValueNode classNode) {
                long numElements = longValue(b, targetMethod, numElementsNode, "numElements");
                Class<? extends PointerBase> clazz = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                int size = SizeOf.get(clazz);
                b.addPush(JavaKind.Object, StackValueNode.create(numElements, size, b, disallowVirtualThread));
                return true;
            }
        });
    }

    private static void registerClassPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Class.class);
        /*
         * The field DynamicHub.name cannot be final, so we ensure early constant folding using an
         * invocation plugin.
         */
        r.register(new InvocationPlugin.InlineOnlyInvocationPlugin("getName", Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                JavaConstant constantReceiver = receiver.get(false).asJavaConstant();
                if (constantReceiver != null) {
                    receiver.requireNonNull();
                    ResolvedJavaType type = b.getConstantReflection().asJavaType(constantReceiver);
                    if (type != null) {
                        /*
                         * Class names must be interned according to the Java specification. This
                         * also ensures we get the same String instance that is stored in
                         * DynamicHub.name without having a dependency on DynamicHub.
                         */
                        String className = type.toClassName().intern();
                        b.addPush(JavaKind.Object, ConstantNode.forConstant(b.getConstantReflection().forString(className), b.getMetaAccess()));
                        return true;
                    }
                }
                return false;
            }
        });

        registerClassDesiredAssertionStatusPlugin(plugins);
    }

    public static void registerClassDesiredAssertionStatusPlugin(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Class.class);
        r.register(new RequiredInvocationPlugin("desiredAssertionStatus", Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                JavaConstant constantReceiver = receiver.get().asJavaConstant();
                if (constantReceiver != null && constantReceiver.isNonNull()) {
                    Object clazzOrHub = b.getSnippetReflection().asObject(Object.class, constantReceiver);
                    boolean desiredAssertionStatus;
                    if (clazzOrHub instanceof Class) {
                        desiredAssertionStatus = RuntimeAssertionsSupport.singleton().desiredAssertionStatus((Class<?>) clazzOrHub);
                    } else if (clazzOrHub instanceof DynamicHub) {
                        desiredAssertionStatus = ((DynamicHub) clazzOrHub).desiredAssertionStatus();
                    } else {
                        throw VMError.shouldNotReachHere("Unexpected class object: " + clazzOrHub);
                    }
                    b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(desiredAssertionStatus));
                    return true;
                }
                return false;
            }
        });
    }

    private static void registerEdgesPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Edges.class).setAllowOverwrite(true);
        for (Class<?> c : new Class<?>[]{Node.class, NodeList.class}) {
            r.register(new RequiredInvocationPlugin("get" + ClassUtil.getUnqualifiedName(c) + "Unsafe", Node.class, long.class) {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode node, ValueNode offset) {
                    b.addPush(JavaKind.Object, new UnsafePartitionLoadNode(node, offset, JavaKind.Object, LocationIdentity.any(), GraalEdgeUnsafePartition.get(), b.getMetaAccess().lookupJavaType(c)));
                    return true;
                }
            });

            r.register(new RequiredInvocationPlugin("put" + ClassUtil.getUnqualifiedName(c) + "Unsafe", Node.class, long.class, c) {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode node, ValueNode offset, ValueNode value) {
                    b.add(new UnsafePartitionStoreNode(node, offset, value, JavaKind.Object, LocationIdentity.any(), GraalEdgeUnsafePartition.get(), b.getMetaAccess().lookupJavaType(c)));
                    return true;
                }
            });
        }
    }

    protected static long longValue(GraphBuilderContext b, ResolvedJavaMethod targetMethod, ValueNode node, String name) {
        if (!node.isConstant()) {
            throw b.bailout("parameter " + name + " is not a compile time constant for call to " + targetMethod.format("%H.%n(%p)") + " in " + b.getMethod().asStackTraceElement(b.bci()));
        }
        return node.asJavaConstant().asLong();
    }

    private static void registerVMConfigurationPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, ImageSingletons.class);
        r.register(new RequiredInvocationPlugin("contains", Class.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<?> key = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                boolean result = ImageSingletons.contains(key);
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(result));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("lookup", Class.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<?> key = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                Object result = ImageSingletons.lookup(key);
                b.addPush(JavaKind.Object, ConstantNode.forConstant(b.getSnippetReflection().forObject(result), b.getMetaAccess()));
                return true;
            }
        });
    }

    private static void registerPlatformPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, Platform.class);
        r.register(new RequiredInvocationPlugin("includedIn", Class.class) {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode classNode) {
                Class<? extends Platform> platform = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                boolean result = Platform.includedIn(platform);
                b.addPush(JavaKind.Boolean, ConstantNode.forBoolean(result));
                return true;
            }
        });
    }

    private static void registerSizeOfPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, SizeOf.class);
        r.register(new RequiredInvocationPlugin("get", Class.class) {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<? extends PointerBase> clazz = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                int result = SizeOf.get(clazz);
                b.addPush(JavaKind.Int, ConstantNode.forInt(result));
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("unsigned", Class.class) {
            @SuppressWarnings("unchecked")
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unused, ValueNode classNode) {
                Class<? extends PointerBase> clazz = constantObjectParameter(b, targetMethod, 0, Class.class, classNode);
                UnsignedWord result = SizeOf.unsigned(clazz);
                b.addPush(JavaKind.Object, ConstantNode.forConstant(b.getSnippetReflection().forObject(result), b.getMetaAccess()));
                return true;
            }
        });
    }

    private static void registerReferencePlugins(InvocationPlugins plugins, ParsingReason parsingReason) {
        Registration r = new Registration(plugins, Reference.class);
        r.register(new ReachabilityFencePlugin() {
            @Override
            protected boolean useExplicitReachabilityFence(GraphBuilderContext b) {
                return parsingReason != ParsingReason.JITCompilation;
            }
        });
    }

    private static void registerReferenceAccessPlugins(InvocationPlugins plugins) {
        Registration r = new Registration(plugins, ReferenceAccessImpl.class);
        r.register(new RequiredInvocationPlugin("getCompressedRepresentation", Receiver.class, Object.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode objectNode) {
                receiver.requireNonNull();
                if (ReferenceAccess.singleton().haveCompressedReferences()) {
                    ValueNode compressedObj = SubstrateCompressionNode.compress(b.getGraph(), objectNode, ImageSingletons.lookup(CompressEncoding.class));
                    JavaKind compressedIntKind = JavaKind.fromWordSize(ConfigurationValues.getObjectLayout().getReferenceSize());
                    ValueNode compressedValue = b.add(WordCastNode.narrowOopToUntrackedWord(compressedObj, compressedIntKind));
                    b.addPush(JavaKind.Object, ZeroExtendNode.convertUnsigned(compressedValue, FrameAccess.getWordStamp(), NodeView.DEFAULT));
                } else {
                    b.addPush(JavaKind.Object, WordCastNode.objectToUntrackedPointer(objectNode, ConfigurationValues.getWordKind()));
                }
                return true;
            }
        });
        r.register(new RequiredInvocationPlugin("uncompressReference", Receiver.class, UnsignedWord.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode wordNode) {
                receiver.requireNonNull();
                if (ReferenceAccess.singleton().haveCompressedReferences()) {
                    CompressEncoding encoding = ImageSingletons.lookup(CompressEncoding.class);
                    JavaKind compressedIntKind = JavaKind.fromWordSize(ConfigurationValues.getObjectLayout().getReferenceSize());
                    NarrowOopStamp compressedStamp = (NarrowOopStamp) SubstrateNarrowOopStamp.compressed((AbstractObjectStamp) StampFactory.object(), encoding);
                    ValueNode narrowNode = b.add(NarrowNode.convertUnsigned(wordNode, StampFactory.forKind(compressedIntKind), NodeView.DEFAULT));
                    WordCastNode compressedObj = b.add(WordCastNode.wordToNarrowObject(narrowNode, compressedStamp));
                    b.addPush(JavaKind.Object, SubstrateCompressionNode.uncompress(b.getGraph(), compressedObj, encoding));
                } else {
                    b.addPush(JavaKind.Object, WordCastNode.wordToObject(wordNode, ConfigurationValues.getWordKind()));
                }
                return true;
            }
        });
    }

    private static void registerArraysSupportPlugins(InvocationPlugins plugins, Replacements replacements, Architecture arch) {
        InvocationPlugins.Registration r = new InvocationPlugins.Registration(plugins, "jdk.internal.util.ArraysSupport", replacements);
        r.registerConditional(VectorizedMismatchNode.isSupported(arch), new InvocationPlugin("vectorizedMismatch", Object.class, long.class, Object.class, long.class, int.class, int.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver,
                            ValueNode aObject, ValueNode aOffset, ValueNode bObject, ValueNode bOffset, ValueNode length, ValueNode log2ArrayIndexScale) {
                ValueNode aAddr = b.add(new ComputeObjectAddressNode(aObject, aOffset));
                ValueNode bAddr = b.add(new ComputeObjectAddressNode(bObject, bOffset));
                b.addPush(JavaKind.Int, new VectorizedMismatchNode(aAddr, bAddr, length, log2ArrayIndexScale));
                return true;
            }
        });
        r.registerConditional(VectorizedHashCodeNode.isSupported(arch),
                        new StandardGraphBuilderPlugins.VectorizedHashCodeInvocationPlugin("vectorizedHashCode"));
    }

    private static class SubstrateCipherBlockChainingCryptPlugin extends StandardGraphBuilderPlugins.CipherBlockChainingCryptPlugin {

        SubstrateCipherBlockChainingCryptPlugin(AESNode.CryptMode mode) {
            super(mode);
        }

        @Override
        protected boolean canApply(GraphBuilderContext b) {
            return b instanceof BytecodeParser;
        }

        @Override
        protected ResolvedJavaType getTypeAESCrypt(MetaAccessProvider metaAccess, ResolvedJavaType context) throws ClassNotFoundException {
            Class<?> classAESCrypt = Class.forName("com.sun.crypto.provider.AESCrypt", true, ClassLoader.getSystemClassLoader());
            return metaAccess.lookupJavaType(classAESCrypt);
        }
    }

    @SuppressWarnings("unused")
    private static Object nonNullReason(NodeSourcePosition position) {
        return position == null ? "Unknown graph builder location." : position;
    }

    private static void registerAESPlugins(InvocationPlugins plugins, Replacements replacements, Architecture arch) {
        Registration r = new Registration(plugins, "com.sun.crypto.provider.CounterMode", replacements);
        r.registerConditional(CounterModeAESNode.isSupported(arch), new CounterModeCryptPlugin() {
            @Override
            protected boolean canApply(GraphBuilderContext b) {
                return b instanceof BytecodeParser;
            }

            @Override
            protected ValueNode getFieldOffset(GraphBuilderContext b, ResolvedJavaField field) {
                return FieldOffsetNode.create(JavaKind.Long, field);
            }

            @Override
            protected ResolvedJavaType getTypeAESCrypt(MetaAccessProvider metaAccess, ResolvedJavaType context) throws ClassNotFoundException {
                Class<?> classAESCrypt = Class.forName("com.sun.crypto.provider.AESCrypt", true, ClassLoader.getSystemClassLoader());
                return metaAccess.lookupJavaType(classAESCrypt);
            }
        });

        r = new Registration(plugins, "com.sun.crypto.provider.CipherBlockChaining", replacements);
        r.registerConditional(CipherBlockChainingAESNode.isSupported(arch), new SubstrateCipherBlockChainingCryptPlugin(AESNode.CryptMode.ENCRYPT));
        r.registerConditional(CipherBlockChainingAESNode.isSupported(arch), new SubstrateCipherBlockChainingCryptPlugin(AESNode.CryptMode.DECRYPT));
    }

    private static <T> T constantObjectParameter(GraphBuilderContext b, ResolvedJavaMethod targetMethod, int parameterIndex, Class<T> declaredType, ValueNode classNode) {
        checkParameterUsage(classNode.isConstant(), b, targetMethod, parameterIndex, "parameter is not a compile time constant");
        T result = b.getSnippetReflection().asObject(declaredType, classNode.asJavaConstant());
        checkParameterUsage(result != null, b, targetMethod, parameterIndex, "parameter is null");
        return result;
    }

    public static void checkParameterUsage(boolean condition, GraphBuilderContext b, ResolvedJavaMethod targetMethod, int parameterIndex, String message) {
        if (condition) {
            return;
        }

        String parameterName = null;
        LocalVariableTable variableTable = targetMethod.getLocalVariableTable();
        if (variableTable != null) {
            Local variable = variableTable.getLocal(parameterIndex, 0);
            if (variable != null) {
                parameterName = variable.getName();
            }
        }
        if (parameterName == null) {
            /* Fall back to parameter number if no name is available. */
            parameterName = String.valueOf(parameterIndex);
        }

        throw UserError.abort("%s: parameter %s of call to %s in %s", message, parameterName, targetMethod, b.getMethod().asStackTraceElement(b.bci()));
    }
}

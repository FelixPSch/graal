/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.nodes;

import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeCycles;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodeinfo.NodeSize;
import jdk.graal.compiler.nodes.calc.FloatingNode;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;

import com.oracle.svm.core.FrameAccess;

import jdk.vm.ci.code.Register;

@NodeInfo(cycles = NodeCycles.CYCLES_0, size = NodeSize.SIZE_0)
public final class ReadReservedRegisterFloatingNode extends FloatingNode implements LIRLowerable {
    public static final NodeClass<ReadReservedRegisterFloatingNode> TYPE = NodeClass.create(ReadReservedRegisterFloatingNode.class);

    private final Register register;

    protected ReadReservedRegisterFloatingNode(Register register) {
        super(TYPE, FrameAccess.getWordStamp());
        this.register = register;
    }

    public Register getRegister() {
        return register;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        LIRKind lirKind = gen.getLIRGeneratorTool().getLIRKind(stamp);
        gen.setResult(this, register.asValue(lirKind));
    }
}

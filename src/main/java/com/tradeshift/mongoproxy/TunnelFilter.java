/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.tradeshift.mongoproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteOrder;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.SocketConnectorHandler;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;

/**
 * Simple tunneling filter, which maps input of one connection to the output of
 * another and vise versa.
 *
 * @author Alexey Stashok
 */
public class TunnelFilter extends BaseFilter {
    private Attribute<Connection> peerConnectionAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute("TunnelFilter.peerConnection");

    // Transport, which will be used to create peer connection
    private final SocketConnectorHandler transport;

    // Destination address for peer connections
    private final SocketAddress redirectAddress;

    public TunnelFilter(SocketConnectorHandler transport, String host, int port) {
        this(transport, new InetSocketAddress(host, port));
    }

    public TunnelFilter(SocketConnectorHandler transport, SocketAddress redirectAddress) {
        this.transport = transport;
        this.redirectAddress = redirectAddress;
    }
    
    /**
     * This method will be called, once {@link Connection} has some available data
     */
    @Override
    public NextAction handleRead(final FilterChainContext ctx)
            throws IOException {
        final Connection connection = ctx.getConnection();
        final Connection peerConnection = peerConnectionAttribute.get(connection);

        // if connection is closed - stop the execution
        if (!connection.isOpen()) {
            return ctx.getStopAction();
        }

        // if peerConnection wasn't created - create it (usually happens on first connection request)
        if (peerConnection == null) {
            // "Peer connect" phase could take some time - so execute it in non-blocking mode

            // Connect peer connection and register completion handler
            transport.connect(redirectAddress, new ConnectCompletionHandler(ctx));

            // return suspend status
            return ctx.getSuspendAction();
        }

        Buffer msg = ctx.getMessage();
        int length = msg.remaining();
        if (length < 16) {
        	return ctx.getStopAction(msg);
        }
        msg.mark();
        msg.order(ByteOrder.LITTLE_ENDIAN);
        int msgLength = msg.getInt();
        int requestId = msg.getInt();
        int responseTo = msg.getInt();
        int opCode = msg.getInt();
        msg.reset();
//        System.out.println("Opcode: " + opCode);
        if (opCode == 2004) {
//        	ctx.fail(new Exception());
        }
        
        if (length < msgLength) {
        	return ctx.getStopAction(msg);
        }
        final Buffer remainder = length > msgLength ?  msg.split(msgLength) : null;
        
        // if peer connection is already created - just forward data to peer
        redirectToPeer(ctx, peerConnection);
        msg.tryDispose();

        return ctx.getInvokeAction(remainder);
    }

    /**
     * This method will be called, to notify about {@link Connection} closing.
     */
    @Override
    public NextAction handleClose(FilterChainContext ctx) throws IOException {
        final Connection connection = ctx.getConnection();
        final Connection peerConnection = peerConnectionAttribute.get(connection);

        // Close peer connection as well, if it wasn't closed before
        if (peerConnection != null && peerConnection.isOpen()) {
            peerConnection.close();
        }

        return ctx.getInvokeAction();
    }

    /**
     * Redirect data from {@link Connection} to its peer.
     *
     * @param context {@link FilterChainContext}
     * @param peerConnection peer {@link Connection}
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    private static void redirectToPeer(final FilterChainContext context,
            final Connection peerConnection) throws IOException {

        final Connection srcConnection = context.getConnection();
        final Object message = context.getMessage();
        peerConnection.write(message);
    }
    
    /**
     * Peer connect {@link CompletionHandler}
     */
    private class ConnectCompletionHandler implements CompletionHandler<Connection> {
        private final FilterChainContext context;
        
        private ConnectCompletionHandler(FilterChainContext context) {
            this.context = context;
        }

        @Override
        public void cancelled() {
            close(context.getConnection());
            resumeContext();
        }

        @Override
        public void failed(Throwable throwable) {
            close(context.getConnection());
            resumeContext();
        }

        /**
         * If peer was successfully connected - map both connections to each other.
         */
        @Override
        public void completed(Connection peerConnection) {
            final Connection connection = context.getConnection();

            // Map connections
            peerConnectionAttribute.set(connection, peerConnection);
            peerConnectionAttribute.set(peerConnection, connection);

            // Resume filter chain execution
            resumeContext();
        }

        @Override
        public void updated(Connection peerConnection) {
        }

        /**
         * Resume {@link FilterChain} execution on stage, where it was
         * earlier suspended.
         */
        private void resumeContext() {
            context.resume();
        }

        /**
         * Close the {@link Connection}
         * @param connection {@link Connection}
         */
        private void close(Connection connection) {
            try {
                connection.close();
            } catch (IOException e) {
            }
        }
    }
}

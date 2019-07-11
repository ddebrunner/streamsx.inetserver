/*
 * Copyright 2019 IBM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.streamsx.inet.wsserver.servlet;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import com.ibm.json.java.JSONObject;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.metrics.Metric;

/**
 * The common Socket class for websocket operators
 * This class handles the common websocket events and maintains the
 * logical connection the operators with the internal attributes:
 * operatorContext, sessionsConnected, nClientsConnected
 * The sessionsConnected map maps the immutable session key to the 
 * mutable Session object.
 */
public class EventSocket extends WebSocketAdapter {

	public static final String N_CLIENTS_CONNRCTED_DESCR = 
			"Number of clients currently connected to the operators WebSocket context.";

	private static final Logger trace = Logger.getLogger(EventSocket.class.getName());
	
	private static final AtomicLong sessionKeyGenerator = new AtomicLong();
	
	protected final OperatorContext operatorContext;
	private final Map<Long, Session> sessionsConnected;
	private final Metric nClientsConnected;
	private final boolean enableConnectionControlMessages;

	public EventSocket(OperatorContext operatorContext, EventSocketConduit webSocketConduit) {
		super();
		this.operatorContext = operatorContext;
		this.sessionsConnected = webSocketConduit.sessionsConnected;
		this.nClientsConnected = webSocketConduit.nClientsConnected;
		this.enableConnectionControlMessages = webSocketConduit.enableConnectionControlMessages;
		if (trace.isEnabledFor(TraceLevel.INFO)) trace.info(operatorContext.getLogicalName() + ": EventSocket(...)");
	}

	@Override
	public void onWebSocketConnect(Session sess) {
		super.onWebSocketConnect(sess); //set session and remote -> problem with WebSocketAdapter concept if more than on connection
		boolean isSessionEqual = sess == getSession();
		boolean isRemoteEqual = sess.getRemote() == getRemote();
		if (trace.isEnabledFor(TraceLevel.INFO))
			trace.info(operatorContext.getLogicalName() + ": onWebSocketConnect - session remote: " + getRemoteId(sess) + 
					" remote: " + getRemote().getInetSocketAddress().getHostString() + ":" + getRemote().getInetSocketAddress().getPort() +
					" sessionEqual: " + isSessionEqual + " remoteEqual: " + isRemoteEqual);
		
		Long sessionKey = sessionKeyGenerator.incrementAndGet();
		Session previousSession = sessionsConnected.put(sessionKey, sess);
		if (previousSession != null) {
			trace.error(operatorContext.getLogicalName() + ": onWebSocketConnect - duplicate session key: " + sessionKey.toString() + " remote:" + getRemoteId(sess));
		}
		nClientsConnected.increment();
		if (enableConnectionControlMessages) statusToAll( "OPEN",  "R:" + getRemoteId(sess) + " L:" + sess.getLocalAddress());
	}

	@Override
	public synchronized void onWebSocketClose(int statusCode, String reason) {
		if (trace.isEnabledFor(TraceLevel.INFO))
			trace.info(operatorContext.getLogicalName() + ": onWebSocketClose - " + " statusCode: " + statusCode + 
					" reason: " + reason);
		
		Set<Long> keyset = sessionsConnected.keySet();
		HashSet<Long> keyToDelete = new HashSet<Long>();
		for (Long k : keyset) {
			Session sess = sessionsConnected.get(k);
			if (sess.isOpen()) {
				if (trace.isEnabledFor(TraceLevel.DEBUG))
					trace.debug("Session is open: " + getRemoteId(sess));
			} else {
				if (trace.isEnabledFor(TraceLevel.INFO))
					trace.info("Session is closed: " + getRemoteId(sess));
				keyToDelete.add(k);
			}
		}
		for (Long k : keyToDelete) {
			Session previousSession = sessionsConnected.remove(k);
			if (previousSession != null) {
				if (enableConnectionControlMessages)
					statusToAll( "CLOSE",  "R:" + getRemoteId(previousSession) + " L:" + previousSession.getLocalAddress());
			} else {
				trace.error(operatorContext.getLogicalName() + ": onWebSocketClose - can not remove session key: " + k.toString());
			}
		}
		
		nClientsConnected.incrementValue(-1);
		super.onWebSocketClose(statusCode,reason); //set session and remote to null
	}

	@Override
	public void onWebSocketError(Throwable cause) {
		super.onWebSocketError(cause); //? do nothing
		trace.error(operatorContext.getLogicalName() + ": onWebSocketError - " + cause.getMessage(), cause);
	}

	@Override
	public void onWebSocketText(String message) {
		super.onWebSocketText(message); //? do nothing
		if (trace.isEnabledFor(TraceLevel.INFO)) trace.info(operatorContext.getLogicalName() + ": onWebSocketText - length: " + message.length());
	}
	
	@Override
	public void onWebSocketBinary(byte[] payload, int offset, int len) {
		super.onWebSocketBinary(payload, offset, len); //? do nothing
		if (trace.isEnabledFor(TraceLevel.INFO)) trace.info(operatorContext.getLogicalName() + ": onWebSocketBinary - length: " + (len - offset));
	}

	public String getRemoteId(Session sess) {
		String res = "null";
		if (sess != null) {
			res = sess.getRemote().getInetSocketAddress().getHostString() + ":" + sess.getRemote().getInetSocketAddress().getPort() + " " + sess.getRemote().getBatchMode().name();
		}
		return res;
	}

	/**
	 * Send a status/control message to all, since we have control and data on the same 
	 * interface need a consistent way to send such a message. 
	 * @param status
	 * @param text
	 */
	protected void statusToAll(String status, String text) {
		JSONObject controlMessage = new JSONObject();
		JSONObject controlBody = new JSONObject();
		controlBody.put("status", status);
		controlBody.put("value", text);
		controlMessage.put("control", controlBody);
		sendToAll(controlMessage);
	}

	/**
	 * Sends <var>jsonMessage</var> to all currently connected WebSocket clients.
	 * 
	 * @param jsonMessage to transmit 
	 * 
	 * @return number of messages sent, thus the number of active connections.
	 */
	public int sendToAll( JSONObject jsonMessage ) {
		String message = null;
		int cnt = 0;
		try {
			message = jsonMessage.serialize();
			if (trace.isEnabledFor(TraceLevel.TRACE)) trace.log(TraceLevel.TRACE, operatorContext.getLogicalName() +  ": sendToAll() : " + message);
			if (message != null) {
				synchronized(sessionsConnected) {
					Collection<Session> sessions = sessionsConnected.values();
					for( Session session : sessions ) {
						if (session.isOpen()) {
							if (trace.isEnabledFor(TraceLevel.TRACE)) trace.log(TraceLevel.TRACE, operatorContext.getLogicalName() +  ": sendToAll(): " + getRemoteId(session));
							session.getRemote().sendString(message);
						} else {
							trace.error(operatorContext.getLogicalName() +  ": sendToAll(): " + getRemoteId(session) + " session aready closed");
						}
					}
				}
			}
			return cnt;
		} catch (Exception e) { //catch all exceptions to avoid errors with concurrently closed sessions
			trace.error(operatorContext.getLogicalName() + ": sendToAll(): " + jsonMessage.toString()
			+ " err: " + e.getClass().getName() + " " + e.getMessage(), e);
		}
		return cnt;
	}

}

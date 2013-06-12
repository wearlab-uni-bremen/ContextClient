/*
   Copyright 2007-2013 Hendrik Iben, University Bremen

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.tzi.context.client;

import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.tzi.context.common.Protocol;
import org.tzi.context.common.Protocol.WriteMessageResult;
import org.tzi.context.common.UniqueIdProvider;
import org.tzi.context.common.Util;



public class ContextClient {

	final static boolean debug = false;

	public enum CommunicationState { Disconnected, Connecting, Connected, Failure };
	
	public static final String failureUnknownHost = "Unknown Host ";
	public static final String failureConnection = "Unable to connect to ";
	
	private CommunicationThread commThread;
	
	private Queue<String> commandQueue = new LinkedList<String>();
	
	private List<ContextClientListener> ccll = new CopyOnWriteArrayList<ContextClientListener>();
	
	private static class TransferRepresentation { 
		private byte [] txToServerData;
		private byte [] txBuffer = new byte [Protocol.maxDataSize];
		private int lastAck = 0;
		private ByteArrayOutputStream txFromServerBuffer;
		private int txFromServerSize;

		private boolean txToServer = false;
		private boolean txFromServer = false;
		private int txFromServerNeededPacket;
		private String txForCommand = null;
		
		public int getPacketData(int n, byte [] buffer, int offs) {
			int data_offs = Protocol.txMaxData * n;
			if(data_offs >= txToServerData.length)
				return 0;
			
			int len = Protocol.txMaxData;
			if((data_offs + len) > txToServerData.length) {
				len = txToServerData.length - data_offs;
			}
			
			System.arraycopy(txToServerData, data_offs, buffer, offs, len);
			
			return len;
		}
	}
	
	private Map<Integer, TransferRepresentation> transferMap = new TreeMap<Integer, TransferRepresentation>();
	
	
	private Charset asciiCharset = Charset.forName("ASCII");
	
	public int getNumTXFromServer() {
		int num = 0;
		synchronized(transferMap) {
			for(TransferRepresentation tr : transferMap.values()) {
				if(tr.txFromServer) {
					num++;
				}
			}
		}
		
		return num;
	}
	
	public int getNumTXToServer() {
		int num = 0;
		synchronized(transferMap) {
			for(TransferRepresentation tr : transferMap.values()) {
				if(tr.txToServer) {
					num++;
				}
			}
		}
		
		return num;
	}
	
	public int [] getTXFromServerStates(int [] into) {
		if(into == null || into.length<2) {
			into = new int [2];
		}
		into[0] = 0;
		into[1] = 0;
		synchronized(transferMap) {
			int index = 2;
			for(TransferRepresentation tr : transferMap.values()) {
				if(tr.txFromServer) {
					if(into.length>index) {
						into[index] = tr.txFromServerSize;
						index++;
					}
					if(into.length>index) {
						into[index] = tr.txFromServerBuffer.size();
						index++;
					}
					into[0] += tr.txFromServerSize;
					into[1] += tr.txFromServerBuffer.size();
				}
			}
		}
		
		return into;
	}
	
	public int [] getTXToServerStates(int [] into) {
		if(into == null || into.length<2) {
			into = new int [2];
		}
		into[0] = 0;
		into[1] = 0;
		synchronized(transferMap) {
			int index = 2;
			for(TransferRepresentation tr : transferMap.values()) {
				if(tr.txToServer) {
					int s = tr.lastAck * Protocol.maxDataSize;
					if(s > tr.txToServerData.length)
						s = tr.txToServerData.length;

					if(into.length>index) {
						into[index] = tr.txToServerData.length;
						index++;
					}
					if(into.length>index) {
						into[index] = s; 
						index++;
					}
					into[0] += tr.txToServerData.length;
					into[1] += s;
				}
			}
		}
		
		return into;
	}

	public boolean processTX(String txMessage) {
		String [] words = Util.splitWS(txMessage);
		TransferRepresentation tr;
		
		if(words.length<3) {
			if(debug)
				System.err.println("Invalid TXMessage: " + txMessage);
			return false;
		}
		
		int tid;
		int packet;
		
		try {
			tid = Integer.parseInt(words[1]);
			packet = Integer.parseInt(words[2]);
			
			if(packet<0)
				throw new NumberFormatException();
			
		} catch(NumberFormatException nfe) {
			if(debug)
				System.err.println("Invalid packet in TXMessage: " + txMessage);

			return false;
		}

		synchronized(transferMap) {
			tr = transferMap.get(tid);
		}
		
		if(debug)
			System.out.println("Processing transfer for ID " + tid + ", packet " + packet + ", exist ? " + (tr == null ? "no" : "yes") );

		int dataIndex = 3;
		boolean isPacket; 
		
		if((isPacket = words[0].equalsIgnoreCase(Protocol.TXPACKET)) || words[0].equalsIgnoreCase(Protocol.TXCTX)) {
			
			if(packet==0) {
				int size;
				
				try {
					if(words.length<5) {
						if(debug)
							System.err.println("Invalid initial TX: " + txMessage);
						return isPacket;
					}
					
					size = Integer.parseInt(words[3]);
					
					if(size < 1)
						throw new NumberFormatException();
				} catch(NumberFormatException nfe) {
					if(debug)
						System.err.println("Invalid size in initial TX: " + txMessage);
					return isPacket;
				}
				
				dataIndex = 4;
				
				if(tr==null) {
					tr = new TransferRepresentation();
					synchronized(transferMap) {
						transferMap.put(tid, tr);
					}
				}
				
				
				if(!tr.txFromServer) {
					tr.txFromServerSize = size;
					tr.txFromServerBuffer = new ByteArrayOutputStream();
					tr.txFromServer = true;
					tr.txForCommand = null;

					if(debug)
						System.out.println("Setting up transfer from server of " + tr.txFromServerSize + " bytes");
					
					if(isPacket) {
						synchronized(commandQueue) {
							tr.txForCommand = commandQueue.peek();
						}
					}
				}
				
				tr.txFromServerNeededPacket = 0;
			} else {
				if(words.length < 4) {
					if(debug)
						System.err.println("Invalid TX: " + txMessage);
					return false;
				}
			}
			
			if(tr==null) {
				if(debug)
					System.err.println("No transfer for ID " + tid);
				return packet==0 ? isPacket : false;
			}
			
			if(packet != tr.txFromServerNeededPacket) {
				putCommand(Protocol.TXRESEND + " " + tid + " " + tr.txFromServerNeededPacket);
			} else {
				if(tr.txFromServer) {
					try {
						tr.txFromServerBuffer.write(words[dataIndex].getBytes(asciiCharset));
					} catch (IOException e) {
						e.printStackTrace();
						return false;
					}
				} else {
					if(debug)
						System.out.println("Not processing packet after finish: " + txMessage);
				}
				
				putCommand(Protocol.TXACK + " " + tid + " " + packet);

				if(tr.txFromServerBuffer.size() >= tr.txFromServerSize) {
					tr.txFromServer = false;
					String message = new String(tr.txFromServerBuffer.toByteArray(), asciiCharset);
					if(debug)
						System.out.println("Reconstructing answer to " + tr.txForCommand);
					commThread.addGeneratedMessage(tr.txForCommand, Util.urldecode(message));
					tr.txFromServerBuffer = null;
					tr.txFromServerNeededPacket = 0;
					synchronized(transferMap) {
						transferMap.remove(tid);
					}
				} else {
					tr.txFromServerNeededPacket++;
				}
			}
			
			// packet 0 is a response to a command...
			return packet==0 ? isPacket : false;
		}

		if(words[0].equalsIgnoreCase(Protocol.TXACK)) {
			if(tr.txToServer) {
				tr.lastAck = packet;
				packet++;
				int tranLen = tr.getPacketData(packet, tr.txBuffer, 0);
				if(tranLen > 0) {
					putCommand(Protocol.TXPACKET + " " + tid + " " + packet + " " + new String(tr.txBuffer, 0, tranLen, asciiCharset));
				} else {
					if(debug)
						System.out.println("Transfer to server finished...");
					// last packet ack'd
					tr.txToServer = false;
					synchronized(transferMap) {
						transferMap.remove(tid);
					}
				}
			}
			return false;
		}

		if(words[0].equalsIgnoreCase(Protocol.TXCANCEL)) {
			if(tr.txToServer) {
				tr.txToServer = false;
				tr.txToServerData = null;
				synchronized(transferMap) {
					transferMap.remove(tid);
				}
			}
			return false;
		}
		if(words[0].equalsIgnoreCase(Protocol.TXRESEND)) {
			if(tr.txToServer) {
				int tranLen = tr.getPacketData(packet, tr.txBuffer, 0);
				if(tranLen > 0) {
					putCommand(Protocol.TXPACKET + " " + tid + " " + packet + " " + (packet==0 ? (tr.txToServerData.length + " ") : "") + new String(tr.txBuffer, 0, tranLen, asciiCharset));
				}
			}
			return false;
		}
		
		return false;
	}
	
	private class CommunicationThread extends Thread implements UniqueIdProvider {
		private String serverAddress = "localhost";
		private int serverPort = Protocol.standardPort;
		private Selector readSelector = null;
		private SelectionKey readKey = null;
		private SocketChannel socketChannel = null;
		private boolean autoreconnect = true;
		private CommunicationState commState = CommunicationState.Disconnected;
		private String failureMessage = "";
		
		private boolean transientError = false;
		
		private boolean terminate = false;
		private boolean doConnect = false;
		private boolean doDisconnect = false;
		
		private Queue<String> messages = new LinkedList<String>();
		
		private String loginName = null;
		private int loginId = -1;
		
		private Queue<String> outgoing = new LinkedList<String>();
		
		private Random rnd = new Random();
		private Set<Integer> usedIds = new TreeSet<Integer>();
		
		private class GeneratedMessage {
			private String cmd;
			private String message;
			
			public GeneratedMessage(String cmd, String message) {
				this.cmd = cmd;
				this.message = message;
			}
			
			public String getCmd() {
				return cmd;
			}
			
			public String getMessage() {
				return message;
			}
		}
		
		private Queue<GeneratedMessage> genMsgQueue = new LinkedList<GeneratedMessage>();
		
		public void addGeneratedMessage(String cmd, String message) {
			synchronized (genMsgQueue) {
				genMsgQueue.offer(new GeneratedMessage(cmd, message));
			}
		}
		
		public CommunicationThread(String serverAddress, int serverPort) {
			this.serverAddress = serverAddress;
			this.serverPort = serverPort;
			setDaemon(true);
		}
		
		public Integer getUniqueId() {
			synchronized (usedIds) {
				Integer id = -1;
				
				do {
					while(id<1) 
						id=Integer.valueOf(rnd.nextInt());
				} while(usedIds.contains(id));
				
				usedIds.add(id);
				return id;
			}
		}
		
		public void freeId(Integer uid) {
			synchronized (usedIds) {
				usedIds.remove(uid);
			}
		}
		
		synchronized public void login(String loginName) {
			if(commState == CommunicationState.Failure) {
				commState = CommunicationState.Disconnected;
			}
			
			if(commState != CommunicationState.Disconnected)
				throw new RuntimeException("Cannot login while not disconnected!");
			
			this.loginName = loginName;
			this.loginId = -1;
			doConnect = true;
			doDisconnect = false;
		}

		synchronized public void relogin(String loginName, int id) {
			if(commState == CommunicationState.Failure) {
				commState = CommunicationState.Disconnected;
			}
			
			if(commState != CommunicationState.Disconnected)
				throw new RuntimeException("Cannot re-login while not disconnected!");
			
			this.loginName = loginName;
			this.loginId = id;
			doConnect = true;
			doDisconnect = false;
		}
		
		synchronized public void logout() {
			if(commState != CommunicationState.Connected)
				throw new RuntimeException("Cannot logout while not connected!");
			autoreconnect = false;
			doConnect = false;
			putMessage(Protocol.LOGOUT);
			doDisconnect = true;
		}

		synchronized public void logoutIfConnected() {
			if(commState == CommunicationState.Connected) {
				logout();
			}
		}
		
		public void putMessage(String msg) {
			synchronized(outgoing) {
				outgoing.offer(msg);
			}
		}
		
		public CommunicationState getCommunicationState() {
			return commState;
		}
		
		public String getFailureMessage() {
			return failureMessage;
		}
		
		public boolean getAutoReconnect() {
			return autoreconnect;
		}
		
		public void setAutoReconnect(boolean autoreconnect) {
			this.autoreconnect = autoreconnect;
		}
		
		public void run() {
			boolean noRead;
			boolean loginSend = false;
			boolean triedRelogin = false;
			byte [] buffer = new byte [64*1024];
			ByteBuffer bb = ByteBuffer.wrap(buffer);
			CommunicationState oldCommState = commState;
			while(!terminate || (commState == CommunicationState.Connected && !outgoing.isEmpty()) ) {
				noRead = true;
				if(oldCommState != commState) {
					listenerProcessCommunicationState(commState);
				}
				oldCommState = commState;
				switch(commState) {
				case Disconnected:
					triedRelogin = false;
					transientError = false;
					if(loginName != null && (doConnect || autoreconnect)) {
						try {
							SocketAddress sockAddr = new InetSocketAddress(serverAddress, serverPort);
							socketChannel = SocketChannel.open(sockAddr);
							socketChannel.configureBlocking(false);
							readSelector = Selector.open();
							readKey = socketChannel.register(readSelector, SelectionKey.OP_READ);
							commState = CommunicationState.Connecting;
							loginSend = false;
						} catch (UnknownHostException e) {
							transientError = true;
							failureMessage = failureUnknownHost + "\"" + serverAddress + "\"!";
							commState = CommunicationState.Failure;
						} catch(ConnectException ce) {
							transientError = true;
							failureMessage = failureConnection + "\"" + serverAddress + ":" + serverPort + "\"!";
							commState = CommunicationState.Failure;
						} catch (IOException e) {
							failureMessage = "IO-Exception while connecting!";
							commState = CommunicationState.Failure;
						}
					} else {
						try {
							sleep(100);
						} catch (InterruptedException e) {
						}
					}
					break;
				case Connecting:
					try {
						readSelector.select(500);
						if(readKey.isReadable()) {
							bb.rewind();
							int answerBytes = socketChannel.read(bb);
							
							if(answerBytes < 1) {
								if(answerBytes == -1) {
									if(debug) {
										System.err.println("Socket closed while connecting!");
									}
									transientError = true;
									failureMessage = "IO-Exception while connecting!";
									commState = CommunicationState.Failure;
									
									readKey.cancel();
									readSelector.close();
									//socket.close();
									socketChannel.close();
								}
								break;
							}
							
							noRead = false;
							
							String message = new String(buffer, 0, answerBytes);

							if(!loginSend) {
								if(message.startsWith("SiWearContextServer")) {

									try {
										if(doConnect && loginId == -1) {
											
											//reset();
											
											Protocol.writeMessageC(socketChannel, Protocol.LOGIN+" "+Util.urlencode(loginName));
											if(debug) {
												System.out.println("Sending LOGIN");
											}
										} else {
											triedRelogin = true;
											Protocol.writeMessageC(socketChannel, Protocol.RELOGIN+" "+Util.urlencode(loginName)+" "+loginId);
											if(debug) {
												System.out.println("Sending RELOGIN");
											}
										}
										loginSend = true;
									} catch (IOException e) {
										failureMessage = "Error while connecting to server!";
										commState = CommunicationState.Failure;
									}
								} else {
									failureMessage = "Invalid server response!";
									commState = CommunicationState.Failure;
								}
							} else {
								String [] words = Util.splitWS(message);

								if(words.length>=3 && words[0].equalsIgnoreCase(Protocol.ACCEPT)) {
									try {
										loginId = Integer.parseInt(words[2]);
										commState = CommunicationState.Connected;
										if(debug) {
											System.out.println("Connected");
										}
									} catch(NumberFormatException nfe) {
										failureMessage = "Invalid ID from Server!";
										commState = CommunicationState.Failure;
									}
								} else {
									transientError = true;
									failureMessage = "Server does not accept login!";
									commState = CommunicationState.Failure;
								}
							}
						}
					} catch(SocketTimeoutException ste) {
						// does not matter
					} catch (IOException e) {
						failureMessage = "Error while connecting to server!";
						commState = CommunicationState.Failure;
					}
					break;
				case Connected:
					triedRelogin = false;
					synchronized(outgoing) {
						while(!outgoing.isEmpty()) {
							try {
								WriteMessageResult res = Protocol.writeMessageC(socketChannel, outgoing.remove(), false, this);
								if(res.isTransfer()) {
									if(debug) {
										System.out.println("Starting transfer with ID " + res.getTransferId());
										System.out.println("Message: " + new String(res.getPacketBytes(), 0, res.getPacketBytes().length > 80 ? 80 : res.getPacketBytes().length));
									}
									TransferRepresentation tr = new TransferRepresentation();
									tr.txToServerData = res.getPacketBytes();
									tr.txToServer = true;
									synchronized(transferMap) {
										transferMap.put(res.getTransferId(), tr);
									}
								}
							} catch (IOException e) {
								try {
									readKey.cancel();
									readSelector.close();
									socketChannel.close();
								} catch (IOException e1) {
								}
								transientError = true;
								failureMessage = "Error while communicating with server!";
								commState = CommunicationState.Failure;
							}
						}
					}
					if(doDisconnect) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e) {
						}
						outgoing.clear();
						doDisconnect = false;
						commState = CommunicationState.Disconnected;
						loginId = -1;
						try {
							readKey.cancel();
							readSelector.close();
							socketChannel.close();
						} catch (IOException e1) {
						}
						// tell about logout even when asked to terminate
						if(terminate) {
							listenerProcessCommunicationState(commState);
						}
						break;
					}
					try {
						int msgBytes = 0;
						readSelector.selectNow();
						if(readKey.isReadable()) {
							bb.rewind();
							msgBytes = socketChannel.read(bb);
						}
						if(msgBytes > 0) {
							noRead = false;
							String rmsg = Protocol.decodeString(buffer, 0, msgBytes);
							String [] rmsgs = Util.splitNL(rmsg);
							
							for(String m : rmsgs) {
								messages.offer(m);
							}
						} else {
							if(msgBytes == -1) {
								if(debug) {
									System.err.println("Socket closed while waiting for message!");
								}
								transientError = true;
								failureMessage = "Socket closed while waiting for message!";
								commState = CommunicationState.Failure;
								
								readKey.cancel();
								readSelector.close();
								socketChannel.close();
								break;
							}
						}
						String msg = messages.poll();
						if(msg!=null) {
							if(msg.startsWith(Protocol.PING)) {
								synchronized(outgoing) {
									outgoing.offer(Protocol.PONG);
								}
							} else {
								if(msg.startsWith(Protocol.DROP)) {
									if(debug) {
										System.out.println("We have been dropped...");
									}
									commState = CommunicationState.Disconnected;
									try {
										readKey.cancel();
										readSelector.close();
										socketChannel.close();
									} catch (IOException e1) {
									}
								} else {
									if(msg.startsWith("TX")) {
										if(processTX(msg)) {
											processMessage(msg);
										}
									} else {
										if(Protocol.isServerInitiatedMessage(msg)) {
											listenerProcessContextInformation(msg);
										} else {
											processMessage(msg);
										}
									}
								}
							}
						}
						GeneratedMessage genMsg;
						synchronized (genMsgQueue) {
							genMsg = genMsgQueue.poll();
						}
						if(genMsg!=null) {
							if(genMsg.getCmd()==null) {
								listenerProcessContextInformation(genMsg.getMessage());
							} else {
								processGeneratedMessage(genMsg.getCmd(), genMsg.getMessage());
							}
						}
					} catch(SocketTimeoutException ste) {
						try {
							sleep(10);
						} catch (InterruptedException e) {
						}
					} catch (IOException e) {
						transientError = true;
						failureMessage = "Error while communicating with server!";
						commState = CommunicationState.Failure;
						try {
							readKey.cancel();
							readSelector.close();
							socketChannel.close();
						} catch (IOException e1) {
						}
					}
					break;
				case Failure:
					outgoing.clear();
					if(transientError) {
						try {
							sleep(1000);
							if(autoreconnect) {
								// failed after relogin: server restarted -> ID is invalid
								if(triedRelogin) {
									loginId = -1;
								}
								commState = CommunicationState.Disconnected;
							}
						} catch (InterruptedException e) {
						}
					} else {
						try {
							sleep(100);
						} catch (InterruptedException e) {
						}
					}
					break;
				}
				try {
					boolean noOut;
					synchronized(outgoing) {
						noOut = outgoing.isEmpty();
					}
					boolean noIn;
					synchronized(messages) {
						noIn = messages.isEmpty();
					}
					if(noOut && noIn && noRead) {
						sleep(5);
					}
				} catch (InterruptedException e) {
				}
			}
		}
		
		// reset interferes with context manager 
		@SuppressWarnings("unused")
		private void reset() {
			synchronized (outgoing) {
				outgoing.clear();
			}
			synchronized (commandQueue) {
				commandQueue.clear();
			}
			synchronized (genMsgQueue) {
				genMsgQueue.clear();
			}
		}
	}
	
	public void init(String serverAddress, int serverPort) {
		if(commThread != null) {
			if(debug)
				System.out.println("Stopping old commThread");
			commThread.terminate = true;
		}
		if(debug)
			System.out.println("Starting new commThread");
		commThread = new CommunicationThread(serverAddress, serverPort);
		commThread.start();
	}
	
	public void login(String name) {
		commThread.login(name);
	}

	public void relogin(String name, int id) {
		commThread.relogin(name, id);
	}
	
	public void logout() {
		commThread.logout();
	}

	public void logoutIfConnected() {
		commThread.logoutIfConnected();
	}
	
	public void putCommand(String message) {
		synchronized(commandQueue) {
			commandQueue.offer(message);
			commThread.putMessage(message);
		}
	}
	
	public CommunicationState getCommunicationState() {
		if(commThread==null)
			return CommunicationState.Disconnected;
		return commThread.getCommunicationState();
	}
	
	public String getFailureMessage() {
		return commThread.getFailureMessage();
	}
	
	public boolean getAutoReconnect() {
		return commThread.getAutoReconnect();
	}
	
	public void setAutoReconnect(boolean autoreconnect) {
		commThread.setAutoReconnect(autoreconnect);
	}
	
	public boolean canLogin() {
		if(commThread == null)
			return false;
		
		CommunicationState cs = commThread.getCommunicationState();
		
		return cs == CommunicationState.Disconnected || cs == CommunicationState.Failure;
	}

	public boolean canLogout() {
		if(commThread == null)
			return false;
		
		CommunicationState cs = commThread.getCommunicationState();
		
		return cs == CommunicationState.Connected;
	}
	
	public int getID() {
		return commThread.loginId;
	}
	
	public String getUserName() {
		return commThread.loginName;
	}
	
	public void terminate() {
		commThread.terminate = true;
	}
	
	public void listenerProcessCommunicationState(CommunicationState state) {
		for(ContextClientListener ccl : ccll) {
			ccl.processCommunicationState(state);
		}
	}
	
	private void processMessage(String message) {
		String cmd;
		synchronized(commandQueue) {
			cmd = commandQueue.poll();
		}
		listenerProcessCommandResult(cmd, message);
	}
	
	private void processGeneratedMessage(String cmd, String message) {
		listenerProcessCommandResult(cmd, message);
	}
	
	public void listenerProcessContextInformation(String message)
	{
		for(ContextClientListener ccl : ccll) {
			ccl.processContextInformation(message);
		}
	}
	
	private void listenerProcessCommandResult(String command, String result) {
		for(ContextClientListener ccl : ccll) {
			ccl.processCommandResult(command, result);
		}
	}
	
	
	public boolean addContextClientListener(ContextClientListener ccl) {
		return ccll.add(ccl);
	}
	
	public boolean removeContextClientListener(ContextClientListener ccl) {
		return ccll.remove(ccl);
	}
}

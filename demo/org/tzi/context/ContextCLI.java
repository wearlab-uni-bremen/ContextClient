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
package org.tzi.context;

import java.io.BufferedReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Vector;

import org.tzi.context.client.ContextClient;
import org.tzi.context.client.ContextClient.CommunicationState;
import org.tzi.context.client.ContextClientListener;
import org.tzi.context.client.ContextManager;
import org.tzi.context.common.Context;
import org.tzi.context.common.ContextElement;
import org.tzi.context.common.ContextListener;
import org.tzi.context.common.ContextListenerInterface;
import org.tzi.context.common.Protocol;
import org.tzi.context.common.Util;


public class ContextCLI {

	private static class IsQuoted {
		public String string;
		public boolean isQuoted;
		
		public IsQuoted(String string, boolean isQuoted) {
			this.string = string;
			this.isQuoted = isQuoted;
		}
	}
	
	private static boolean allWhitespace(String s) {
		for(int i=0; i<s.length(); i++) {
			if(!Character.isWhitespace(s.charAt(i)))
				return false;
		}
		return true;
	}
	
	private static List<IsQuoted> parseQuoted(String s) {
		Vector<IsQuoted> sv = new Vector<IsQuoted>();
		StringBuilder sb = new StringBuilder();
		
		boolean escaped = false;
		boolean inString = false;
		for(int i=0; i<s.length(); i++) {
			char c = s.charAt(i);

			if(inString) {
				if(c == '"') {
					inString = false;
					sv.add(new IsQuoted(sb.toString(), true));
					sb.setLength(0);
				} else {
					sb.append(c);
				}
			} else {

				if(escaped) {
					sb.append(c);
					escaped = false;
				} else {
					if(c == '"') {
						inString = true;
						String cur = sb.toString();
						if(!allWhitespace(cur)) {
							sv.add(new IsQuoted(cur, false));
						}
						sb.setLength(0);
					} else if(c == '\\') {
						escaped = true;
					} else {
						sb.append(c);
					}
				}
			}
		}
		
		if(escaped) {
			sb.append('\\');
		}
		
		String cur = sb.toString();
		if(inString || !allWhitespace(cur)) {
			sv.add(new IsQuoted(cur, inString));
		}
		
		return sv;
	}
	
	public static List<String> parseWords(String s) {
		List<IsQuoted> quotes = parseQuoted(s);
		List<String> words = new Vector<String>();
		
		for(IsQuoted iq : quotes) {
			if(iq.isQuoted) {
				words.add(iq.string);
			} else {
				for(String w : Util.splitWS(iq.string)) {
					if(!allWhitespace(w))
						words.add(w);
				}
			}
		}
		
		return words;
	}
	
	public static String [] parseWordsA(String s) {
		List<String> words = parseWords(s);
		
		return words.toArray(new String [words.size()]);
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		String userName = "user";
		String serverName = "localhost";
		int serverPort = Protocol.standardPort;
		
		boolean logoutOnExit = true;
		
		int aidx = 0;
		
		if(args.length > aidx) {
			userName = args[aidx++];
		}
		
		if(args.length > aidx) {
			serverName = args[aidx++];
		}

		if(args.length > aidx) {
			serverPort = Integer.parseInt(args[aidx++]);
		}
		
		final ContextClient cc = new ContextClient();
		cc.init(serverName, serverPort);
		cc.login(userName);
		
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		String cmd;
		
		cc.addContextClientListener(new ContextClientListener() {

			public void processCommandResult(String command, String result) {
				System.out.println("> " + result);
			}

			public void processCommunicationState(CommunicationState state) {
				System.out.println
				(
					"[" + state + "]"
					+ (state == CommunicationState.Failure ? " " + cc.getFailureMessage() : "")
					+ (state == CommunicationState.Connected ? " ID: " + cc.getID()  + " " + cc.getUserName(): "")
				);
				
			}

			public void processContextInformation(String message) {
				// TODO Auto-generated method stub
				
			}
		});
		
		ContextManager cm = new ContextManager(cc);
		
		cm.addContextListener(new ContextListener() {

			public ContextListenerInterface getProperties() {
				// TODO Auto-generated method stub
				return null;
			}

			public void processContext(Context ctx, ContextElement ce) {
				System.out.println(": " + ctx.getName() + "." + ce.getSourceIdentifier() + "." + ce.getPropertyIdentifier() + " => " + ce.getValue() + " @" + ce.getTimestamp() + " " + ce.getTypeTags());
			}

			public void propertyAdded(Context ctx, String source,
					String property) {
				// TODO Auto-generated method stub
				
			}

			public void propertyRemoved(Context ctx, String source,
					String property) {
				// TODO Auto-generated method stub
				
			}

			public void sourceAdded(Context ctx, String source, String property) {
				// TODO Auto-generated method stub
				
			}

			public void sourceRemoved(Context ctx, String source) {
				// TODO Auto-generated method stub
				
			}
		});
		
		try {
			while( (cmd = br.readLine()) != null ) {
				//String [] words = Util.splitWS(cmd);
				String [] words = parseWordsA(cmd);
				String command = words[0];
				
				if(command.equalsIgnoreCase("exit"))
					break;

				boolean processed = false;

				if(command.equalsIgnoreCase("observe")) {
					processed = true;

					if(words.length < 4) {
						System.out.println("Need at least ctx src and prp!");
					} else {
						String ctx = words[1];
						String src = words[2];
						String prp = words[3];
						List<String> tags = new Vector<String>();
						for(int tidx = 4; tidx < words.length; tidx++) {
							tags.add(words[tidx]);
						}
						cm.subscribe(ctx, src, prp, tags);
					}
				}

				if(command.equalsIgnoreCase("propagate")) {
					processed = true;

					if(words.length < 5) {
						System.out.println("Need at least ctx src prp and val!");
					} else {
						String ctx = words[1];
						String src = words[2];
						String prp = words[3];
						String val = words[4];
						int idx = 5;
						long timestamp = -1;
						boolean error = false;
						boolean persistent = false;
						if(words.length > idx) {
							try {
								timestamp = Long.parseLong(words[idx++]);
							} catch(NumberFormatException nfe) {
								System.out.println("Invalid timestamp! " + words[idx-1]);
								error = true;
							}
						}
						if(!error) {
							List<String> tags = new Vector<String>();
							while(idx < words.length) {
								String tag = words[idx++];
								if(idx == words.length && Util.startsWithIgnoreCase(tag, "p")) {
									persistent = true;
								} else {
									tags.add(tag);
								}
							}
							cm.setProperty(ctx, src, prp, val, tags, timestamp, persistent);
						}
					}
				} 

				if(command.equalsIgnoreCase("login")) {
					processed = true;
					Integer reID = null;
					boolean error = false;
					
					if(words.length > 1) {
						userName = words[1];
					}
					
					if(words.length > 2) {
						try {
							reID = Integer.parseInt(words[2]);
						} catch(NumberFormatException nfe) {
							error = true;
							System.out.println("Invalid re-login ID");
						}
					}
					if(!error) {
						if(cc.canLogin()) {
							if(reID!=null) {
								cc.relogin(userName, reID);
							} else {
								cc.login(userName);
							}
						}
						else {
							System.out.println("Cannot login now!");
						}
					}
				}

				if(command.equalsIgnoreCase("logout")) {
					processed = true;
					
					if(cc.canLogout()) {
						cc.logout();
					}
					else {
						System.out.println("Cannot logout now!");
					}
				}

				if(command.equalsIgnoreCase("cut")) {
					processed = true;
					cc.init(serverName, serverPort);
				}

				if(command.equalsIgnoreCase("getid")) {
					processed = true;
					if(cc.getCommunicationState() == CommunicationState.Connected)
						System.out.println("ID: " + cc.getID());
					else
						System.out.println("Not connected...");
				}

				if(command.equalsIgnoreCase("auto")) {
					processed = true;
					
					cc.setAutoReconnect(!cc.getAutoReconnect());
					System.out.println("Auto-Reconnect is now " + (cc.getAutoReconnect() ? "enabled" : "disabled"));
				}

				if(command.equalsIgnoreCase("autologout")) {
					processed = true;
					
					logoutOnExit = !logoutOnExit;
					System.out.println("Logout on exit is now " + (logoutOnExit ? "enabled" : "disabled"));
				}
						
				if(!processed)
					cc.putCommand(cmd);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		if(logoutOnExit)
			cc.logoutIfConnected();
		cc.terminate();
	}

}

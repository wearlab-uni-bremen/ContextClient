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

import java.util.Arrays;


import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.tzi.context.abstractenv.ContextAbstraction;
import org.tzi.context.abstractenv.PassiveEnvironment;
import org.tzi.context.abstractenv.PropertyAbstraction;
import org.tzi.context.abstractenv.SourceAbstraction;
import org.tzi.context.client.ContextClient.CommunicationState;
import org.tzi.context.common.Context;
import org.tzi.context.common.ContextElement;
import org.tzi.context.common.ContextListener;
import org.tzi.context.common.ContextListenerInterface;
import org.tzi.context.common.ContextMessage;
import org.tzi.context.common.Protocol;
import org.tzi.context.common.Util;


public class ContextManager implements ContextClientListener, ContextListener {
	
	public static interface Listener {
		public void onContextList(String prefix, Map<Integer, String> cm);
		public void onSourceList(String prefix, String context, Map<Integer, String> sm);
		public void onPropertyList(String prefix, String context, String source, Map<Integer, String> pm);
		public void onPropertyUpdate(String prefix, String context, String source, ContextElement ce);
		public void onHistory(String prefix, String context, String source, List<ContextElement> history);
	}
	
	private static enum ID_TYPE { IT_CTX, IT_SRC, IT_PRP, IT_SETPRPID, IT_SETPRP, IT_GETCTXID, IT_GETSRCID, IT_GETPRPID, IT_SUBSCRIPTION, IT_SHORTSUB, IT_GETCTXLIST, IT_GETSRCLIST, IT_GETPRPLIST, IT_GETPRPUPDATE, IT_GETPRPINFO, IT_HISTORY };

	private TreeSet<String> usedPrefixSet = new TreeSet<String>();
	
	private static final String caCtx = "ctx";
	
	private Object mapUpdateDummy = new Object();
	
	private PassiveEnvironment penv = new PassiveEnvironment();
	
	private static class DataHolder {
		private ID_TYPE type;
		
		private String prefix;
		private String context;
		private String source;
		private String property;
		
		private String value;
		private List<String> tags;
		private long timestamp;
		private boolean isPersistent;
		
		private String subscriptionString;
		private Object subscriptionKey;
		
		private int id;
		
		private boolean hasFailedBefore = false;
		
		private DataHolder(String prefix) {
			this.prefix = prefix;
		}
		
		private static DataHolder forCtxList(String prefix) {
			DataHolder dh = new DataHolder(prefix);
			dh.type = ID_TYPE.IT_GETCTXLIST;
			return dh;
		}

		private static DataHolder forSrcList(String prefix, String context) {
			DataHolder dh = new DataHolder(prefix);
			dh.type = ID_TYPE.IT_GETSRCLIST;
			dh.context = context;
			return dh;
		}

		private static DataHolder forPrpList(String prefix, String context, String source) {
			DataHolder dh = new DataHolder(prefix);
			dh.type = ID_TYPE.IT_GETPRPLIST;
			dh.context = context;
			dh.source = source;
			return dh;
		}

		private static DataHolder forPrpUpdate(String prefix, String context, String source, String property) {
			DataHolder dh = new DataHolder(prefix);
			dh.type = ID_TYPE.IT_GETPRPUPDATE;
			dh.context = context;
			dh.source = source;
			dh.property = property;
			return dh;
		}
		
		private static DataHolder forPrpHistory(String prefix, String context, String source, String property) {
			DataHolder dh = new DataHolder(prefix);
			dh.type = ID_TYPE.IT_HISTORY;
			dh.context = context;
			dh.source = source;
			dh.property = property;
			return dh;
		}
		
		private static DataHolder forCtx(String prefix, String context, boolean isCreate) {
			DataHolder h = new DataHolder(prefix);
			h.type = isCreate ? ID_TYPE.IT_CTX : ID_TYPE.IT_GETCTXID;
			h.context = context;
			return h;
		}

		private static DataHolder forSrc(String prefix, String context, String source, boolean isCreate) {
			DataHolder h = new DataHolder(prefix);
			h.type = isCreate ? ID_TYPE.IT_SRC : ID_TYPE.IT_GETSRCID;
			h.context = context;
			h.source = source;
			return h;
		}
		
		private static DataHolder forPrp(String prefix, String context, String source, String property, boolean isCreate) {
			DataHolder h = new DataHolder(prefix);
			h.type = isCreate ? ID_TYPE.IT_PRP : ID_TYPE.IT_GETPRPID;
			h.context = context;
			h.source = source;
			h.property = property;
			return h;
		}

		private static DataHolder forSet(String prefix, String context, String source, String property, String value, List<String> tags, long timestamp, boolean isPersistent) {
			DataHolder h = new DataHolder(prefix);
			h.type = ID_TYPE.IT_SETPRP;
			h.context = context;
			h.source = source;
			h.property = property;
			h.value = value;
			h.tags = tags;
			h.timestamp = timestamp;
			h.isPersistent = isPersistent;
			return h;
		}
		
		private static DataHolder forSubscription(String prefix, String subscription, Object key) {
			DataHolder h = new DataHolder(prefix);
			h.type = ID_TYPE.IT_SUBSCRIPTION;
			h.subscriptionString = subscription;
			h.subscriptionKey = key;
			return h;
		}
		
		private static DataHolder forUnknownPropertyContext(String prefix, String message) {
			DataHolder h = new DataHolder(prefix);
			h.type = ID_TYPE.IT_GETPRPINFO;
			h.value = message;
			return h;
		}
		
		private static DataHolder forShortSub(String prefix, Object key, int id, boolean setShort) {
			DataHolder h = new DataHolder(prefix);
			h.type = ID_TYPE.IT_SHORTSUB;
			h.subscriptionKey = key;
			h.id = id;
			h.isPersistent = setShort;
			return h;
		}
		
	}

	public static class SubscriptionProperty {
		public String name;
		public Set<String> tags = new TreeSet<String>();
		
		public SubscriptionProperty(String prp, Set<String> tags) {
			name = prp;
			if(tags!=null)
				this.tags.addAll(tags);
		}

		public SubscriptionProperty(String prp) {
			this(prp, null);
		}
		
		public SubscriptionProperty merge(SubscriptionProperty sp) {
			this.tags.addAll(sp.tags);
			if(this.tags.size()>0 && this.tags.contains(Context.ALL_TAGS)) {
				this.tags.remove(Context.ALL_TAGS);
			}
			
			return this;
		}
	}
	
	public static class SubscriptionSource {
		public String name;
		public Map<String, SubscriptionProperty> props = new TreeMap<String, SubscriptionProperty>(); 

		public SubscriptionSource(String src) {
			this.name = src;
		}

		public SubscriptionSource add(SubscriptionProperty sprp) {
			SubscriptionProperty pold = props.get(sprp.name);
			
			if(pold!=null) {
				pold.merge(sprp);
			} else {
				props.put(sprp.name, sprp);
			}
			
			return this;
		}
		
		public SubscriptionSource remove(SubscriptionProperty sprp) {
			props.remove(sprp.name);

			return this;
		}
		
		public SubscriptionSource remove(String prpname) {
			props.remove(prpname);

			return this;
		}
		
		public SubscriptionSource merge(SubscriptionSource snew) {
			for(SubscriptionProperty sprp : snew.props.values()) {
				add(sprp);
			}

			return this;
		}
	}
	
	public static class SubscriptionContext {
		public String name;
		public Map<String, SubscriptionSource> sources = new TreeMap<String, SubscriptionSource>();
		
		public SubscriptionContext(String ctx) {
			this.name = ctx;
		}
		
		public SubscriptionContext add(SubscriptionSource ssrc) {
			SubscriptionSource sold = sources.get(ssrc.name);
			
			if(sold!=null) {
				sold.merge(ssrc);
			} else {
				sources.put(ssrc.name, ssrc);
			}
			
			return this;
		}
		
		public SubscriptionContext remove(SubscriptionSource ssrc) {
			sources.remove(ssrc.name);
			
			return this;
		}
		
		public SubscriptionContext remove(String srcname) {
			sources.remove(srcname);
			
			return this;
		}
		
		public SubscriptionContext merge(SubscriptionContext cnew) {
			for(SubscriptionSource ssrc : cnew.sources.values()) {
				add(ssrc);
			}
			
			return this;
		}
	}
	
	public static class SubscriptionData {
		public Object key;
		public Map<String, SubscriptionContext> contexts = new TreeMap<String, SubscriptionContext>();
		
		public SubscriptionData(Object key) {
			this.key = key;
		}
		
		public SubscriptionData add(SubscriptionContext sctx) {
			SubscriptionContext cold = contexts.get(sctx.name);
			if(cold!=null) {
				cold.merge(sctx);
			} else {
				contexts.put(sctx.name, sctx);
			}
			
			return this;
		}
		
		public SubscriptionData remove(SubscriptionContext sctx) {
			contexts.remove(sctx.name);
			
			return this;
		}
		
		public SubscriptionData remove(String ctxname) {
			contexts.remove(ctxname);
			
			return this;
		}
		
		public SubscriptionData merge(SubscriptionData dnew) {
			for(SubscriptionContext sctx : dnew.contexts.values()) {
				add(sctx);
			}
			
			return this;
		}
		
		public static SubscriptionData create(Object key, String ctx, String src, String prp, Set<String> tags) {
			SubscriptionData sd = new SubscriptionData(key);
			return sd.add(new SubscriptionContext(ctx).add(new SubscriptionSource(src).add(new SubscriptionProperty(prp, tags))));
		}
	}
	
	private TreeMap<String, DataHolder> setHolderMap = new TreeMap<String, DataHolder>();
	
	private List<ContextListener> listeners = new CopyOnWriteArrayList<ContextListener>();
	private List<Listener> mgrListeners = new CopyOnWriteArrayList<ContextManager.Listener>();
	
	private List<SubscriptionData> subscriptions_todo = new LinkedList<SubscriptionData>();
	private List<SubscriptionData> subscriptions = new LinkedList<SubscriptionData>();
	
	private TreeMap<Integer, Object> subscriptionMap = new TreeMap<Integer, Object>();
	
	private int lastClientId = -1;
	private ContextClient client = null;
	
	private static final String prefixStart = "cm2";
	private static final int prefixLength = 4;
	
	private static final String alpha = "abcdefghijklmnopqrstzuvwxyz";
	private static final String num = "0123456789";
	
	private static final String prefixChars = alpha + alpha.toUpperCase() + num;
	
	private static final String prefixFormat;
	private static final Character [] prefixCharBuffer = new Character [prefixLength];
	
	static {
		String tmp = prefixStart;
		for(int i=0; i<prefixLength; i++)
			tmp += "%c";
		
		prefixFormat = tmp;
	}
	
	private Random rnd = new Random();
	
	private String getPrefix() {
		String prefix;
		synchronized (usedPrefixSet) {
			do {
				for(int i=0; i<prefixLength; i++)
					prefixCharBuffer[i] = prefixChars.charAt(rnd.nextInt(prefixChars.length()));
				prefix = String.format(prefixFormat, (Object [])prefixCharBuffer);
			} while(usedPrefixSet.contains(prefix));

			usedPrefixSet.add(prefix);
		}		
		return prefix;
	}
	
	private void freePrefix(String prefix) {
		synchronized (usedPrefixSet) {
			usedPrefixSet.remove(prefix);
		}
	}
	
	private boolean isOurPrefix(String prefix) {
		synchronized (usedPrefixSet) {
			return usedPrefixSet.contains(prefix);
		}
	}
	
	public ContextManager(ContextClient client) {
		client.addContextClientListener(this);
		this.client = client;
	}
	
	public DataHolder setProperty(String context, String source, String property, String value) {
		return setProperty(context, source, property, value, null, -1, false);
	}
	
	public DataHolder setProperty(String context, String source, String property, String value, List<String> tags, long timestamp) {
		return setProperty(context, source, property, value, tags, timestamp, false);
	}
	
	public DataHolder setProperty(String context, String source, String property, String value, List<String> tags, long timestamp, boolean isPersistent) {
		StringBuilder sb = new StringBuilder();
		
		Integer prpId = getPropertyId(context, source, property);
		
		String prefix = getPrefix();
		
		DataHolder dh;

		synchronized(setHolderMap){
			setHolderMap.put(prefix, dh = DataHolder.forSet(prefix, context, source, property, value, tags, timestamp, isPersistent));
		}
		
		sb.append(Protocol.PREFIX_CHAR);
		sb.append(prefix);
		sb.append(' ');
		
		sb.append(Protocol.SETPRP);
		
		if(prpId!=null) {
			sb.append(" ");
			sb.append(prpId.toString());
			dh.type = ID_TYPE.IT_SETPRPID;
		} else {
			
			sb.append(" @");
			sb.append(Util.urlencode(context));
			sb.append(" @");
			sb.append(Util.urlencode(source));
			sb.append(" @");
			sb.append(Util.urlencode(property));
		}
		sb.append(" = ");
		sb.append(Util.urlencode(value));
		sb.append(" ");
		sb.append(Long.toString(timestamp));
		sb.append(" ");
		sb.append(Integer.toString(tags==null?0:tags.size()));
		if(tags!=null) {
			for(String tag : tags) {
				sb.append(" ");
				sb.append(Util.urlencode(tag));
			}
		}
		if(isPersistent) {
			sb.append(" P");
		}
		String command = sb.toString();;
		doCommand(command);
		
		if(prpId==null) {
			// request ids...
			System.out.println("Requesting ids for " + context + ", " + source + ", " + property);
			String cmdid;
			
			cmdid = getPrefix();
			synchronized(setHolderMap){
				setHolderMap.put(cmdid, DataHolder.forCtx(cmdid, context, false));
				doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.GETCTXID + " " + Util.urlencode(context));

				cmdid = getPrefix();
				setHolderMap.put(cmdid, DataHolder.forSrc(cmdid, context, source, false));
				doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.GETSRCID + " @" + Util.urlencode(context) + " " + Util.urlencode(source));

				cmdid = getPrefix();
				setHolderMap.put(cmdid, DataHolder.forPrp(cmdid, context, source, property, false));
			}
			doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.GETPRPID + " @" + Util.urlencode(context) + " @" + Util.urlencode(source) + " " + Util.urlencode(property));
		}
		
		return dh;
	}
	
	public void subscribe(String context, String source, String property, String...tags) {
		subscribe(null, context, source, property, Arrays.asList(tags));
	}

	public void subscribe(Object key, String context, String source, String property, String...tags) {
		subscribe(key, context, source, property, Arrays.asList(tags));
	}
	
	public void subscribe(String context, String source, String property, List<String> tags) {
		subscribe(null, context, source, property, tags);
	}
	
	public static <A> List<A> listOrEmtpy(List<A> l) {
		if(l!=null)
			return l;
		
		return Collections.emptyList();
	}
	
	public static <A> Set<A> listToSetOrEmpty(List<A> l) {
		if(l!=null)
			return new TreeSet<A>(l);
		
		return Collections.emptySet();
	}

	
	public void subscribe(Object key, String context, String source, String property, List<String> tags) {
		SubscriptionData sd = SubscriptionData.create(key, context, source, property, listToSetOrEmpty(tags) );
		subscribe(sd);
	}
	
	public void subscribe(SubscriptionData sd) {
		synchronized(subscriptions) {
			subscriptions_todo.add(sd);
			processPendingSubscriptions();
		}
	}
	
	private void processPendingSubscriptions() {
		if(client.getCommunicationState() == CommunicationState.Connected) {
			synchronized (subscriptions) {
				while(!subscriptions_todo.isEmpty()) {
					System.out.println("Subscribing now...");
					SubscriptionData sd = subscriptions_todo.remove(0);
					String command = createSubscribeString(sd);
					subscriptions.add(sd);
					DataHolder h = DataHolder.forSubscription(getPrefix(), command, sd.key);
					synchronized (setHolderMap) {
						setHolderMap.put(h.prefix, h);
					}
					doCommand(Protocol.PREFIX_CHAR + h.prefix + " " + command);
				}
			}
		} else {
			synchronized (subscriptions) {
				if(subscriptions_todo.size()>0)
					System.out.println("Can't subscribe now...");
			}
		}
	}
	
	/*
	public void subscribeMultiProp(Object key, String context, String source, List<String> properties, List<String> tags) {
		String command = createSubscribeStringMultiProp(context, source, properties, tags);
		DataHolder h = DataHolder.forSubscription(getPrefix(), command, key);
		synchronized (setHolderMap) {
			setHolderMap.put(h.prefix, h);
		}
		doCommand(Protocol.PREFIX_CHAR + h.prefix + " " + command);
	}
	*/
	
	protected static String createSubscribeString(String context, String source,
			String property, List<String> tags) {
		StringBuilder sb = new StringBuilder();
		
		sb.append(Protocol.SUBSCRIBE);
		sb.append(" 1 ");
		if(Context.ALL_CONTEXTS.equals(context)) {
			sb.append(Context.ALL_CONTEXTS);
		} else {
			sb.append('@');
			sb.append(Util.urlencode(context));
		}
		sb.append(" 1 ");
		if(Context.ALL_SOURCES.equals(source)) {
			sb.append(Context.ALL_SOURCES);
		} else {
			sb.append('@');
			sb.append(Util.urlencode(source));
		}
		sb.append(" 1 ");
		if(Context.ALL_PROPERTIES.equals(property)) {
			sb.append(Context.ALL_PROPERTIES);
		} else {
			sb.append('@');
			sb.append(Util.urlencode(property));
		}
		sb.append(' ');
		if(tags!=null) {
			sb.append(Integer.toString(tags.size()));
			for(String tag : tags) {
				sb.append(' ');
				sb.append(Util.urlencode(tag));
			}
		} else {
			sb.append("0");
		}
		String command = sb.toString();
		return command;
	}
	
	public static String createSubscribeString(SubscriptionData sd) {
		StringBuilder sb = new StringBuilder();
		
		sb.append(Protocol.SUBSCRIBE);

		sb.append(' ');
		sb.append(Integer.toString(sd.contexts.size()));
		sb.append(' ');
		
		boolean firstContext = true;
		for(SubscriptionContext sctx : sd.contexts.values()) {
			if(firstContext) {
				firstContext = false;
			} else {
				sb.append(' ');
			}
			if(sctx.name.equals(Context.ALL_CONTEXTS)) {
				sb.append(Context.ALL_CONTEXTS);
			}
			else {
				sb.append('@');
				sb.append(sctx.name);
			}
			
			sb.append(' ');
			sb.append(Integer.toString(sctx.sources.size()));
			sb.append(' ');
			
			boolean firstSource = true;
			for(SubscriptionSource ssrc : sctx.sources.values()) {
				if(firstSource) {
					firstSource = false;
				} else {
					sb.append(' ');
				}

				if(ssrc.name.equals(Context.ALL_SOURCES)) {
					sb.append(Context.ALL_SOURCES);
				}
				else {
					sb.append('@');
					sb.append(ssrc.name);
				}
				
				sb.append(' ');
				sb.append(Integer.toString(ssrc.props.size()));
				sb.append(' ');
				
				boolean firstProp = true;
				for(SubscriptionProperty sprp : ssrc.props.values()) {
					if(firstProp) {
						firstProp = false;
					} else {
						sb.append(' ');
					}
					if(sprp.name.equals(Context.ALL_PROPERTIES)) {
						sb.append(Context.ALL_PROPERTIES);
					}
					else {
						sb.append('@');
						sb.append(sprp.name);
					}

					sb.append(' ');
					sb.append(Integer.toString(sprp.tags.size()));
					sb.append(' ');

					boolean firstTag = true;
					for(String tag : sprp.tags) {
						if(firstTag) {
							firstTag=false;
						} else {
							sb.append(' ');
						}
						sb.append(tag);
					}

				}
			}
		}
		
		return sb.toString();
	}
	
	protected static String createSubscribeStringMultiProp(String context, String source,
			List<String> properties, List<String> tags) {
		StringBuilder sb = new StringBuilder();
		
		sb.append(Protocol.SUBSCRIBE);
		sb.append(" 1 ");
		if(Context.ALL_CONTEXTS.equals(context)) {
			sb.append(Context.ALL_CONTEXTS);
		} else {
			sb.append('@');
			sb.append(Util.urlencode(context));
		}
		sb.append(" 1 ");
		if(Context.ALL_SOURCES.equals(source)) {
			sb.append(Context.ALL_SOURCES);
		} else {
			sb.append('@');
			sb.append(Util.urlencode(source));
		}
		sb.append(' ');
		sb.append(Integer.toString(properties.size()));
		for(String prop : properties) {
			sb.append(" @");
			sb.append(Util.urlencode(prop));
		}
		sb.append(' ');
		if(tags!=null) {
			sb.append(Integer.toString(tags.size()));
			for(String tag : tags) {
				sb.append(' ');
				sb.append(Util.urlencode(tag));
			}
		} else {
			sb.append("0");
		}
		String command = sb.toString();
		return command;
	}


	protected void doCommand(String command) {
		client.putCommand(command);
	}
	
	private void updateContextMap(String contextName, Integer contextId) {
		synchronized(mapUpdateDummy) {
			ContextAbstraction ca = penv.getContextByName(contextName);
			if(ca!=null) {
				if(ca.getId() != contextId) {
					//System.out.println("...updating context id... " + ca.getId() + " -> " + contextId);
					penv.changeContextId(ca.getId(), contextId);
				}
			} else {
				//System.out.println("Creating context at update " + contextId);
				ca = penv.injectContext(contextId, contextName);
				Context ctx = new Context(contextName);
				ctx.addContextListener(this);
				ca.setData(caCtx, ctx);
			}
		}
	}
	
	private void updateSourceMap(Integer contextId, String sourceName, Integer sourceId) {
		synchronized(mapUpdateDummy) {
			ContextAbstraction ca = penv.getContextById(contextId);
			if(ca==null)
				return;
			SourceAbstraction sa = ca.getSourceByName(sourceName);

			if(sa!=null) {
				if(sa.getId() != sourceId) {
					penv.changeSourceId(sa.getId(), sourceId);
				}
			} else {
				//System.out.println("...inserting source id...");
				penv.injectSource(ca, sourceId, sourceName);
			}
		}
	}
	
	private void updatePropertyMap(Integer ctxId, Integer srcId, String propertyName, Integer propertyId) {
		synchronized(mapUpdateDummy) {
			SourceAbstraction sa = penv.getSourceById(srcId);
			if(sa==null)
				return;
			PropertyAbstraction pa = sa.getPropertyByName(propertyName);
			if(pa!=null) {
				if(pa.getId() != propertyId) {
					//System.out.println("...updating property id...");
					penv.changePropertyId(pa.getId(), propertyId);
				}
			} else {
				//System.out.println("...inserting property id...");
				penv.injectProperty(sa, propertyId, propertyName);
			}
		}
	}
	
	private Integer getPropertyId(String ctxName, String srcName, String prpName) {
		synchronized(mapUpdateDummy) {
			ContextAbstraction ca = penv.getContextByName(ctxName);
			if(ca==null)
				return null;
			SourceAbstraction sa = ca.getSourceByName(srcName);
			if(sa==null)
				return null;
			PropertyAbstraction pa = sa.getPropertyByName(prpName);
			if(pa==null)
				return null;
			return pa.getId();
		}
	}
	
	private void resetIdAssociations() {
		synchronized(mapUpdateDummy) {
			penv = new PassiveEnvironment();
		}		
	}

	public void processCommandResult(String command, String result) {
		String [] words = Util.splitWS(result);
		String [] _prefix = { null };
		String prefix = null;

		words = Util.splitPrefixFromMessage(words, _prefix);
		prefix = _prefix[0];

		if(prefix==null)
			return;

		if(!isOurPrefix(prefix)) {
			System.err.println("Not our prefix... " + prefix);
			return;
		}

		DataHolder dh;
		synchronized(setHolderMap) {
			dh = setHolderMap.remove(prefix);
		}

		freePrefix(prefix);

		if(words.length < 1)
			return;


		ID_TYPE idt = dh != null ? dh.type : null;

		if(idt!=null) {
			//System.out.println("Received a reply for a command of type: " + idt + ": " + result);

			Integer pid = Util.parseIntReply(result);

			String rctx = dh.context;
			String rsrc = dh.source;
			String rprp = dh.property;

			Integer ctxId = null;
			Integer srcId = null;

			if(rctx!=null) {
				ContextAbstraction ca = penv.getContextByName(rctx);
				ctxId = ca == null ? null : ca.getId();

				if(ctxId!=null && rsrc != null) {
					SourceAbstraction sa = ca.getSourceByName(rsrc);
					srcId = sa == null ? null : sa.getId();
				}
			}

			if(pid!=null) {

				switch(idt) {
				case IT_CTX:
				case IT_GETCTXID:
					if(rctx!=null) {
						//System.out.println("Updating context " + rctx + " to id " + pid);
						updateContextMap(rctx, pid);
					} else {
						System.err.println("Bad map... no context for " + prefix);
					}
					break;
				case IT_SRC:
				case IT_GETSRCID:
					if(ctxId != null && rsrc != null) {
						//System.out.println("Updating source " + rsrc + " in context " + rctx);
						updateSourceMap(ctxId, rsrc, pid);
					} else {
						System.err.println("Bad map... no context and/or source for " + prefix);
					}
					break;
				case IT_PRP:
				case IT_GETPRPID:
					if(srcId != null && rprp != null) {
						//System.out.println("Updating property " + rprp + "in source " + rsrc + " in context " + rctx);
						updatePropertyMap(ctxId, srcId, rprp, pid);
					} else {
						System.err.println("Bad map... no source and/or property for " + prefix);
					}
					break;
				}
			} else {
				
				switch(idt) {
				case IT_SUBSCRIPTION:
					if(Util.isFailReply(result)) {
						System.err.println("Failed to subscribe with " + dh.subscriptionString);
					} else {
						System.out.println("Received subscription id for " + dh.subscriptionKey + "");
						try {
							pid = Integer.parseInt(words[1]);
							System.out.println("Got " + pid + " subscription ids...");
							for(int i=0; i<pid; i++) {
								try {
									int subpid = Integer.parseInt(words[2+i]);
									System.out.println("Processing sub-pid " + subpid + "...");
									if(dh.subscriptionKey!=null) {
										synchronized (subscriptionMap) {
											subscriptionMap.put(subpid, dh.subscriptionKey);
										}
									}
									setShortSub(subpid, dh.subscriptionKey, true);
								} catch(NumberFormatException nfe) {
									System.err.println("Error parsing sub-pid!");
								}
							}
						} catch(NumberFormatException nfe) {
							System.err.println("Error parsing number of sub-pids!");
						}
						break;
					}
					break;
				case IT_SHORTSUB:
					if(Util.isFailReply(result)) {
						System.err.println("Failed to set short context info for " + dh.id + ": " + dh.subscriptionKey);
					} else {
						System.out.println("Short context info for " + dh.id + ": " + dh.subscriptionKey + (dh.isPersistent ? " activated" : " deactivated"));
					}
					break;
				case IT_SETPRP:
				case IT_SETPRPID:
					if(Util.isFailReply(result)) {
						if(rctx!=null && rsrc != null && rprp != null) {
							if(!dh.hasFailedBefore) {
								if(idt == ID_TYPE.IT_SETPRP) {

									System.out.println(">>> setprp failed. creating properties!");

									String cmdid;
									cmdid = getPrefix();
									synchronized(setHolderMap) {
										setHolderMap.put(cmdid, DataHolder.forCtx(cmdid, rctx, true));
										System.out.println("Creating context with prefix " + cmdid);

										doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.CREATECTX + " " + Util.urlencode(rctx));

										cmdid = getPrefix();
										setHolderMap.put(cmdid, DataHolder.forSrc(cmdid, rctx, rsrc, true));

										System.out.println("Creating source with prefix " + cmdid);

										doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.CREATESRC + " @" + Util.urlencode(rctx) + " " + Util.urlencode(rsrc));

										cmdid = getPrefix();
										setHolderMap.put(cmdid, DataHolder.forPrp(cmdid, rctx, rsrc, rprp, true));
									}

									System.out.println("Creating property with prefix " + cmdid);

									doCommand(Protocol.PREFIX_CHAR + cmdid + " " + Protocol.CREATEPRP + " @" + Util.urlencode(rctx) + " @" + Util.urlencode(rsrc) + " " + Util.urlencode(rprp));
									DataHolder dhnew = setProperty(dh.context, dh.source, dh.property, dh.value, dh.tags, dh.timestamp, dh.isPersistent);
									dhnew.hasFailedBefore = true;
								} else {
									System.out.println("Resetting id associations...");
									resetIdAssociations();

									// not setting fail flag here, because it is more likely that the server was just
									// restarted and the ids are wrong...
									setProperty(dh.context, dh.source, dh.property, dh.value, dh.tags, dh.timestamp, dh.isPersistent);
								}
							} else {
								System.err.println(">>> hardfail at setting " + dh.context + ", " + dh.source + ", " + dh.property);
							}
						} else {
							System.err.println("Failed but don't know what to do...");
						}
					}
					break;
				case IT_GETPRPINFO:
					// REPLY IDINFO P .....
					if(words.length == 11 && "P".equals(words[2])) {
						String prpIdS = words[3];
						String prpName = words[4];
						String srcIdS = words[6];
						String srcName = words[7];
						String ctxIdS = words[9];
						String ctxName = words[10];
						
						try  {
							Integer prpId = Integer.parseInt(prpIdS);
							srcId = Integer.parseInt(srcIdS);
							ctxId = Integer.parseInt(ctxIdS);
							
							updateContextMap(ctxName, ctxId);
							updateSourceMap(ctxId, srcName, srcId);
							updatePropertyMap(ctxId, srcId, prpName, prpId);
							
							//System.out.println("Re-Processing context...");
							processContextInformation(dh.value);
						} catch(NumberFormatException nfe) {
							
						}
					} else {
						System.err.println("Lost context to unknown property... " + words.length + " " + words[1]);
					}
					break;
				case IT_GETCTXLIST:
					Map<Integer, String> ctxmap = Protocol.parseCTXList(result);
					for(Map.Entry<Integer, String> ctxentry : ctxmap.entrySet()) {
						updateContextMap(ctxentry.getValue(), ctxentry.getKey());
					}
					invokeContextListMessage(prefix, ctxmap);
					break;
				case IT_GETSRCLIST:
					Map<Integer, Map<Integer, String>> srcmap = Protocol.parseSRCList(result);
					if(srcmap==null) {
						srcmap = new TreeMap<Integer, Map<Integer,String>>();
					}
					Map<Integer, String> src = null;
					for(Map.Entry<Integer, Map<Integer, String>> e : srcmap.entrySet()) {
						Integer srcctx = e.getKey();
						src = e.getValue();
						for(Map.Entry<Integer, String> srcentry : e.getValue().entrySet()) {
							updateSourceMap(srcctx, srcentry.getValue(), srcentry.getKey());
						}
						break;
					}
					if(src!=null) {
						invokeSourceListMessage(prefix, dh.context, src);
					}
					break;
				case IT_GETPRPLIST:
					Map<Integer, Map<Integer, Map<Integer, String>>> prpmap = Protocol.parsePRPList(result);
					Map<Integer, Map<Integer, String>> prp = null;
					if(prpmap==null) {
						System.err.println("No map from " + result);
						break;
					}
					Integer prpctx = null;
					for(Map.Entry<Integer, Map<Integer, Map<Integer, String>>> e : prpmap.entrySet()) {
						prpctx = e.getKey();
						prp = e.getValue();
						break;
					}
					if(prp!=null) {
						src = null;
						Integer prpsrc = null;
						for(Map.Entry<Integer, Map<Integer, String>> e : prp.entrySet()) {
							prpsrc = e.getKey();
							src = e.getValue();
							break;
						}
						
						if(src!=null) {
							for(Map.Entry<Integer, String> prpentry : src.entrySet()) {
								updatePropertyMap(prpctx, prpsrc, prpentry.getValue(), prpentry.getKey());
							}
							
							invokePropertyListMessage(prefix, dh.context, dh.source, src);
						}
					}
					
					break;
				case IT_GETPRPUPDATE:
					ContextElement ce = Protocol.parseProperty(dh.source, dh.property, result);
					if(ce!=null) {
						invokePropertyUpdateMessage(prefix, dh.context, dh.source, ce);
					}
					break;
				case IT_HISTORY:
					List<ContextElement> history = Protocol.parseHistory(dh.source, dh.property, result);
					if(history!=null) {
						invokeHistoryMessage(prefix, dh.context, dh.source, history);
					}
					break;
				}
			}

		} else {
			System.err.println("No known command with prefix " + prefix);
		}
	}

	public void processCommunicationState(CommunicationState state) {
		if(state == CommunicationState.Connected) {
			int idNow = client.getID();

			if(idNow != lastClientId) {
				synchronized (subscriptions) {
					subscriptions_todo.addAll(subscriptions);
					subscriptions.clear();
				}
				lastClientId = idNow;
				processPendingSubscriptions();
			}
		}
	}

	public void processContextInformation(String message) {
		ContextMessage cm = ContextMessage.fromString(message);
		if(cm!=null) {
			String contextName = cm.getContextName();
			ContextElement ce = cm.getCE();
			String cinfo = cm.getContextInformation();
			Integer ctxId = null;
			if(cinfo != null && (cinfo = cinfo.trim()).length() > 0) {
				try {
					ctxId = Integer.parseInt(cm.getContextInformation());
				} catch(NumberFormatException nfe) {
					
				}
			}
			
			if(cm.isShortFormat()) {
				try {
					Integer prpId = Integer.parseInt(cm.getShortPrefix());
					PropertyAbstraction pa = penv.getPropertyById(prpId);
					
					if(pa!=null) {
						contextName = pa.getSource().getContext().getName();
						ce = new ContextElement(pa.getSource().getName(), pa.getName(), ce.getValue(), ce.getTimestamp(), ce.isPersistent(), ce.getTypeTags());
						ctxId = pa.getSource().getContext().getId();
					} else {
						System.err.println("No such short property: " + prpId + " ... asking server and re-scheduling processing...");
						requestPrpInfo(prpId, message);
						return;
					}
					
					
				} catch(NumberFormatException nfe) {
					System.err.println("Short Context without Id!");
					return;
				}
			}
			
			ContextAbstraction ca = penv.getContextByName(contextName);
			Context ctx = ca == null ? null : (Context)ca.getData(caCtx);

			if(ctx==null) {
				if(ctxId == null) {
					 System.err.println("No id for context creation!");
					 return;
				} else {
					//System.out.println("Creating context at context event... " + ctxId);
					ctx = new Context(contextName);
					ctx.addContextListener(this);
					ca = penv.injectContext(ctxId, contextName);
					ca.setData(caCtx, ctx);
				}
			}
			
			ContextMessage.Type cmt = cm.getType();
			
			ctx.setCurrentIdentifier(cm.getListenerId());
			ctx.setCurrentKey(null);
			
			try {
				Integer listenerKey = Integer.parseInt(cm.getListenerId());
				synchronized (subscriptionMap) {
					ctx.setCurrentKey(subscriptionMap.get(listenerKey));
				}
			} catch(NumberFormatException nfe) {
				
			}

			switch(cmt) {
			case Context:
				ctx.mergeContextElement(ce);
				break;
			case PropertyRemoved:
				ctx.removeSourceProperty(ce.getSourceIdentifier(), ce.getPropertyIdentifier());
				break;
			case SourceRemoved:
				ctx.removeSource(ce.getSourceIdentifier());
			}
		}
	}

	public ContextListenerInterface getProperties() {
		return null;
	}
	
	private static class ManagerMessageThread extends Thread {
		private Listener l = null;
		
		private String prefix;
		private Map<Integer, String> ctxList = null;
		
		private String context;
		private String source;
		private Map<Integer, String> srcList = null;
		private Map<Integer, String> prpList = null;
		private ContextElement ce = null;
		private List<ContextElement> history = null;
		
		
		public ManagerMessageThread(Listener l, String prefix) {
			this.l = l;
			this.prefix = prefix;
		}
		
		public ManagerMessageThread(Listener l, String prefix, Map<Integer, String> ctxMap) {
			this(l, prefix);
			if(ctxMap!=null) {
				this.ctxList = new TreeMap<Integer, String>();
				this.ctxList.putAll(ctxMap);
			}
		}
		
		public static ManagerMessageThread forSrcList(Listener l, String prefix, String context, Map<Integer, String> srcMap) {
			ManagerMessageThread mmt = new ManagerMessageThread(l, prefix);
			mmt.context = context;
			mmt.srcList  = srcMap;
			return mmt;
		};
		
		public static ManagerMessageThread forPrpList(Listener l, String prefix, String context, String source, Map<Integer, String> prpMap) {
			ManagerMessageThread mmt = new ManagerMessageThread(l, prefix);
			mmt.context = context;
			mmt.source = source;
			mmt.prpList  = prpMap;
			return mmt;
		};

		public static ManagerMessageThread forPrpUpdate(Listener l, String prefix, String context, String source, ContextElement ce) {
			ManagerMessageThread mmt = new ManagerMessageThread(l, prefix);
			mmt.context = context;
			mmt.source = source;
			mmt.ce = ce;
			return mmt;
		};

		public static ManagerMessageThread forHistory(Listener l, String prefix, String context, String source, List<ContextElement> history) {
			ManagerMessageThread mmt = new ManagerMessageThread(l, prefix);
			mmt.context = context;
			mmt.source = source;
			mmt.history = history;
			return mmt;
		};
		
		public void run() {
			if(l==null)
				return;
			
			if(ctxList!=null) {
				l.onContextList(prefix, ctxList);
			}
			
			if(srcList!=null) {
				l.onSourceList(prefix, context, srcList);
			}
			
			if(prpList!=null) {
				l.onPropertyList(prefix, context, source, prpList);
			}
			if(ce!=null) {
				l.onPropertyUpdate(prefix, context, source, ce);
			}
			if(history!=null) {
				l.onHistory(prefix, context, source, history);
			}
		}
	}
	
	private void invokeContextListMessage(String prefix, Map<Integer, String> cm) {
		for(Listener l : mgrListeners) {
			new ManagerMessageThread(l, prefix, cm).start();
		}
	}

	private void invokeSourceListMessage(String prefix, String context, Map<Integer, String> sm) {
		for(Listener l : mgrListeners) {
			ManagerMessageThread.forSrcList(l, prefix, context, sm).start();
		}
	}

	private void invokePropertyListMessage(String prefix, String context, String source, Map<Integer, String> pm) {
		for(Listener l : mgrListeners) {
			ManagerMessageThread.forPrpList(l, prefix, context, source, pm).start();
		}
	}

	private void invokePropertyUpdateMessage(String prefix, String context, String source, ContextElement ce) {
		for(Listener l : mgrListeners) {
			ManagerMessageThread.forPrpUpdate(l, prefix, context, source, ce).start();
		}
	}

	private void invokeHistoryMessage(String prefix, String context, String source, List<ContextElement> history) {
		for(Listener l : mgrListeners) {
			ManagerMessageThread.forHistory(l, prefix, context, source, history).start();
		}
	}
	
	private static class ContextChangeInvokeThread extends Thread {
		private static enum CCITMode { CCIT_CTX, CCIT_SRC_ADD, CCIT_SRC_REM, CCIT_PRP_ADD, CCIT_PRP_REM };
		
		private ContextListener cl;
		private Context ctx;
		private ContextElement ce;
		
		private String source;
		private String property;
		
		private CCITMode mode;
		
		public ContextChangeInvokeThread(ContextListener cl, Context ctx, ContextElement ce) {
			mode = CCITMode.CCIT_CTX;
			
			this.cl = cl;
			this.ctx = ctx;
			this.ce = ce;
		}
		
		public ContextChangeInvokeThread(ContextListener cl, Context ctx, String source, String property, boolean isSrc, boolean added) {
			mode = added ? (isSrc ? CCITMode.CCIT_SRC_ADD : CCITMode.CCIT_PRP_ADD) : (isSrc ? CCITMode.CCIT_SRC_REM : CCITMode.CCIT_PRP_REM);
			
			this.cl = cl;
			this.ctx = ctx;
			this.source = source;
			this.property = property;
		}
		
		public void run() {
			switch(mode) {
			case CCIT_CTX:
				cl.processContext(ctx, ce);
				break;
			case CCIT_PRP_ADD:
				cl.propertyAdded(ctx, source, property);
				break;
			case CCIT_PRP_REM:
				cl.propertyRemoved(ctx, source, property);
				break;
			case CCIT_SRC_ADD:
				cl.sourceAdded(ctx, source, property);
				break;
			case CCIT_SRC_REM:
				cl.sourceRemoved(ctx, source);
				break;
			}
		}
	}
	
	private void invokeProcessContext(ContextListener cl, Context ctx, ContextElement ce) {
		new ContextChangeInvokeThread(cl, ctx, ce).start();
	}
	
	private void invokeContextChange(ContextListener cl, Context ctx, String source, String property, boolean isSrc, boolean added) {
		new ContextChangeInvokeThread(cl, ctx, source, property, isSrc, added).start();
	}

	public void processContext(Context ctx, ContextElement ce) {
		for(ContextListener cl : listeners)
			invokeProcessContext(cl, ctx, ce);
	}

	public void propertyAdded(Context ctx, String source, String property) {
		for(ContextListener cl : listeners)
			invokeContextChange(cl, ctx, source, property, false, true);
	}

	public void propertyRemoved(Context ctx, String source, String property) {
		for(ContextListener cl : listeners)
			invokeContextChange(cl, ctx, source, property, false, false);
	}

	public void sourceAdded(Context ctx, String source, String property) {
		for(ContextListener cl : listeners)
			invokeContextChange(cl, ctx, source, property, true, true);
	}

	public void sourceRemoved(Context ctx, String source) {
		for(ContextListener cl : listeners)
			invokeContextChange(cl, ctx, source, null, true, false);
	}
	
	public boolean addContextListener(ContextListener cl) {
		return listeners.add(cl);
	}

	public boolean removeContextListener(ContextListener cl) {
		return listeners.remove(cl);
	}
	
	public boolean addManagerListener(Listener l) {
		return mgrListeners.add(l);
	}

	public boolean removeManagerListener(Listener l) {
		return mgrListeners.remove(l);
	}
	
	public void reset() {
		penv = new PassiveEnvironment();
		synchronized (subscriptionMap) {
			subscriptionMap.clear();
		}
	}
	
	public String requestContextList() {
		DataHolder dh = DataHolder.forCtxList(getPrefix());
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.LISTCTX);
		return dh.prefix;
	}
	
	public String requestSourceList(String context) {
		DataHolder dh = DataHolder.forSrcList(getPrefix(), context);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.LISTSRC + " @"+Util.urlencode(context));
		return dh.prefix;
	}
	
	public String requestPropertyList(String context, String source) {
		if(source.endsWith("*"))
			source = source.substring(0, source.length()-1);
		
		DataHolder dh = DataHolder.forPrpList(getPrefix(), context, source);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.LISTPRP + " @"+Util.urlencode(context) + " 1 @" + Util.urlencode(source));
		return dh.prefix;
	}
	
	public String requestPropertyUpdate(String context, String source, String property) {
		if(source.endsWith("*"))
			source = source.substring(0, source.length()-1);
		if(property.endsWith("*"))
			property = property.substring(0, property.length()-1);
		
		
		DataHolder dh = DataHolder.forPrpUpdate(getPrefix(), context, source, property);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.GETPRP + " @"+Util.urlencode(context) + " @" + Util.urlencode(source) + " @" + Util.urlencode(property));
		return dh.prefix;
	}
	
	public String requestPropertyHistory(String context, String source, String property, int limit, Set<String> withTags) {
		if(source.endsWith("*"))
			source = source.substring(0, source.length()-1);
		if(property.endsWith("*"))
			property = property.substring(0, property.length()-1);
		
		
		DataHolder dh = DataHolder.forPrpHistory(getPrefix(), context, source, property);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		// history <identifier> latest|earliest
		// history <identifier> fromNum toNum Limit
		// history <identifier> fromNum (+|r)toNum Limit
		// -> + or r --> toNum is relative
		// to == -1 -> use current Time
		// if from is < 0 -> add 'to' (or current time if to == -1)
		// if +|r is specified, 'from' is added to the value of 'to'
		//
		// history queries are biased towards the most recent results
		//
		// examples
		// history 1234 latest -> get most recent value
		// history 1234 earliest -> get first known value
		// history 1234 0 -1 10 -> get most recent 10 values
		// history 1234 -10000 -1 10 -> get most recent 10 values from the last 10 seconds
		// history 1234 1360259120 +5000 5 -> get most recent 5 values between that point in time and 5 seconds later
		// history 1234 1360259120 1360259188 10 -> get most recent 10 values between the two points in time
		StringBuilder sb = new StringBuilder();
		sb.append(Protocol.PREFIX_CHAR);
		sb.append(dh.prefix);
		sb.append(' ');
		sb.append(Protocol.HISTORY);
		sb.append('@');
		sb.append(Util.urlencode(context));
		sb.append(' ');
		sb.append('@');
		sb.append(Util.urlencode(source));
		sb.append(' ');
		sb.append('@');
		sb.append(Util.urlencode(property));
		sb.append(" get 0 -1 ");
		sb.append(limit);
		if(withTags!=null) {
			sb.append(" tags");
			for(String tag : withTags) {
				sb.append(' ');
				sb.append(Util.urlencode(tag));
			}
		}
		doCommand(sb.toString());
		return dh.prefix;
	}
	
	private String requestPrpInfo(Integer id, String ctxMessage) {
		DataHolder dh = DataHolder.forUnknownPropertyContext(getPrefix(), ctxMessage);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.GETIDINFO + " " + id);
		return dh.prefix;
	}
	
	private String setShortSub(Integer id, Object key, boolean setShort) {
		DataHolder dh = DataHolder.forShortSub(getPrefix(), key, id, setShort);
		synchronized (setHolderMap) {
			setHolderMap.put(dh.prefix, dh);
		}
		doCommand(Protocol.PREFIX_CHAR + dh.prefix + " " + Protocol.SHORTSUB + " " + (setShort ? "true " : "false ") + id);
		return dh.prefix;
	}

}

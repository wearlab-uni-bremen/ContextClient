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

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.tzi.context.abstractenv.ContextAbstraction;
import org.tzi.context.abstractenv.PassiveEnvironment;
import org.tzi.context.abstractenv.PropertyAbstraction;
import org.tzi.context.abstractenv.SourceAbstraction;
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

public class Client implements Runnable, WindowListener, ContextClientListener, ContextListener {
	
	private static final boolean debug = false;
	
	private ContextClient cc = null;
	private ContextManager cm = null;
	
	private JFrame frame;
	
	private List<JFrame> propertyDialogs = new LinkedList<JFrame>();
	
	private JTree infoTree;
	private DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode();
	private DefaultMutableTreeNode contextsNode = new DefaultMutableTreeNode("Contexts");
	private DefaultMutableTreeNode unresolvedNode = new DefaultMutableTreeNode("Unresolved");
	
	private JTextField tfName;
	private JTextField tfHost;
	private JTextField tfPort;
	private JTextField tfUserId;
	private JTextField tfCommand;
	
	private JTextArea taResponse;
	
	private static final String dTreeNode = "treenode";
	private static final String dCE = "ce";
	private static final String dPD = "propDialog";
	
	private DefaultTreeModel infoTreeModel = new DefaultTreeModel(rootNode);
	private PassiveEnvironment penv = new PassiveEnvironment();
	
	public static class UserHolder<A> {
		private String s;
		private A a;
		
		public A getData() {
			return a;
		}
		
		public void setString(String s) {
			this.s = s;
		}
		
		public UserHolder(String s, A a) {
			this.s = s;
			this.a = a;
		}
		
		public String toString() {
			return s;
		}
	}
	
	public static class ContextHolder extends UserHolder<ContextAbstraction> {

		public ContextHolder(String s, ContextAbstraction a) {
			super(s, a);
		}
	}
	
	public static class SourceHolder extends UserHolder<SourceAbstraction> {

		public SourceHolder(String s, SourceAbstraction a) {
			super(s, a);
		}
	}

	public static class PropertyHolder extends UserHolder<PropertyAbstraction> {

		public PropertyHolder(String s, PropertyAbstraction a) {
			super(s, a);
		}
	}
	
	private ContextManager.Listener mgrListener = new ContextManager.Listener() {
		
		@Override
		public void onContextList(String prefix, Map<Integer, String> cm) {
			for(Map.Entry<Integer, String> ctxe : cm.entrySet()) {
				ContextAbstraction ca = penv.injectContext(ctxe.getKey(), ctxe.getValue());
				DefaultMutableTreeNode n = (DefaultMutableTreeNode)ca.getData(dTreeNode);
				if(n==null) {
					n = new DefaultMutableTreeNode(ca.getName());
					ca.setData(dTreeNode, n);
					n.setUserObject(new ContextHolder(ca.getName(), ca));
					infoTreeModel.insertNodeInto(n, contextsNode, contextsNode.getChildCount());
				}
			}
		}
		
		@Override
		public void onSourceList(String prefix, String context, Map<Integer, String> sm) {
			ContextAbstraction ca = penv.getContextByName(context);
			DefaultMutableTreeNode caNode = ca == null ? null : (DefaultMutableTreeNode)ca.getData(dTreeNode);
			
			if(ca == null || caNode == null) {
				if(debug)
					System.out.println("Unknown context... " + context);
				return;
			}
			
			for(Map.Entry<Integer, String> srce : sm.entrySet()) {
				String sourceName = srce.getValue();
				if(sourceName.endsWith("*")) {
					sourceName = sourceName.substring(0, sourceName.length()-1);
				}
				SourceAbstraction sa = penv.injectSource(ca, srce.getKey(), sourceName);
				DefaultMutableTreeNode n = (DefaultMutableTreeNode)sa.getData(dTreeNode);
				if(n==null) {
					n = new DefaultMutableTreeNode(sa.getName());
					sa.setData(dTreeNode, n);
					n.setUserObject(new SourceHolder(sa.getName(), sa));
					infoTreeModel.insertNodeInto(n, caNode, caNode.getChildCount());
				}
			}
		}
		
		@Override
		public void onPropertyList(String prefix, String context, String source, Map<Integer, String> pm) {
			if(debug)
				System.out.println("Parsing property list");
			ContextAbstraction ca = penv.getContextByName(context);

			if(ca == null) {
				if(debug)
					System.out.println("Unknown context... " + context);
				return;
			}
			
			SourceAbstraction sa = ca.getSourceByName(source);
			DefaultMutableTreeNode saNode = sa == null ? null : (DefaultMutableTreeNode)sa.getData(dTreeNode);
			
			if(sa == null || saNode == null) {
				if(debug)
					System.out.println("Unknown source... " + source);
				return;
			}
			
			for(Map.Entry<Integer, String> prpe : pm.entrySet()) {
				String propertyName = prpe.getValue();
				if(propertyName.endsWith("*")) {
					propertyName = propertyName.substring(0, propertyName.length()-1);
				}
				PropertyAbstraction pa = penv.injectProperty(sa,prpe.getKey(), propertyName);
				DefaultMutableTreeNode n = (DefaultMutableTreeNode)pa.getData(dTreeNode);
				if(n==null) {
					n = new DefaultMutableTreeNode(pa.getName());
					pa.setData(dTreeNode, n);
					n.setUserObject(new PropertyHolder(pa.getName(), pa));
					infoTreeModel.insertNodeInto(n, saNode, saNode.getChildCount());
				}
			}
		}
		
		@Override
		public void onPropertyUpdate(String prefix, String context, String source, ContextElement ce) {
			ContextAbstraction ca = penv.getContextByName(context);
			if(ca==null) {
				return;
			}
			SourceAbstraction sa = ca.getSourceByName(source);
			if(sa==null) {
				return;
			}
			PropertyAbstraction pa = sa.getPropertyByName(ce.getPropertyIdentifier());
			if(pa==null) {
				return;
			}
			
			DefaultMutableTreeNode n = (DefaultMutableTreeNode)pa.getData(dTreeNode);
			if(n==null) 
				return;
			
			PropertyHolder ph = (PropertyHolder)n.getUserObject();
			if(ph==null)
				return;
			
			pa.set(ce.getTimestamp(), ce.getValue(), ce.getTypeTags(), ce.isPersistent());
			
			if(pa.getData(dPD)!=null)
				showPropertyDialog(pa, true);
			
			ph.getData().setData(dCE, ce);
			ph.setString(String.format("%s = %s (%d)", pa.getName(), ce.getValue(), ce.getTimestamp()));
			infoTreeModel.nodeChanged(n);
		}

		@Override
		public void onHistory(String prefix, String context, String source,
				List<ContextElement> history) {
			System.out.println("Got history with " + history.size() + " elements...");
			for(ContextElement ce : history) {
				System.out.println(ce.toString());
			}
		}
	};
	
	private AbstractAction loginAction = new AbstractAction("Login") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(cc!=null && !cc.canLogin())
				return;
			
			String hostS = tfHost.getText().trim();
			
			if(hostS.length()==0)
				return;
			
			String portS = tfPort.getText().trim();
			
			int port;
			
			try {
				port = Integer.parseInt(portS);
				if(port < 0 || port > 65535)
					throw new NumberFormatException();
				
			} catch(Exception ex) {
				return;
			}
			
			String lname = tfName.getText().trim();
			if(lname.length()==0)
				return;
			
			if(cc==null) {
				cc = new ContextClient();
				cm = new ContextManager(cc);
				cm.addManagerListener(mgrListener);
				cc.addContextClientListener(Client.this);
				cm.addContextListener(Client.this);
			}

			int rid = -1;
			if(e.getActionCommand().equals("relogin")) {
				String ridS = tfUserId.getText().trim();
				
				try {
					rid = Integer.parseInt(ridS);
				} catch(Exception re) {
					return;
				}
			}

			
			cc.init(hostS, port);
			
			if(rid >= 0) {
				cc.relogin(lname, rid);
			} else {
				cc.login(lname);
			}
		}
	};
	
	private AbstractAction logoutAction = new AbstractAction("Logout") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(cc==null)
				return;
			
			cc.logoutIfConnected();
		}
		
	};
	
	private AbstractAction sendAction = new AbstractAction("Send") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			String commandS = tfCommand.getText().trim();
			if(commandS.length()==0)
				return;
			
			if(cc==null || cc.getCommunicationState()!=CommunicationState.Connected)
				return;
			
			cc.putCommand(commandS);
		}		
	};
	
	private MouseListener treeMouseListener = new MouseAdapter() {
		
		private void checkItemContext(MouseEvent e) {
			if(!e.isPopupTrigger())
				return;
			
			TreePath tp = infoTree.getSelectionModel().getSelectionPath();
			
			if(tp==null)
				return;
			
			DefaultMutableTreeNode n = (DefaultMutableTreeNode)tp.getLastPathComponent();
			if(n==null)
				return;

			Point p = e.getPoint();
			if(n == contextsNode) {
				contextsMenu.show(frame, p.x, p.y);
				return;
			}
			
			if(n.getUserObject() instanceof ContextHolder) {
				contextMenu.show(frame, p.x, p.y);
				return;
			}

			if(n.getUserObject() instanceof SourceHolder) {
				sourceMenu.show(frame, p.x, p.y);
				return;
			}

			if(n.getUserObject() instanceof PropertyHolder) {
				propertyMenu.show(frame, p.x, p.y);
				return;
			}
		}
		
		
		
		@Override
		public void mousePressed(MouseEvent e) {
			if(e.isPopupTrigger()) {
				Point p = e.getPoint();
				TreePath tp = infoTree.getClosestPathForLocation(p.x, p.y);
				if(tp!=null) {
					infoTree.setSelectionPath(tp);
				}
			}
			
			checkItemContext(e);
		}



		@Override
		public void mouseReleased(MouseEvent e) {
			checkItemContext(e);
		}

		@Override
		public void mouseClicked(MouseEvent e) {
			checkItemContext(e);
		}
	};
	
	private static final String acUpdateContexts = "updatecontexts";
	private static final String acUpdateSources = "updatesources";
	private static final String acUpdateProperties = "updateproperties";
	private static final String acUpdateProperty = "updateproperty";
	private static final String acShowPropertyDialog = "propertydialog";
	private static final String acSubscribe = "subscribe";
	private static final String acHistory = "gethistory";
	
	private JPopupMenu contextsMenu;
	private JPopupMenu contextMenu;
	private JPopupMenu sourceMenu;
	private JPopupMenu propertyMenu;
	
	private static JMenuItem setACAndText(JMenuItem jmi, String actionCommand, String text, Character mnemonic) {
		jmi.setActionCommand(actionCommand);
		jmi.setText(text);
		if(mnemonic!=null)
			jmi.setMnemonic(mnemonic);
		return jmi;
	}
	
	private AbstractAction contextsAction = new AbstractAction("Contexts") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(acUpdateContexts.equals(e.getActionCommand())) {
				if(cm!=null) {
					cm.requestContextList();
				}
			}
		}
	};
	
	private AbstractAction contextAction = new AbstractAction("Context") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(acUpdateSources.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 3)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[2] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[2];
					
					ContextHolder ch = (ContextHolder)n.getUserObject();
					if(ch == null)
						return;

					if(cm!=null) {
						cm.requestSourceList(ch.getData().getName());
					}
				}
			}
		}
	};
	
	private AbstractAction sourceAction = new AbstractAction("Source") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(acUpdateProperties.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 4)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[3] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[3];
					
					SourceHolder sh = (SourceHolder)n.getUserObject();
					if(sh == null)
						return;

					if(cm!=null) {
						SourceAbstraction sa = sh.getData();
						cm.requestPropertyList(sa.getContext().getName(), sa.getName());
					}
				}
			}
		}
	};

	private AbstractAction propertyAction = new AbstractAction("Property") {
		private static final long serialVersionUID = 1L;

		@Override
		public void actionPerformed(ActionEvent e) {
			if(acUpdateProperty.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 5)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[4] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[4];
					
					PropertyHolder ph = (PropertyHolder)n.getUserObject();
					if(ph == null)
						continue;

					if(cm!=null) {
						PropertyAbstraction pa = ph.getData();
						cm.requestPropertyUpdate(pa.getSource().getContext().getName(), pa.getSource().getName(), pa.getName());
					}
				}
			}
			if(acHistory.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 5)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[4] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[4];
					
					PropertyHolder ph = (PropertyHolder)n.getUserObject();
					if(ph == null)
						continue;

					if(cm!=null) {
						PropertyAbstraction pa = ph.getData();
						cm.requestPropertyHistory(pa.getSource().getContext().getName(), pa.getSource().getName(), pa.getName(), 10, null);
					}
				}
			}
			if(acShowPropertyDialog.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 5)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[4] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[4];
					
					PropertyHolder ph = (PropertyHolder)n.getUserObject();
					if(ph == null)
						continue;
					
					showPropertyDialog(ph.getData(), false);
				}
			}
			if(acSubscribe.equals(e.getActionCommand())) {
				for(TreePath tp : infoTree.getSelectionPaths()) {
					if(tp.getPathCount() < 5)
						continue;
					Object [] objs = tp.getPath();
					if(! (objs[4] instanceof DefaultMutableTreeNode) )
						continue;
					
					DefaultMutableTreeNode n = (DefaultMutableTreeNode)objs[4];
					
					PropertyHolder ph = (PropertyHolder)n.getUserObject();
					if(ph == null)
						continue;
					
					PropertyAbstraction pa = ph.getData();
					
					if(pa!=null) {
						cm.subscribe(pa.getSource().getContext().getName(), pa.getSource().getName(), pa.getName());
					}
				}
			}
		}
	};
	
	private static final String pdValue = "value";
	private static final String pdTimestamp = "timestamp";
	private static final String pdTags = "tags";
	private static final String pdPersistent = "persistent";
	
	private static final String acAddEnc = "aenc";
	private static final String acUpdate = "update";
	private static final String acSet = "set";
	
	public class PropertyAction extends AbstractAction {
		private static final long serialVersionUID = 1L;
		
		private PropertyAbstraction pa;

		public PropertyAction(PropertyAbstraction pa) {
			super();
			this.pa = pa;
		}



		public PropertyAction(PropertyAbstraction pa, String name, Icon icon) {
			super(name, icon);
			this.pa = pa;
		}



		public PropertyAction(PropertyAbstraction pa, String name) {
			super(name);
			this.pa = pa;
		}



		@Override
		public void actionPerformed(ActionEvent e) {
			if(acAddEnc.equals(e.getActionCommand())) {
				JFrame pdiag = (JFrame)pa.getData(dPD);
				String newTag = JOptionPane.showInputDialog(pdiag, "enter tag to encode", "Add Tag", JOptionPane.PLAIN_MESSAGE);
				
				if(newTag!=null)
					newTag = newTag.trim();
				
				if(newTag!=null && newTag.length()>0) {
					JPanel contentPane = (JPanel)pdiag.getContentPane();
					JTextField tfTags = (JTextField)contentPane.getClientProperty(pdTags);
					
					String oldTags = tfTags.getText();
					tfTags.setText(((oldTags.length()>0)?oldTags+" ":"")+Util.urlencode(newTag));
				}
			}
			if(acUpdate.equals(e.getActionCommand())) {
				cm.requestPropertyUpdate(pa.getSource().getContext().getName(), pa.getSource().getName(), pa.getName());
			}
			if(acSet.equals(e.getActionCommand())) {
				JFrame pdiag = (JFrame)pa.getData(dPD);
				JPanel contentPane = (JPanel)pdiag.getContentPane();
				
				JTextField tfValue = (JTextField)contentPane.getClientProperty(pdValue);
				JTextField tfTimestamp = (JTextField)contentPane.getClientProperty(pdTimestamp);
				JTextField tfTags = (JTextField)contentPane.getClientProperty(pdTags);
				JCheckBox cbPersistent = (JCheckBox)contentPane.getClientProperty(pdPersistent);

				try {
					long ts = Long.parseLong(tfTimestamp.getText());
					String [] tags = Util.splitWS(tfTags.getText());
					List<String> tagList = new LinkedList<String>();
					for(String tag : tags) {
						tagList.add(Util.urldecode(tag));
					}
					
					String ctx = pa.getSource().getContext().getName();
					String src = pa.getSource().getName();
					String prp = pa.getName();
					cm.setProperty(ctx, src, prp, tfValue.getText().trim(), tagList, ts, cbPersistent.isSelected());
				} catch(NumberFormatException nfe) {
					JOptionPane.showMessageDialog(pdiag, "The timestamp value was invalid!", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
		
	}
	
	public void showPropertyDialog(PropertyAbstraction pa, boolean isUpdate) {
		JFrame pdiag = (JFrame)pa.getData(dPD);
		
		if(pdiag == null) {
			if(isUpdate)
				return;
			
			pdiag = new JFrame("Property " + pa.getName(), frame.getGraphicsConfiguration());
			pdiag.setLocationRelativeTo(frame);
			pdiag.setLocationByPlatform(true);
			pdiag.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
			
			JPanel diagPanel = new JPanel();
			GridBagLayout gbl = new GridBagLayout();
			GridBagConstraints gbc = new GridBagConstraints();
			
			diagPanel.setLayout(gbl);
			
			
			gbc.insets.right = 5;
			gbc.insets.bottom = 2;
			gbc.gridwidth = 1;
			gbc.gridheight = 1;
			gbc.anchor = GridBagConstraints.LINE_START;

			JLabel l = new JLabel("Value");
			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(l, gbc);
			diagPanel.add(l);

			JTextField tfValue = new JTextField(20);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(tfValue, gbc);
			diagPanel.add(tfValue);
			diagPanel.putClientProperty(pdValue, tfValue);
			
			l = new JLabel("Timestamp");
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(l, gbc);
			diagPanel.add(l);
			
			JTextField tfTimestamp = new JTextField(20);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(tfTimestamp, gbc);
			diagPanel.add(tfTimestamp);
			diagPanel.putClientProperty(pdTimestamp, tfTimestamp);
			
			l = new JLabel("Tags");
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(l, gbc);
			diagPanel.add(l);
			
			JTextField tfTags = new JTextField(20);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(tfTags, gbc);
			diagPanel.add(tfTags);
			diagPanel.putClientProperty(pdTags, tfTags);
			
			JCheckBox cbPersistent = new JCheckBox("Persistent");
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(cbPersistent, gbc);
			diagPanel.add(cbPersistent);
			diagPanel.putClientProperty(pdPersistent, cbPersistent);
			
			PropertyAction prpAction = new PropertyAction(pa);
			
			JButton btnAddTag = new JButton(prpAction);
			btnAddTag.setText("Add Encoded Tag...");
			btnAddTag.setActionCommand(acAddEnc);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(btnAddTag, gbc);
			diagPanel.add(btnAddTag);

			JButton btnUpdate = new JButton(prpAction);
			btnUpdate.setText("Get Update");
			btnUpdate.setActionCommand(acUpdate);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(btnUpdate, gbc);
			diagPanel.add(btnUpdate);

			JButton btnSet = new JButton(prpAction);
			btnSet.setText("Set Property");
			btnSet.setActionCommand(acSet);
			gbc.gridx = 0;
			gbc.gridy++;
			gbc.fill = GridBagConstraints.NONE;
			gbl.setConstraints(btnSet, gbc);
			diagPanel.add(btnSet);

			pdiag.setContentPane(diagPanel);
			propertyDialogs.add(pdiag);
			pa.setData(dPD, pdiag);
		}
		
		if(isUpdate && !pdiag.isVisible())
			return;
		
		JPanel contentPane = (JPanel)pdiag.getContentPane();
		
		JTextField tfValue = (JTextField)contentPane.getClientProperty(pdValue);
		JTextField tfTimestamp = (JTextField)contentPane.getClientProperty(pdTimestamp);
		JTextField tfTags = (JTextField)contentPane.getClientProperty(pdTags);
		JCheckBox cbPersistent = (JCheckBox)contentPane.getClientProperty(pdPersistent);

		StringBuilder sb = new StringBuilder();
		for(String tag : pa.getTags()) {
			if(sb.length()>0)
				sb.append(" ");
			sb.append(Util.urlencode(tag));
		}
		
		tfValue.setText(pa.getValue());
		tfTimestamp.setText(Long.toString(pa.getTimestamp()));
		tfTags.setText(sb.toString());
		cbPersistent.setSelected(pa.isPersistent());
		
		pdiag.pack();
		pdiag.setVisible(true);		
	}
	
	public void run() {
		logoutAction.setEnabled(false);
		frame = new JFrame("ContextClient");
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(this);
		
		frame.setLayout(new BorderLayout());
		
		infoTreeModel.insertNodeInto(unresolvedNode, rootNode, 0);
		infoTreeModel.insertNodeInto(contextsNode, rootNode, 0);
		
		
		
		frame.add(infoTree = new JTree(), BorderLayout.CENTER);
		infoTree.setRootVisible(false);
		infoTree.setModel(infoTreeModel);
		
		infoTree.addMouseListener(treeMouseListener);
		
		JPanel loginControl = new JPanel();
		loginControl.setBorder(BorderFactory.createTitledBorder("Login Controls"));

		//loginControl.setLayout(new BoxLayout(loginControl, BoxLayout.PAGE_AXIS));
		GridBagLayout gbl = new GridBagLayout();
		loginControl.setLayout(gbl);
		
		JLabel userLabel = new JLabel("User");
		JLabel hostLabel = new JLabel("Host");
		JLabel portLabel = new JLabel("Port");
		JLabel userIdLabel = new JLabel("UserID");
		
		tfName = new JTextField("user", 10);
		tfHost = new JTextField("localhost", 10);
		tfPort = new JTextField(""+Protocol.standardPort, 10);
		tfUserId = new JTextField("", 10);
		
		JButton loginButton = new JButton(loginAction);
		JButton reloginButton = new JButton(loginAction);
		reloginButton.setText("ReLogin");
		reloginButton.setActionCommand("relogin");
		JButton logoutButton = new JButton(logoutAction);
		
		GridBagConstraints gbc = new GridBagConstraints();

		gbc.insets = new Insets(2, 5, 2, 5);
		gbc.anchor = GridBagConstraints.LINE_START;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridy = 0;
		gbc.gridx = 0;
		
		gbl.setConstraints(userLabel, gbc);
		loginControl.add(userLabel);

		gbc.gridx++;
		gbc.gridwidth = 2;
		gbl.setConstraints(tfName, gbc);
		loginControl.add(tfName);
		
		gbc.gridy++;
		gbc.gridx = 0;
		
		gbc.gridwidth = 1;
		gbl.setConstraints(hostLabel, gbc);
		loginControl.add(hostLabel);

		gbc.gridx++;
		gbc.gridwidth = 2;
		gbl.setConstraints(tfHost, gbc);
		loginControl.add(tfHost);
		
		gbc.gridy++;
		gbc.gridx = 0;
		
		gbc.gridwidth = 1;
		gbl.setConstraints(portLabel, gbc);
		loginControl.add(portLabel);

		gbc.gridx++;
		gbc.gridwidth = 2;
		gbl.setConstraints(tfPort, gbc);
		loginControl.add(tfPort);

		gbc.gridy++;
		gbc.gridx = 0;
		
		gbc.gridwidth = 1;
		gbl.setConstraints(userIdLabel, gbc);
		loginControl.add(userIdLabel);

		gbc.gridx++;
		gbc.gridwidth = 2;
		gbl.setConstraints(tfUserId, gbc);
		loginControl.add(tfUserId);
		
		gbc.gridy++;
		gbc.gridx = 0;
		gbc.gridwidth = 1;
		
		gbl.setConstraints(loginButton, gbc);
		loginControl.add(loginButton);
		
		gbc.gridx++;
		gbl.setConstraints(reloginButton, gbc);
		loginControl.add(reloginButton);
		
		gbc.gridx++;
		gbl.setConstraints(logoutButton, gbc);
		loginControl.add(logoutButton);

		
		JPanel eastP = new JPanel();
		eastP.setLayout(new FlowLayout());
		eastP.add(loginControl);
		eastP.add(Box.createGlue());
		
		frame.add(eastP, BorderLayout.EAST);
		
		JPanel southP = new JPanel();
		
		gbl = new GridBagLayout();
		southP.setLayout(gbl);
		
		gbc = new GridBagConstraints();

		gbc.insets = new Insets(2, 5, 2, 5);
		gbc.anchor = GridBagConstraints.LINE_START;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridy = 0;
		gbc.gridx = 0;
		gbc.weightx = 1.0;
		gbc.weighty = 1.0;
		
		gbc.fill = GridBagConstraints.HORIZONTAL;
		
		tfCommand = new JTextField(80);
		gbl.setConstraints(tfCommand, gbc);
		southP.add(tfCommand);
		tfCommand.setAction(sendAction);
		
		JButton sendButton = new JButton(sendAction);
		
		gbc.weightx = 0.0;
		gbc.weighty = 1.0;
		gbc.fill = GridBagConstraints.NONE;
		gbc.gridx = 1;
		
		gbl.setConstraints(sendButton, gbc);
		southP.add(sendButton);

		taResponse = new JTextArea(5, 80);
		taResponse.setEditable(false);
		
		gbc.weightx = 1.0;
		gbc.weighty = 1.0;
		gbc.gridwidth = 2;
		gbc.fill = GridBagConstraints.BOTH;
		gbc.gridy = 1;
		gbc.gridx = 0;
		
		JScrollPane jsp = new JScrollPane(taResponse);
		
		gbl.setConstraints(jsp, gbc);
		southP.add(jsp);
		
		
		frame.add(southP, BorderLayout.SOUTH);
		
		contextsMenu = new JPopupMenu("Contexts");
		contextsMenu.add(setACAndText(new JMenuItem(contextsAction), acUpdateContexts, "Update Contexts", 'U'));

		contextMenu = new JPopupMenu("Context");
		contextMenu.add(setACAndText(new JMenuItem(contextAction), acUpdateSources, "Update Sources", 'S'));

		sourceMenu = new JPopupMenu("Source");
		sourceMenu.add(setACAndText(new JMenuItem(sourceAction), acUpdateProperties, "Update Properties", 'P'));

		propertyMenu = new JPopupMenu("Property");
		propertyMenu.add(setACAndText(new JMenuItem(propertyAction), acUpdateProperty, "Update Property", 'P'));
		propertyMenu.add(setACAndText(new JMenuItem(propertyAction), acShowPropertyDialog, "Edit...", 'E'));
		propertyMenu.add(setACAndText(new JMenuItem(propertyAction), acSubscribe, "Subscribe", 'S'));
		propertyMenu.add(setACAndText(new JMenuItem(propertyAction), acHistory, "Get History", 'H'));
		
		frame.pack();
		frame.setVisible(true);
	}
	
	public static void main(String...args) {
		EventQueue.invokeLater(new Client());
	}

	@Override
	public void windowOpened(WindowEvent e) {
	}

	@Override
	public void windowClosing(WindowEvent e) {
		for(JFrame pf : propertyDialogs) {
			pf.dispose();
		}
		frame.dispose();
		if(cc!=null) {
			cc.logoutIfConnected();
			cc.terminate();
			cc = null;
		}
	}

	@Override
	public void windowClosed(WindowEvent e) {
	}

	@Override
	public void windowIconified(WindowEvent e) {
	}

	@Override
	public void windowDeiconified(WindowEvent e) {
	}

	@Override
	public void windowActivated(WindowEvent e) {
	}

	@Override
	public void windowDeactivated(WindowEvent e) {
	}

	@Override
	public void processCommunicationState(CommunicationState state) {
		switch(state) {
		case Connecting:
			loginAction.setEnabled(false);
			break;
		case Connected:
			logoutAction.setEnabled(true);
			tfUserId.setText(""+cc.getID());
			break;
		case Disconnected:
			tfUserId.setText("");
			loginAction.setEnabled(true);
			logoutAction.setEnabled(false);
			break;
		case Failure:
			loginAction.setEnabled(true);
			logoutAction.setEnabled(false);
			break;
		}
	}
	
	synchronized private void addTextLine(String l) {
		taResponse.setText(taResponse.getText() + "\n" + l);
		
		EventQueue.invokeLater(new Runnable() {
			
			private int count = 0;
			
			public void run() {
				int l = taResponse.getDocument().getLength();
				taResponse.setCaretPosition(l);
				Point pos = taResponse.getCaret().getMagicCaretPosition();
				
				// this is needed because I am too lazy to 
				// put the text-adding in a another EventQueue.invoke call
				// and start this thread from there...
				// success is always in the second run
				if(pos == null) {
					if(count < 10) {
						count++;
						EventQueue.invokeLater(this);
					}
				} else {
					taResponse.scrollRectToVisible(new Rectangle(0, pos.y, 1, 1));
				}
			}
		});
	}

	@Override
	public void processContextInformation(String message) {
		addTextLine("+++ " + message);
	}

	@Override
	public void processCommandResult(String command, String result) {
		addTextLine(">>> " + command + "\n" + "<<< " + result);
	}

	@Override
	public ContextListenerInterface getProperties() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void processContext(Context ctx, ContextElement ce) {
		mgrListener.onPropertyUpdate(null, ctx.getName(), ce.getSourceIdentifier(), ce);
	}
	
	@Override
	public void sourceAdded(Context ctx, String source, String property) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void propertyAdded(Context ctx, String source, String property) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void sourceRemoved(Context ctx, String source) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void propertyRemoved(Context ctx, String source, String property) {
		// TODO Auto-generated method stub
		
	}

}

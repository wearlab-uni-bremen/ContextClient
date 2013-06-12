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


import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Box.Filler;
import javax.swing.border.BevelBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import org.tzi.context.client.ContextClient;
import org.tzi.context.client.ContextClientListener;
import org.tzi.context.client.ContextClient.CommunicationState;
import org.tzi.context.common.Context;
import org.tzi.context.common.ContextElement;
import org.tzi.context.common.ContextMessage;
import org.tzi.context.common.Protocol;
import org.tzi.context.common.Util;


public class ClientUI extends JPanel implements TreeSelectionListener {
	private static final long serialVersionUID = 1L;

	private JTree contextTree;
	private DefaultTreeModel contextTreeModel; 
	private DefaultMutableTreeNode rootNode;
	
	private DefaultMutableTreeNode selectedNode = null;
	
	private JPopupMenu rootUpdateMenu;
	private JPopupMenu contextUpdateMenu;
	private JPopupMenu sourceUpdateMenu;
	private JPopupMenu propertyUpdateMenu;

	private Set<Integer> knownIds = new TreeSet<Integer>();
	private Set<Integer> virtualIds = new TreeSet<Integer>();
	
	private Map<String, PictureFrame> pictureMap = new TreeMap<String, PictureFrame>(); 
	
	private JLabel statusLabel;
	
	private Random rnd = new Random();
	
	private void addKnownId(Integer id) {
		if(virtualIds.contains(id))
			return;
		
		knownIds.add(id);
	}
	
	private boolean isVirtualId(Integer id) {
		return virtualIds.contains(id);
	}
	
	private Integer createVirtualId() {
		Integer vid;
		
		while( (vid = rnd.nextInt()) <= 0 || knownIds.contains(vid) || virtualIds.contains(vid) );

		virtualIds.add(vid);
		
		return vid;
	}
	
	private void removeVirtualId(Integer vid) {
		virtualIds.remove(vid);
	}
	
	private Map<Integer, ContextNode> ctxNodeMap = new TreeMap<Integer, ContextNode>();
	private Map<Integer, SourceNode> srcNodeMap = new TreeMap<Integer, SourceNode>();
	private Map<Integer, PropertyNode> prpNodeMap = new TreeMap<Integer, PropertyNode>();

	private ContextClient cc;
	
	private ButtonAction ba = new ButtonAction();
	
	private JScrollPane ctxScrollPane;
	private JPanel interactionPanel = new JPanel();
	private JTextField tf_Server = new JTextField("localhost", 10);
	private JTextField tf_Port = new JTextField(Integer.toString(Protocol.standardPort), 5);
	private JTextField tf_Login = new JTextField("user", 10);
	private JTextField tf_ID = new JTextField("-1", 10);
	
	private JButton b_Login = new JButton(ba);
	private JButton b_ReLogin = new JButton(ba);
	private JButton b_Logout = new JButton(ba);
	private JButton b_SubAll = new JButton(ba);
	
	private JCheckBox cb_autoExpand = new JCheckBox("Auto-Expand", true);
	
	private JPanel commandPanel = new JPanel(new FlowLayout());
	private JTextField tf_Command = new JTextField(40);
	private JButton b_Send = new JButton(ba);
	
	private JFrame frame;
	private JDialog propertySetDialog;
	private JTextField propertyValue;
	private JTextField propertyTimestamp;
	private JTextField propertyTags;
	private JCheckBox propertyPersistent;
	
	private PropertyNode newNode = null;
	
	private AbstractAction loadBinaryDataAction = new AbstractAction("Load Binary") {
		private static final long serialVersionUID = 1L;
		
		private JFileChooser fileChooser = null;
		
		@Override
		public void actionPerformed(ActionEvent e) {
			if(fileChooser==null) {
				fileChooser = new JFileChooser(new File("."));
				fileChooser.setMultiSelectionEnabled(false);
			}
			
			if(fileChooser.showOpenDialog(propertySetDialog) == JFileChooser.APPROVE_OPTION) {
				File f = fileChooser.getSelectedFile();
				
				if(!f.canRead()) {
					JOptionPane.showMessageDialog(propertySetDialog, "Unable to read from selected file!", "Error", JOptionPane.ERROR_MESSAGE);
					return;
				}
				
				try {
					FileInputStream fis = new FileInputStream(f);
					
					byte [] buffer = new byte [1024];
					int r;
					ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
					
					while((r = fis.read(buffer)) > 0) {
						baos.write(buffer, 0, r);
					}
					
					byte [] data = baos.toByteArray();
					baos.reset();
					
					ByteArrayInputStream bais = new ByteArrayInputStream(data);
					
					Util.encodeBase64(bais, baos);
					
					byte [] encoded = baos.toByteArray();
					
					String valueString = new String(encoded);
					
					propertyValue.setText(valueString);
					
				} catch (FileNotFoundException e1) {
				} catch (IOException e2) {
					JOptionPane.showMessageDialog(propertySetDialog, "Error while reading from selected file!", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	};
	
	public void propertyInput(PropertyNode previous) {
		if(propertySetDialog==null) {
			propertySetDialog = new JDialog(frame, true);
			propertySetDialog.setTitle("Property");
			propertySetDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
			
			AbstractAction escapeAction = new AbstractAction("Escape") {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					propertySetDialog.setVisible(false);
				}
			};
			
			propertySetDialog.getRootPane().getActionMap().put(escapeAction.getValue(Action.NAME), escapeAction);
			propertySetDialog.getRootPane().getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), escapeAction.getValue(Action.NAME));
			
			Container c = propertySetDialog.getContentPane();
			c.setLayout(new BoxLayout(c, BoxLayout.PAGE_AXIS));
			
			JPanel tmpPanel;
			
			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JLabel("Value:"));
			tmpPanel.add(new JButton(loadBinaryDataAction));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(propertyValue = new JTextField(12));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JLabel("Timestamp (-1 == Server-Time):"));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(propertyTimestamp = new JTextField(12));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JButton(new AbstractAction("Current time") {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					propertyTimestamp.setText(Long.toString(System.currentTimeMillis()));
				}
			}));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JLabel("Tags (seperate by space)"));
			
			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(propertyTags = new JTextField(12));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JButton(new AbstractAction("Add Encode") {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					String tag = JOptionPane.showInputDialog(propertySetDialog, "Enter Tag-Text");
					if(tag!=null) {
						propertyTags.setText(propertyTags.getText() + " " + Util.urlencode(tag));
					}
				}
			}));
			
			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(propertyPersistent = new JCheckBox("Persistent"));

			tmpPanel = new JPanel();
			c.add(tmpPanel);
			tmpPanel.add(new JButton(new AbstractAction("OK") {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					try {
						long timestamp = Long.parseLong(propertyTimestamp.getText());
						
						Set<String> tagSet = new HashSet<String>();
						
						String [] taga = propertyTags.getText().split(" ");
						
						for(String tag : taga) {
							tag = tag.trim();
							if(tag.length()==0)
								continue;
							
							tagSet.add(tag);
						}
						
						newNode = new PropertyNode("newNode", -1, propertyValue.getText(), timestamp, tagSet, propertyPersistent.isSelected());
						
						propertySetDialog.setVisible(false);
					} catch(NumberFormatException nfe) {
						JOptionPane.showMessageDialog(propertySetDialog, "Invalid Timestamp: " + propertyTimestamp.getText(), "Invalid Timestamp", JOptionPane.ERROR_MESSAGE);
					}
				}
			}));
			
			propertySetDialog.pack();
		}
		if(previous!=null) {
			propertyValue.setText(previous.value);
			propertyTimestamp.setText(Long.toString(previous.timeStamp));
			StringBuilder tagsb = new StringBuilder();
			for(String tag : previous.tags) {
				if(tagsb.length()>0)
					tagsb.append(' ');
				tagsb.append(Util.urlencode(tag));
			}
			propertyTags.setText(tagsb.toString());
			propertyPersistent.setSelected(previous.isPersistent);
		} else {
			propertyValue.setText("");
			propertyTimestamp.setText("-1");
			propertyTags.setText("");
			propertyPersistent.setSelected(false);
		}
		propertySetDialog.setVisible(true);
	}
	
	private PropertyNode getPropertyInfo(PropertyNode previous) {
		newNode = null;
		propertyInput(previous);
		return newNode;
	}
	
	private class PopupActionListener implements ActionListener, ClipboardOwner {
		
		private String getUserInput(String msg) {
			return JOptionPane.showInputDialog(ClientUI.this, msg);
		}
		
		private void createNewContext() {
			String ctxName = getUserInput("Enter name for new context");
			if(ctxName == null || ctxName.length() < 1)
				return;
			
			cc.putCommand(Protocol.CREATECTX + " " + Util.urlencode(ctxName));
		}
		
		private void createNewSource(Integer ctxId) {
			String srcName = getUserInput("Enter name for new source");
			if(srcName == null || srcName.length() < 1)
				return;
			cc.putCommand(Protocol.CREATESRC + " " + ctxId + " " + Util.urlencode(srcName));
		}

		private void createNewProperty(Integer ctxId, Integer srcId) {
			String prpName = getUserInput("Enter name for new property");
			if(prpName == null || prpName.length() < 1)
				return;
			cc.putCommand(Protocol.CREATEPRP + " " + ctxId + " " + srcId + " " + Util.urlencode(prpName));
		}
		
		private void setProperty(PropertyNode previous) {
			PropertyNode newNode = getPropertyInfo(previous);
			if(newNode == null)
				return;
			StringBuilder tagStringBuilder = new StringBuilder();
			// tags are already url-encoded
			for(String tag : newNode.tags) {
				if(tagStringBuilder.length()>0)
					tagStringBuilder.append(' ');
				tagStringBuilder.append(tag);
			}
			String setcommand = String.format("%s %d = %s %d %d %s%s",
					Protocol.SETPRP, previous.prpId
					, Util.urlencode(newNode.value)
					, newNode.timeStamp
					, newNode.tags.size()
					, tagStringBuilder.toString()
					, newNode.isPersistent ? " P" : ""
					);
			cc.putCommand(setcommand);
		}
		
		private void viewProperty(PropertyNode pn) {
			String value = pn.value;
			ByteArrayInputStream bais = new ByteArrayInputStream(value.getBytes());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			
			String fullName = ((ContextNode)((SourceNode)pn.getParent()).getParent()).ctx.getName();
			fullName += "." + ((SourceNode)pn.getParent()).sourceName;
			fullName += "." + pn.propertyName;
			
			PictureFrame pf = pictureMap.get(fullName);
			
			try {
				Util.decodeBase64(bais, baos);
				BufferedImage bi = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
				if(bi == null) {
					JOptionPane.showMessageDialog(frame, "Can't convert to image...", "No Image", JOptionPane.INFORMATION_MESSAGE);
					if(pf!=null) {
						pf.setImage(null);
					}
				} else {
					
					if(pf==null) {
						pf = new PictureFrame(fullName, bi);
						pictureMap.put(fullName, pf);
					} else {
						pf.setImage(bi);
					}
					
					pf.setVisible(true);
				}
				
			} catch (IOException e) {
				JOptionPane.showMessageDialog(frame, "Unable to view data: " + e.getMessage(), "Error when viewing...", JOptionPane.ERROR_MESSAGE);
				if(pf!=null) {
					pf.setImage(null);
				}
			}
		}

		public void actionPerformed(ActionEvent e) {
			if(cc==null)
				return;
			
			if(e.getActionCommand().equalsIgnoreCase("NEWCTX")) {
				createNewContext();
			}
			if(e.getActionCommand().equalsIgnoreCase("NEWSRC")) {
				ContextNode cn = (ContextNode)selectedNode;
				if(cn != null) {
					createNewSource(cn.ctxId);
				}
			}
			if(e.getActionCommand().equalsIgnoreCase("NEWPRP")) {
				SourceNode sn = (SourceNode)selectedNode;
				if(sn != null) {
					ContextNode cn = (ContextNode)sn.getParent();
					createNewProperty(cn.ctxId, sn.srcId);
				}
			}
			if(e.getActionCommand().equalsIgnoreCase("SETPRP")) {
				PropertyNode pn = (PropertyNode)selectedNode;
				if(pn != null) {
					setProperty(pn);
				}
			}
			if(e.getActionCommand().equalsIgnoreCase("VIEW")) {
				PropertyNode pn = (PropertyNode)selectedNode;
				if(pn != null) {
					viewProperty(pn);
				}
			}
			
			if(e.getActionCommand().equalsIgnoreCase("UPDATECTX")) {
				cc.putCommand(Protocol.LISTCTX);
			}
			if(e.getActionCommand().equalsIgnoreCase("UPDATESRC")) {
				if(selectedNode == rootNode) {
					cc.putCommand(Protocol.LISTCTX);
					cc.putCommand(Protocol.LISTSRC);
				} else {
					int ctxId = ((ContextNode)selectedNode).ctxId;
					cc.putCommand(Protocol.LISTSRC + " " + ctxId);
				}
			}
			if(e.getActionCommand().equalsIgnoreCase("UPDATEPRP")) {
				if(selectedNode instanceof SourceNode) {
				SourceNode sn = (SourceNode)selectedNode;
				ContextNode cn = (ContextNode)sn.getParent();
					cc.putCommand(Protocol.LISTPRP + " " + cn.getContextId() + " 1 " + sn.srcId);
				} else {
					ContextNode cn = (ContextNode)selectedNode;
					cc.putCommand(Protocol.LISTPRP + " " + cn.getContextId() + " 0");
				}
				
			}
			if(e.getActionCommand().equalsIgnoreCase("GETPRP")) {
				PropertyNode pn = (PropertyNode)selectedNode;
				cc.putCommand(Protocol.GETPRP + " " + pn.prpId);
			}
			StringSelection strsel = null;
			if(e.getActionCommand().equalsIgnoreCase("CLIPID")) {
				int id = (selectedNode instanceof PropertyNode) ?
							  ((PropertyNode)selectedNode).prpId
							: (selectedNode instanceof SourceNode) ?
							  ((SourceNode)selectedNode).srcId
							  : (selectedNode instanceof ContextNode) ?
									  ((ContextNode)selectedNode).ctxId
									  : -1;
				if(id != -1)
					strsel = new StringSelection("" + id);
			}
			if(strsel != null) {
				Clipboard clp = Toolkit.getDefaultToolkit().getSystemClipboard();
				clp.setContents(strsel, this);
			}
		}

		public void lostOwnership(Clipboard clipboard, Transferable contents) {
		}
		
	}
	
	private PopupActionListener pal = new PopupActionListener();
	
	private Thread statusUpdater = new Thread() {
		{
			setDaemon(true);
		}
		
		private String updateText = "Ready";
		
		private Runnable updater = new Runnable() {
			
			@Override
			public void run() {
				statusLabel.setText(updateText);
			}
		};
		
		public void run() {
			StringBuilder sb = new StringBuilder();
			
			int [] txToStates = new int [2];
			int [] txFromStates = new int [2];
			
			while(isAlive() && frame.isDisplayable()) {
				sb.setLength(0);
				
				if(cc!=null) {
					sb.append(cc.getCommunicationState());
					
					if(cc.getTXFromServerStates(txFromStates)[0]>0) {
						sb.append(String.format(" Receiving %d/%d", txFromStates[1], txFromStates[0]));
					}
					if(cc.getTXToServerStates(txToStates)[0]>0) {
						sb.append(String.format(" Sending %d/%d", txToStates[1], txToStates[0]));
					}
				}
				else 
					sb.append("Ready");
				
				updateText = sb.toString();
				
				EventQueue.invokeLater(updater);
				
				try {
					sleep(1000);
				} catch (InterruptedException e) {
				}
			}
		}
	};
	
	public ClientUI(JFrame frame) {
		super();
		this.frame = frame;
		setLayout(new BorderLayout());
		rootNode = new DefaultMutableTreeNode("Contexts");
		contextTreeModel = new DefaultTreeModel(rootNode);
		contextTree = new JTree(contextTreeModel);
		
		contextTree.addTreeSelectionListener(this);
		
		JMenuItem mi;
		rootUpdateMenu = new JPopupMenu("Root");
		mi = new JMenuItem("Update contexts");
		mi.setActionCommand("UPDATECTX");
		mi.addActionListener(pal);
		rootUpdateMenu.add(mi);
		mi = new JMenuItem("Update sources");
		mi.setActionCommand("UPDATESRC");
		mi.addActionListener(pal);
		rootUpdateMenu.add(mi);
		mi = new JMenuItem("Create new context");
		mi.setActionCommand("NEWCTX");
		mi.addActionListener(pal);
		rootUpdateMenu.add(mi);

		contextUpdateMenu = new JPopupMenu("Context");
		mi = new JMenuItem("Update sources");
		mi.setActionCommand("UPDATESRC");
		mi.addActionListener(pal);
		contextUpdateMenu.add(mi);
		mi = new JMenuItem("Update properties");
		mi.setActionCommand("UPDATEPRP");
		mi.addActionListener(pal);
		contextUpdateMenu.add(mi);
		mi = new JMenuItem("ID to Clipboard");
		mi.setActionCommand("CLIPID");
		mi.addActionListener(pal);
		contextUpdateMenu.add(mi);
		mi = new JMenuItem("Create new source");
		mi.setActionCommand("NEWSRC");
		mi.addActionListener(pal);
		contextUpdateMenu.add(mi);

		sourceUpdateMenu = new JPopupMenu("Source");
		mi = new JMenuItem("Update properties");
		mi.setActionCommand("UPDATEPRP");
		mi.addActionListener(pal);
		sourceUpdateMenu.add(mi);
		mi = new JMenuItem("ID to Clipboard");
		mi.setActionCommand("CLIPID");
		mi.addActionListener(pal);
		sourceUpdateMenu.add(mi);
		mi = new JMenuItem("Create new property");
		mi.setActionCommand("NEWPRP");
		mi.addActionListener(pal);
		sourceUpdateMenu.add(mi);

		propertyUpdateMenu = new JPopupMenu("Property");
		mi = new JMenuItem("Update");
		mi.setActionCommand("GETPRP");
		mi.addActionListener(pal);
		propertyUpdateMenu.add(mi);
		mi = new JMenuItem("ID to Clipboard");
		mi.setActionCommand("CLIPID");
		mi.addActionListener(pal);
		propertyUpdateMenu.add(mi);
		mi = new JMenuItem("Set property");
		mi.setActionCommand("SETPRP");
		mi.addActionListener(pal);
		propertyUpdateMenu.add(mi);
		mi = new JMenuItem("View data");
		mi.setActionCommand("VIEW");
		mi.addActionListener(pal);
		propertyUpdateMenu.add(mi);
		
		contextTree.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				if(e.getButton()==3) {
					if(selectedNode!=null) {
						if(selectedNode == rootNode) {
							rootUpdateMenu.show(e.getComponent(), e.getX(), e.getY());
						}
						if(selectedNode instanceof ContextNode) {
							contextUpdateMenu.show(e.getComponent(), e.getX(), e.getY());
						}
						if(selectedNode instanceof SourceNode) {
							sourceUpdateMenu.show(e.getComponent(), e.getX(), e.getY());
						}
						if(selectedNode instanceof PropertyNode) {
							propertyUpdateMenu.show(e.getComponent(), e.getX(), e.getY());
						}
					}
				}
			}
		});
		
		ctxScrollPane = new JScrollPane(contextTree);
		add(ctxScrollPane, BorderLayout.CENTER);

		b_Login.setText("Login");
		b_ReLogin.setText("Re-Login");
		b_Logout.setText("Logout");
		b_SubAll.setText("Subscribe All");
		
		interactionPanel.setLayout(new BoxLayout(interactionPanel, BoxLayout.PAGE_AXIS));
		JPanel tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(new JLabel("Server"));
		tmpPanel.add(tf_Server);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(new JLabel("Port"));
		tmpPanel.add(tf_Port);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(new JLabel("Login"));
		tmpPanel.add(tf_Login);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(new JLabel("UserID"));
		tmpPanel.add(tf_ID);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(b_Login);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(b_ReLogin);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(b_Logout);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(b_SubAll);
		interactionPanel.add(tmpPanel);
		tmpPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		tmpPanel.add(cb_autoExpand);
		interactionPanel.add(tmpPanel);
		interactionPanel.add(new Filler(new Dimension(0,0), new Dimension(0, 0), new Dimension(Integer.MAX_VALUE,Integer.MAX_VALUE)));
		
		add(interactionPanel, BorderLayout.EAST);
		
		
		tf_Command.setAction(ba);
		commandPanel.add(tf_Command);
		
		b_Send.setText("Send");
		
		commandPanel.add(b_Send);
		
		JPanel southHolder = new JPanel();
		southHolder.setLayout(new BoxLayout(southHolder, BoxLayout.PAGE_AXIS));
		
		statusLabel = new JLabel("Ready");
		
		JPanel statusHolder = new JPanel();
		statusHolder.setLayout(new FlowLayout(FlowLayout.LEADING));
		
		statusHolder.add(statusLabel);
		statusHolder.setBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED));
		
		southHolder.add(commandPanel);
		southHolder.add(statusHolder);
		
		add(southHolder, BorderLayout.SOUTH);
		
		cmdProc.start();
	}
	
	
	
	private ContextNode ensureContextNode(Integer ctxId, String ctxName) {
		
		ContextNode cn = ctxNodeMap.get(ctxId);
		
		if(cn==null) {
			//String n = ctxName!=null?ctxName:Integer.toString(ctxId);
			cn = new ContextNode(ctxName!=null?new Context(ctxName):null, ctxId);
			ctxNodeMap.put(ctxId, cn);
			contextTreeModel.insertNodeInto(cn, rootNode, rootNode.getChildCount());
			addKnownId(ctxId);
			
			if(cb_autoExpand.isSelected()) {
				contextTree.expandPath(new TreePath(rootNode.getPath()));
			}
		} else {
			if(cn.getContext()==null && ctxName!=null) {
				cn.setContext(new Context(ctxName));
				contextTreeModel.nodeChanged(cn);
			}
		}
		
		return cn;
	}
	
	private void updateContextNodeId(String ctxName, Integer ctxId) {
		Integer virtualId = null;
		ContextNode cn = null;
		
		for(Map.Entry<Integer, ContextNode> meic : ctxNodeMap.entrySet()) {
			cn = meic.getValue();
			if(cn.ctx != null && cn.ctx.getName().equals(ctxName)) {
				if(isVirtualId(meic.getKey())) {
					virtualId = meic.getKey();
					break;
				}
			}
		}
		
		if(virtualId != null) {
			ctxNodeMap.remove(virtualId);
			removeVirtualId(virtualId);
			cn.ctxId = ctxId;
			ctxNodeMap.put(ctxId, cn);
			addKnownId(ctxId);
			contextTreeModel.nodeChanged(cn);
			
			for(int c = 0; c < cn.getChildCount(); c++) {
				SourceNode sn = (SourceNode)cn.getChildAt(c);
				if(sn.sourceName!=null)
					cc.putCommand(Protocol.GETSRCID + " " + ctxId + " " + sn.sourceName);
			}
		}
	}
	
	private SourceNode ensureSourceNode(Integer ctxId, Integer srcId, String srcName) {
		ContextNode cn = ensureContextNode(ctxId, null);
		
		SourceNode sn = srcNodeMap.get(srcId);
		
		if(sn==null) {
			sn = new SourceNode(srcName, srcId);
			srcNodeMap.put(srcId, sn);
			contextTreeModel.insertNodeInto(sn, cn, cn.getChildCount());
			addKnownId(srcId);
			
			if(cb_autoExpand.isSelected()) {
				contextTree.expandPath(new TreePath(cn.getPath()));
			}

		} else {
			if(srcName != null && !srcName.equals(sn.getName())) {
				sn.setName(srcName);
				contextTreeModel.nodeChanged(sn);
			}
		}
		
		return sn;
	}
	
	private void updateSourceNodeId(Integer ctxId, String srcName, Integer srcId) {
		Integer virtualId = null;
		SourceNode sn = null;
		
		for(Map.Entry<Integer, SourceNode> meis : srcNodeMap.entrySet()) {
			sn = meis.getValue();
			if(sn.sourceName != null && sn.sourceName.equals(srcName)) {
				if(isVirtualId(meis.getKey())) {
					virtualId = meis.getKey();
					break;
				}
			}
		}
		
		if(virtualId != null) {
			srcNodeMap.remove(virtualId);
			removeVirtualId(virtualId);
			sn.srcId = srcId;
			srcNodeMap.put(srcId, sn);
			addKnownId(srcId);
			contextTreeModel.nodeChanged(sn);
			
			for(int c=0; c<sn.getChildCount(); c++) {
				PropertyNode pn = (PropertyNode)sn.getChildAt(c);
				if(pn.propertyName != null) {
					cc.putCommand(Protocol.GETPRPID + " " + ctxId + " " + srcId + " " + pn.propertyName);
				}
			}
		}
	}
	
	private PropertyNode ensurePropertyNode(Integer ctxId, Integer srcId, Integer prpId, String prpName) {
		SourceNode sn = ensureSourceNode(ctxId, srcId, null);
		
		PropertyNode pn = prpNodeMap.get(prpId);
		
		if(pn==null) {
			pn = new PropertyNode(prpName, prpId, "", -1, null, false);
			prpNodeMap.put(prpId, pn);
			contextTreeModel.insertNodeInto(pn, sn, sn.getChildCount());
			addKnownId(prpId);
			if(cb_autoExpand.isSelected()) {
				contextTree.expandPath(new TreePath(sn.getPath()));
			}
		} else {
			if(prpName!=null && !prpName.equals(pn.getName())) {
				pn.setName(prpName);
				contextTreeModel.nodeChanged(pn);
			}
		}
		
		return pn;
	}
	
	private void updatePropertyNodeId(Integer ctxId, Integer srcId, String prpName, Integer prpId) {
		Integer virtualId = null;
		PropertyNode pn = null;
		
		for(Map.Entry<Integer, PropertyNode> meip : prpNodeMap.entrySet()) {
			pn = meip.getValue();
			if(pn.propertyName != null && pn.propertyName.equals(prpName)) {
				if(isVirtualId(meip.getKey())) {
					virtualId = meip.getKey();
					break;
				}
			}
		}
		
		if(virtualId != null) {
			prpNodeMap.remove(virtualId);
			removeVirtualId(virtualId);
			pn.prpId = prpId;
			prpNodeMap.put(prpId, pn);
			addKnownId(prpId);
			contextTreeModel.nodeChanged(pn);
		}
	}
	
	private Integer findContextIdByName(String contextName) {
		for(Map.Entry<Integer, ContextNode> meic : ctxNodeMap.entrySet()) {
			ContextNode cn = meic.getValue();
			if(cn.ctx != null && cn.ctx.getName().equals(contextName))
				return meic.getKey();
		}
		
		return null;
	}
	
	private PropertyNode getPropertyNodeForContext(ContextMessage cm) {
		if(cm.getType() == ContextMessage.Type.ShortContext || cm.isShortFormat()) {
			try {
				Integer prpId = Integer.parseInt(cm.getCE().getPropertyIdentifier());
				
				return prpNodeMap.get(prpId);
				
			} catch(NumberFormatException nfe) {
				return null;
			}
		}
		
		if(cm.getType() != ContextMessage.Type.Context)
			return null;

		ContextElement ce = cm.getCE();
		
		if(ce==null)
			return null;
		
		String info = cm.getContextInformation();
		Integer ctxId = -1;
		
		if(info != null) {
			ctxId = Util.parseIntOr(info, -1);
		}
		
		if(ctxId == -1) {
			ctxId = findContextIdByName(cm.getContextName());
		}
		
		if(ctxId == null)
			return null;
		
		ContextNode cn = ctxNodeMap.get(ctxId);
		
		if(cn==null) {
			ctxId = createVirtualId();
			cn = ensureContextNode(ctxId, cm.getContextName());
		}
		
		SourceNode sn = null;
		for(int c = 0; c < cn.getChildCount(); c++) {
			sn = (SourceNode)cn.getChildAt(c);
			
			if(sn.sourceName!=null && sn.sourceName.equals(ce.getSourceIdentifier())) {
				break;
			}
			
			sn = null;
		}
	
		
		if(sn == null) {
			sn = ensureSourceNode(ctxId, createVirtualId(), ce.getSourceIdentifier());
		}
		
		PropertyNode pn = null;
		
		for(int c=0; c<sn.getChildCount(); c++) {
			pn = (PropertyNode)sn.getChildAt(c);
			
			if(pn.propertyName != null && pn.propertyName.equals(ce.getPropertyIdentifier())) {
				break;
			}
			
			pn = null;
		}
		
		if(pn == null) {
			pn = ensurePropertyNode(ctxId, sn.srcId, createVirtualId(), ce.getPropertyIdentifier());
		}
		
		return pn;
	}
	
	
	
	private class CommandProcessor extends Thread implements ContextClientListener {
		private Queue<String> commandQueue = new LinkedList<String>();
		private Queue<String> replyQueue = new LinkedList<String>();
		private Queue<String> contextQueue = new LinkedList<String>();
		private boolean stopProcessingCommands = false;
		
		public void run() {
			while(!stopProcessingCommands) {
				synchronized(commandQueue) {
					while(!commandQueue.isEmpty()) {
						String command = commandQueue.poll();
						String msg = replyQueue.poll();
						
						if(command == null)
							continue;
						
						//System.out.println("Command: " + ((command.length() > 300) ? command.substring(0, 300) + "[...]" : command));
						//System.out.println("UIMessage: " + ((msg.length() > 300) ? msg.substring(0, 300) + "[...]" : msg));
						
						if(Util.startsWithIgnoreCase(command, Protocol.LISTCTX)) {
							Map<Integer, String> ctxMap = Protocol.parseCTXList(msg);
							System.out.println("Parsing context list...");
							if(ctxMap!=null) {
								System.out.println(ctxMap);

								for(Iterator<Map.Entry<Integer, String>> ei = ctxMap.entrySet().iterator(); ei.hasNext(); ) {
									Map.Entry<Integer, String> e = ei.next();
									ensureContextNode(e.getKey(), e.getValue());
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.LISTSRC)) {
							System.out.println("Parsing source list...");
							Map<Integer, Map<Integer, String>> srcMap = Protocol.parseSRCList(msg);
							if(srcMap!=null) {
								System.out.println(srcMap);
								for(Map.Entry<Integer, Map<Integer, String>> cme : srcMap.entrySet()) {
									for(Map.Entry<Integer, String> sme : cme.getValue().entrySet()) {
										ensureSourceNode(cme.getKey(), sme.getKey(), sme.getValue());
									}
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.LISTPRP)) {
							System.out.println("Parsing property list...");
							Map<Integer, Map<Integer, Map<Integer, String>>> prpMap = Protocol.parsePRPList(msg);
							if(prpMap!=null) {
								System.out.println(prpMap);
								for(Map.Entry<Integer, Map<Integer, Map<Integer, String>>> cme : prpMap.entrySet()) {
									for(Map.Entry<Integer, Map<Integer, String>> sme : cme.getValue().entrySet()) {
										for(Map.Entry<Integer, String> pme : sme.getValue().entrySet()) {
											ensurePropertyNode(cme.getKey(), sme.getKey(), pme.getKey(), pme.getValue());
										}
									}
								}
							}
						}
						
						String [] getcmd = Util.splitWS(command);
						
						if(Util.startsWithIgnoreCase(command, Protocol.GETPRP)) {
							if(getcmd.length>1) {
								int prpId = Util.parseIntOr(getcmd[1], -1);
								
								if(prpId>=0) {
									PropertyNode pn = prpNodeMap.get(prpId);
									if(pn!=null) {
										// source- and property names are not needed here
										ContextElement ce = Protocol.parseProperty("", "", msg);
										if(ce!=null) {
											pn.update(ce.getValue(), ce.getTimestamp(), ce.getTypeTags(), ce.isPersistent());
											contextTreeModel.nodeChanged(pn);
										}
									}
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.GETCTXID)) {
							if(getcmd.length>1) {
								String ctxName = getcmd[1];
								Integer ctxId = Util.parseIntReply(msg);
								if(ctxId!=null) {
									updateContextNodeId(ctxName, ctxId);
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.GETSRCID)) {
							if(getcmd.length>2) {
								Integer ctxId = Util.parseIntOr(getcmd[1], -1);
								String srcName = getcmd[2];
								Integer srcId = Util.parseIntReply(msg);
								if(ctxId != -1 && srcId!=null) {
									updateSourceNodeId(ctxId, srcName, srcId);
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.GETPRPID)) {
							if(getcmd.length>3) {
								Integer ctxId = Util.parseIntOr(getcmd[1], -1);
								Integer srcId = Util.parseIntOr(getcmd[2], -1);
								String prpName = getcmd[3];
								Integer prpId = Util.parseIntReply(msg);
								if(ctxId != -1 && srcId!=-1 && prpId != null) {
									updatePropertyNodeId(ctxId, srcId, prpName, prpId);
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.CREATECTX)) {
							if(getcmd.length>1) {
								Integer ctxId = Util.parseIntReply(msg);
								if(ctxId!=null) {
									ensureContextNode(ctxId, Util.urldecode(getcmd[1]));
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.CREATESRC)) {
							if(getcmd.length>2) {
								Integer ctxId = Util.parseIntOr(getcmd[1], -1);
								Integer srcId = Util.parseIntReply(msg);
								if(ctxId!=-1 && srcId!=null) {
									ensureSourceNode(ctxId, srcId, Util.urldecode(getcmd[2]));
								}
							}
						}
						if(Util.startsWithIgnoreCase(command, Protocol.CREATEPRP)) {
							if(getcmd.length>3) {
								Integer ctxId = Util.parseIntOr(getcmd[1], -1);
								Integer srcId = Util.parseIntOr(getcmd[2], -1);
								Integer prpId = Util.parseIntReply(msg);
								if(ctxId!=-1 && srcId!=-1 && prpId != null) {
									ensurePropertyNode(ctxId, srcId, prpId, Util.urldecode(getcmd[3]));
								}
							}
						}
						// update node by server after setting
						if(Util.startsWithIgnoreCase(command, Protocol.SETPRP)) {
							if(getcmd.length>1) {
								Integer prpId = Util.parseIntOr(getcmd[1], -1);
								if(prpId!=-1) {
									cc.putCommand(Protocol.GETPRP + " " + prpId);
								}
							}
						}
					}
				}
				synchronized(contextQueue) {
					while(!contextQueue.isEmpty()) {
						String contextMessage = contextQueue.remove();
						if(contextMessage == null)
							continue;
						
						ContextMessage cm = ContextMessage.fromString(contextMessage);
						
						if(cm!=null)
						{
							// TODO do real parsing...
							switch(cm.getType())
							{
							case SourceAdded:
								System.out.println("New source: " + cm.getContextName() + " -> " + cm.getSourceName() + " -> " + cm.getPropertyName());
								break;
							case SourceRemoved:
								System.out.println("Removed source: " + cm.getContextName() + " -> " + cm.getSourceName());
								break;
							case PropertyAdded:
								System.out.println("New property: " + cm.getContextName() + " -> " + cm.getSourceName() + " -> " + cm.getPropertyName());
								break;
							case PropertyRemoved:
								System.out.println("Removed property: " + cm.getContextName() + " -> " + cm.getSourceName() + " -> " + cm.getPropertyName());
								break;
							case Context:
							case ShortContext:
								//System.out.println("Context-Information: " + contextMessage);

								ContextElement ce = cm.getCE();
								
								PropertyNode pn = getPropertyNodeForContext(cm);
								
								if(pn!=null)
								{
									pn.update(ce.getValue(), ce.getTimestamp(), ce.getTypeTags(), ce.isPersistent());
									contextTreeModel.nodeChanged(pn);
									
									if(isVirtualId(pn.prpId)) {
										SourceNode sn = (SourceNode)pn.getParent();
										if(sn!=null) {
											ContextNode cn = (ContextNode)sn.getParent();
											
											if(cn!=null && cn.ctx != null) {
												cc.putCommand(Protocol.GETCTXID + " " + cn.ctx.getName());
											}
										}
									}
								}
								else
								{
									System.err.println("No Matching Entry in tree... " + contextMessage);
								}
								
								if(ce.getTypeTags().contains("Picture")) {
									System.out.println("Maybe got picture...");
									
									String fullName = cm.getContextName() + "." + ce.getSourceIdentifier() + "." + ce.getPropertyIdentifier();
									
									PictureFrame pf = pictureMap.get(fullName);
									
									byte [] encoded = ce.getValue().getBytes();
									ByteArrayOutputStream baos = new ByteArrayOutputStream();
									try {
										Util.decodeBase64(new ByteArrayInputStream(encoded), baos);
										
										byte [] imagedata = baos.toByteArray();
										
										BufferedImage bi = ImageIO.read(new ByteArrayInputStream(imagedata));
										
										if(bi!=null) {
											
											if(pf==null) {
												pf = new PictureFrame(fullName, bi);
												pictureMap.put(fullName, pf);
											} else {
												pf.setImage(bi);
											}
											pf.setVisible(true);
											
										} else {
											System.err.println("Unable to decode image from data...");
											if(pf!=null) {
												pf.setImage(null);
											}
										}
										
									} catch (IOException e) {
										System.err.println("Error decoding Base64 stream: " + e.getMessage());
										if(pf!=null) {
											pf.setImage(null);
										}
									}
									
									
								}
								
								break;
							}
						} else {
							System.err.println("Received invalid context message: " + contextMessage);
						}
					}
				}
				try {
					sleep(50);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		
		public void terminate() {
			stopProcessingCommands = true;
		}
		
		public void processCommandResult(String command, String message) {
			synchronized(commandQueue) {
				commandQueue.offer(command);
				replyQueue.offer(message);
			}
		}
		
		public void processContextInformation(String message) {
			System.out.println("CTX-Message: " + message);
			synchronized(contextQueue) {
				contextQueue.offer(message);
			}
		}

		public void processCommunicationState(CommunicationState state) {
			System.out.println("CommState: " + state);
			tf_ID.setText(""+cc.getID());
		}
	}
	
	private CommandProcessor cmdProc = new CommandProcessor(); 
	
	private class ButtonAction extends AbstractAction {
		private static final long serialVersionUID = 1L;

		public void actionPerformed(ActionEvent e) {
			if(e.getSource()==b_Login) {
				if(cc!=null) {
					cc.terminate();
				}
				cc = new ContextClient();
				cc.addContextClientListener(cmdProc);
				cc.init(tf_Server.getText(), Integer.parseInt(tf_Port.getText()));
				cc.login(tf_Login.getText());
			}
			if(e.getSource()==b_ReLogin) {
				if(cc!=null) {
					cc.terminate();
				}
				cc = new ContextClient();
				cc.addContextClientListener(cmdProc);
				cc.init(tf_Server.getText(), Integer.parseInt(tf_Port.getText()));
				int oldid = Util.parseIntOr(tf_ID.getText(), -1);
				if(oldid!=-1) {
					cc.relogin(tf_Login.getText(), oldid);
				}
			}
			if(e.getSource()==b_Logout) {
				if(cc!=null) {
					if(cc.getCommunicationState()==CommunicationState.Connected)
						cc.logout();
				}
			}
			if(e.getSource()==b_Send || e.getSource()==tf_Command) {
				if(cc!=null) {
					cc.putCommand(tf_Command.getText());
				}
			}
			if(e.getSource()==b_SubAll) {
				if(cc!=null) {
					cc.putCommand(Protocol.SUBSCRIBE + " 1 <AllContexts> 1 <AllSources> 1 <AllProperties> 0");
				}
			}
		}
		
	}

	private class ContextNode extends DefaultMutableTreeNode {
		private static final long serialVersionUID = 1L;

		private Context ctx;
		private int ctxId;
		
		public ContextNode(Context ctx, int ctxId) {
			super();
			this.ctx = ctx;
			this.ctxId = ctxId;
		}

		@Override
		public Object getUserObject() {
			return ctx;
		}
		
		public void setContext(Context ctx) {
			this.ctx = ctx;
		}
		
		public Context getContext() {
			return ctx;
		}
		
		public int getContextId() {
			return ctxId;
		}
		
		public String toString() {
			return ((ctx!=null)?ctx.getName():"<null>") + " (" +ctxId + ")";
		}
	}
	
	private class SourceNode extends DefaultMutableTreeNode {
		private static final long serialVersionUID = 1L;

		private String sourceName;
		private boolean isMerged = true;
		private int srcId;
		
		public SourceNode(String sourceName, int srcId) {
			super();
			if(sourceName != null && sourceName.endsWith("*")) {
				isMerged = false;
				sourceName = sourceName.substring(0, sourceName.length()-1);
			}
			this.sourceName = sourceName;
			this.srcId = srcId;
		}
		
		public String getName() {
			return sourceName;
		}
		
		@SuppressWarnings("unused")
		public boolean isMerged() {
			return isMerged;
		}
		
		public void setName(String srcName) {
			this.sourceName = srcName;
		}
		
		public String toString() {
			return sourceName + ( isMerged?"":"*" ) + " (" + srcId + ")";
		}
	}
	
	/**
	 * Represents a ContextElement as a TreeNode
	 * @author hendrik
	 *
	 */
	private class PropertyNode extends DefaultMutableTreeNode {
		private static final long serialVersionUID = 1L;

		private String propertyName;
		private boolean isMerged = true;
		private int prpId;
		
		private String value;
		private Set<String> tags = new HashSet<String>();
		
		private long timeStamp;
		private boolean isPersistent;
		
		public PropertyNode(String propertyName, int prpId, String value, long timestamp, Set<String> tags, boolean isPersistent) {
			super();
			if(propertyName.endsWith("*")) {
				isMerged = false;
				propertyName = propertyName.substring(0, propertyName.length()-1);
			}
			this.propertyName = propertyName;
			this.prpId = prpId;
			this.value = value;
			this.timeStamp = timestamp;
			if(tags!=null)
				this.tags.addAll(tags);
			this.isPersistent = isPersistent;
		}
		
		public void update(String value, Long timestamp, Set<String> tags, Boolean isPersistent) {
			if(value!=null)
				this.value = value;
			if(timestamp!=null)
				this.timeStamp = timestamp;
			if(tags!=null) {
				this.tags.clear();
				this.tags.addAll(tags);
			}
			if(isPersistent!=null) {
				this.isPersistent = isPersistent;
			}
		}
		
		public String getName() {
			return propertyName;
		}
		
		@SuppressWarnings("unused")
		public boolean isMerged() {
			return isMerged;
		}
		
		public void setName(String prpName) {
			this.propertyName = prpName;
		}
		
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			sb.append(propertyName);
			if(!isMerged)
				sb.append("*");
			sb.append("=");
			sb.append(value);
			sb.append(" ");
			sb.append("(");
			sb.append(Integer.toString(prpId));
			sb.append(")");
			sb.append(" @");
			sb.append(Long.toString(timeStamp));
			sb.append(" <");
			for(Iterator<String> tagi = tags.iterator(); tagi.hasNext(); ) {
				sb.append(tagi.next());
				if(tagi.hasNext())
					sb.append(", ");
			}
			sb.append(">");
			if(isPersistent)
				sb.append(" Persistent");
			
			return sb.toString();
		}
	}
	
	private class PictureFrame extends JFrame {
		private static final long serialVersionUID = 1L;
		private JLabel pictureLabel;
		
		public void setImage(BufferedImage image) {
			if(image==null)
				pictureLabel.setText("No-Image!");
			else
				pictureLabel.setIcon(new ImageIcon(image));
			pack();
		}
		
		private AbstractAction escapeAction = new AbstractAction("Escape") {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				setVisible(false);
			}
		};
		
		public PictureFrame(String title, BufferedImage image) {
			super(title);
			setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
			getRootPane().getActionMap().put(escapeAction.getValue(Action.NAME), escapeAction);
			getRootPane().getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), escapeAction.getValue(Action.NAME));
			
			add(pictureLabel = new JLabel());
			setImage(image);
		}
		
	}
	
	public void valueChanged(TreeSelectionEvent e) {
		selectedNode = (DefaultMutableTreeNode)e.getPath().getLastPathComponent();
	}
	
	public static void main(String...args) {
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				JFrame frame = new JFrame("ContextClient");
				final ClientUI cui = new ClientUI(frame);
				frame.add(cui);
				frame.pack();
				frame.setVisible(true);
				frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
				frame.addWindowListener(new WindowAdapter() {
					@Override
					public void windowClosed(WindowEvent e) {
						if(cui.cc!=null) {
							if(cui.cc.getCommunicationState()==ContextClient.CommunicationState.Connected)
								cui.cc.logout();
							cui.cc.terminate();
						}
						cui.cmdProc.terminate();
						for(PictureFrame pf : cui.pictureMap.values()) {
							pf.setVisible(false);
							pf.dispose();
						}
					}

					@Override
					public void windowOpened(WindowEvent e) {
						super.windowOpened(e);
						cui.statusUpdater.start();
					}
					
				});
			}
		});
	}
}

package edu.cmu.neuron2;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.mina.common.IoHandlerAdapter;
import org.apache.mina.common.IoServiceConfig;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.LoggingFilter;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.serialization.ObjectSerializationCodecFactory;
import org.apache.mina.transport.socket.nio.DatagramAcceptor;
import org.apache.mina.transport.socket.nio.DatagramAcceptorConfig;
import org.apache.mina.transport.socket.nio.DatagramConnector;

public class NeuRonNode extends Thread {
	private final ExecutorService executor;
	private final ScheduledExecutorService scheduler;
	private int iNodeId; // TODO non final
	private final boolean bCoordinator;
	private final String sCoordinatorIp;
	private final int basePort;
	private boolean doQuit;

	private final Hashtable<Integer, NodeInfo> nodes = new Hashtable<Integer, NodeInfo>();

	int[][] probeTable; // probeTable[i] = node members[i]'s probe table. value
	// at index j in row i is the link latency between nodes
	// members[i]->members[j].
	// ArrayList<Integer> bestHopTable; // value at index i, is the best hop
	// value for node at index i in members
	// TODO non final
	private int[][] grid;
	private int numCols, numRows;
	private final HashSet<Integer> neighbors = new HashSet<Integer>();
	private final IoServiceConfig cfg = new DatagramAcceptorConfig();

	private Random generator;

	public NeuRonNode(int id, String cName, int cPort,
			ExecutorService executor, ScheduledExecutorService scheduler) {
		iNodeId = id;
		sCoordinatorIp = cName;
		basePort = cPort;
		this.executor = executor;
		this.scheduler = scheduler;
		generator = new Random(iNodeId);
		probeTable = null;
		// bestHopTable = new ArrayList<Integer>();
		grid = null;
		numCols = numRows = 0;
		bCoordinator = iNodeId == 0;
		cfg.getFilterChain().addLast("logger", new LoggingFilter());
		cfg.getFilterChain().addLast(
				"codec",
				new ProtocolCodecFilter(
						new ObjectSerializationCodecFactory()));

	}

	private void log(String msg) {
		System.out.println("node " + iNodeId + ":\n  " + msg);
	}

	private void err(String msg) {
		System.out.println("node " + iNodeId + ":\n  " + msg);
	}

	public void run() {
		if (bCoordinator) {
			try {
				int nextNodeId = 0;
				Thread.sleep(2000);
				ServerSocket ss = new ServerSocket(basePort);
				try {
					ss.setReuseAddress(true);
					ss.setSoTimeout(1000);
					log("Beep!");
					while (!doQuit) {
						final Socket incoming;
						try {
							incoming = ss.accept();
						} catch (SocketTimeoutException ex) {
							continue;
						}
						final int nodeId = nextNodeId++;
						executor.submit(new Runnable() {
							public void run() {
								try {
									Msg.Join msg = (Msg.Join) new ObjectInputStream(
											incoming.getInputStream())
											.readObject();
									try {
										Msg.Init im = new Msg.Init();
										im.id = nodeId;
										synchronized (NeuRonNode.this) {
											addMemberNode(nodeId, msg.addr,
													msg.port);
											im.members = new ArrayList<NodeInfo>(
													nodes.values());
										}
										new ObjectOutputStream(incoming
												.getOutputStream())
												.writeObject(im);
									} finally {
										incoming.close();
									}
								} catch (Exception ex) {
									throw new RuntimeException(ex);
								}
							}
						});
					}
				} finally {
					ss.close();
					log("coord done");
				}
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		} else {
			try {
				Socket s;
				while (true) {
					// Connect to the co-ordinator
					try {
						s = new Socket(sCoordinatorIp, basePort);
						break;
					} catch (Exception ex) {
						log("couldn't connect to coord, retrying in 1 sec");
						Thread.sleep(1000);
					}
				}

				try {
					// talk to coordinator
					log("sending join");
					Msg.Join msg = new Msg.Join();
					msg.addr = InetAddress.getLocalHost();
					new ObjectOutputStream(s.getOutputStream())
							.writeObject(msg);

					Msg.Init im = (Msg.Init) new ObjectInputStream(s
							.getInputStream()).readObject();
					log("got from coord: " + im);
					iNodeId = im.id;
					handleMembershipChange(im.members);
				} finally {
					try {
						s.close();
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}

			try {
				new DatagramAcceptor().bind(new InetSocketAddress(basePort
						+ iNodeId), new Receiver(), cfg);
				log("server started");

				scheduler.scheduleAtFixedRate(new Runnable() {
					public void run() {
						synchronized (NeuRonNode.this) {
							pingAll();
						}
					}
				}, 1, 10, TimeUnit.SECONDS);
				scheduler.scheduleAtFixedRate(new Runnable() {
					public void run() {
						synchronized (NeuRonNode.this) {
							sendAllNeighborsAdjacencyTable();
							sendAllNeighborsRoutingRecommendations();
						}
					}
				}, 1, 10, TimeUnit.SECONDS);
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}
	}
	
	private void pingAll() {
		Msg.Ping ping = new Msg.Ping();
		ping.time = System.currentTimeMillis();
		ping.info = nodes.get(iNodeId);
		for (int nid : nodes.keySet()) {
			sendObject(ping, nid);
		}
	}

	public final class Receiver extends IoHandlerAdapter {
		@Override
		public void messageReceived(IoSession session, Object msg)
				throws Exception {
			synchronized (NeuRonNode.this) {
				if (msg instanceof Msg.Membership) {
					log("got membership msg " + msg);
					handleMembershipChange(((Msg.Membership) msg).members);
				} else if (msg instanceof Msg.Routing) {
					log("got routing msg " + msg);
					updateNetworkState((Msg.Routing) msg);
				} else if (msg instanceof Msg.RoutingRecs) {
					log("got routing recs msg " + msg);
					// TODO something
				} else if (msg instanceof Msg.Ping) { 
					log("got ping msg " + msg);
					Msg.Ping ping = ((Msg.Ping) msg);
					Msg.Pong pong = new Msg.Pong();
					pong.time = ping.time;
					handleMembershipChange(ping.info);
					sendObject(pong, ping.info.id);
				} else if (msg instanceof Msg.Pong) {
					Msg.Pong pong = (Msg.Pong) msg;
					long latency = System.currentTimeMillis() - pong.time;
					log("got pong msg with latency " + latency);
					// TODO do something with the latency
				} else {
					throw new Exception("received unknown message type");
				}
			}
		}
	}

	/**
	 * a coordinator-only method
	 */
	private void addMemberNode(int newNid, InetAddress addr, int port) {
		if (!nodes.contains(newNid)) {
			NodeInfo info = new NodeInfo();
			info.id = newNid;
			info.addr = addr;
			info.port = port;
			nodes.put(newNid, info);
		}
	}

	/**
	 * garbage collect node_id because we think he's dead
	 * 
	 * @param node_id
	 */
	private void removeMemberNode(int node_id) {
		log(node_id + " is dead to me");
		nodes.remove(node_id);
	}

	private ArrayList<Integer> memberNids() {
		ArrayList<Integer> nids = new ArrayList<Integer>(nodes.keySet());
		Collections.sort(nids);
		return nids;
	}

	private void broadcastMembershipChange(int exceptNid) {
		for (int nid : nodes.keySet()) {
			if (nid != exceptNid) {
				Msg.Membership msg = new Msg.Membership();
				msg.id = iNodeId;
				msg.members = new ArrayList<NodeInfo>(nodes.values());
				sendObject(msg, nid);
			}
		}
	}
	
	private void handleMembershipChange(NodeInfo newNode) {
		List<NodeInfo> infos = new ArrayList<NodeInfo>(nodes.values());
		infos.add(newNode);
		handleMembershipChange(infos);
	}

	private void handleMembershipChange(List<NodeInfo> newNodes) {
		nodes.clear();
		for (NodeInfo node : newNodes)
			nodes.put(node.id, node);
		probeTable = new int[nodes.size()][nodes.size()];
		repopulateGrid();
		printGrid();
		printNeighborList();
	}

	/**
	 * TODO XXX OPEN QUESTION HOW TO HANDLE NODE WORLD VIEW INCONSISTENCIES????
	 */
	private void repopulateGrid() {
		numCols = (int) Math.ceil(Math.sqrt(nodes.size()));
		numRows = (int) Math.ceil((double) nodes.size() / (double) numCols);
		grid = new int[numRows][numCols];
		List<Integer> nids = memberNids();
		int m = 0;
		for (int i = 0; i < numRows; i++) {
			for (int j = 0; j < numCols; j++) {
				if (m >= nids.size()) {
					m = 0;
				}
				grid[i][j] = nids.get(m);
				m++;
			}
		}
		repopulateNeighborList();
		repopulateProbeTable();
	}

	private void repopulateNeighborList() {
		neighbors.clear();
		for (int i = 0; i < numRows; i++) {
			for (int j = 0; j < numCols; j++) {
				if (grid[i][j] == iNodeId) {
					// all the nodes in row i, and all the nodes in column j are
					// belong to us :)
					for (int x = 0; x < numCols; x++) {
						if (grid[i][x] != iNodeId) {
							neighbors.add(grid[i][x]);
						}
					}
					for (int x = 0; x < numRows; x++) {
						if (grid[x][j] != iNodeId) {
							neighbors.add(grid[x][j]);
						}
					}
				}
			}
		}
	}

	private void repopulateProbeTable() {
		int nodeIndex = memberNids().indexOf(iNodeId);
		for (int i = 0; i < memberNids().size(); i++) {
			if (i == nodeIndex) {
				probeTable[i][i] = 0;
			} else {
				probeTable[nodeIndex][i] = generator.nextInt();
			}
		}

		// for testing
		if (nodeIndex == 0) {
			for (int i = 1; i < memberNids().size(); i++) {
				probeTable[nodeIndex][i] = 1;
			}
		} else {
			probeTable[nodeIndex][0] = 1;
		}
	}

	private void printMembership() {
		String s = new String("Membership for Node " + iNodeId
				+ ". Membership = [");
		for (Integer memberId : memberNids()) {
			s += memberId + ", ";
		}
		s += "]";
		log(s);
	}

	private void printNeighborList() {
		String s = new String("Neighbors for Node " + iNodeId
				+ ". Neighbors = [");
		synchronized (neighbors) {
			for (Integer neighborId : neighbors) {
				s += neighborId + ", ";
			}
			s += "]";
		}
		log(s);
	}

	private void printGrid() {
		String s = new String("Grid for Node " + iNodeId + ".\n");
		if (grid != null) {
			for (int i = 0; i < numRows; i++) {
				for (int j = 0; j < numCols; j++) {
					s += "\t " + grid[i][j];
				}
				s += "\n";
			}
		}
		log(s);
	}

	/**
	 * for each neighbor, find for him the min-cost hops to all other neighbors,
	 * and send this info to him (the intermediate node may be one of the
	 * endpoints, meaning a direct route is cheapest)
	 */
	private synchronized void sendAllNeighborsRoutingRecommendations() {
		for (int src : neighbors) {
			ArrayList<Msg.RoutingRecs.Rec> recs = new ArrayList<Msg.RoutingRecs.Rec>();
			int min = Integer.MAX_VALUE, mini = -1;
			for (int dst : neighbors) {
				for (int i = 0; i < probeTable[src].length; i++) {
					int cur = probeTable[src][i] + probeTable[dst][i];
					if (cur < min) {
						min = cur;
						mini = i;
					}
				}
				recs.add(new Msg.RoutingRecs.Rec(dst, mini));
			}
			Msg.RoutingRecs msg = new Msg.RoutingRecs();
			msg.recs = recs;
			sendObject(msg, src);
		}
	}

	private void sendObject(final Serializable o, int nid) {
		log("sending msg to " + nid + " living at " + (basePort + nid));
		new DatagramConnector().connect(
				new InetSocketAddress(nodes.get(nid).addr, basePort + nid),
				new IoHandlerAdapter() {
					@Override
					public void sessionCreated(IoSession session) {
						session.write(o);
					}
				}, cfg);
	}

	private synchronized void sendAllNeighborsAdjacencyTable() {
		Msg.Routing rm = new Msg.Routing();
		rm.id = iNodeId;
		rm.membershipList = memberNids();
		rm.probeTable = probeTable[rm.membershipList.indexOf(iNodeId)].clone();
		for (int nid : neighbors) {
			sendObject(rm, nid);
		}
	}

	private synchronized void updateNetworkState(Msg.Routing rm) {
		// TODO make this more robust
		int offset = memberNids().indexOf(rm.id);
		if (offset != -1) {
			for (int i = 0; i < rm.probeTable.length; i++) {
				probeTable[offset][i] = rm.probeTable[i];
			}
		}
	}

	public void quit() {
		this.doQuit = true;
	}
}

package edu.cmu.neuron2;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import edu.cmu.neuron2.msg.InitMsg;
import edu.cmu.neuron2.msg.MembershipMsg;
import edu.cmu.neuron2.msg.RoutingMsg;

public class NeuRonNode extends Thread {
	private final ExecutorService executor;
	private final Network network;
	private int iNodeId; // TODO non final
	private final boolean bCoordinator;
	private final String sCoordinatorIp;
	private final int iCoordinatorPort;
	private boolean doQuit = false;

	private MembershipUpdateServerThread must;
	private RoutingUpdateServerThread rust;
	private RoutingUpdateThread rut;

	Semaphore semStateLock;

	int nodeIndex;
	ArrayList<Integer> members;
	int[][] probeTable; // probeTable[i] = node members[i]'s probe table. value
	// at index j in row i is the link latency between nodes
	// members[i]->members[j].
	// ArrayList<Integer> bestHopTable; // value at index i, is the best hop
	// value for node at index i in members
	// TODO non final
	private int[][] grid;
	private int numCols, numRows;
	private final ArrayList<Integer> neighbors;

	Random generator;

	public NeuRonNode(int id, String cName, int cPort, Network network,
			ExecutorService executor) {
		iNodeId = id;
		nodeIndex = 0;
		sCoordinatorIp = cName;
		iCoordinatorPort = cPort;
		this.network = network;
		this.executor = executor;

		must = null;
		rust = null;
		rut = null;

		generator = new Random(iNodeId);

		semStateLock = new Semaphore(1);

		members = new ArrayList<Integer>();
		probeTable = null;
		// bestHopTable = new ArrayList<Integer>();
		grid = null;
		numCols = numRows = 0;
		neighbors = new ArrayList<Integer>();

		if (iNodeId == 0) {
			bCoordinator = true;
		} else {
			bCoordinator = false;
		}
	}

	public void log(String msg) {
		System.out.println("node " + iNodeId + ":\n" + msg);
	}

	public void err(String msg) {
		System.out.println("node " + iNodeId + ":\n" + msg);
	}

	public void run() {
		if (bCoordinator) {
			try {
				int nextNodeId = 0;
				Thread.sleep(2000);
				ServerSocket ss = new ServerSocket(iCoordinatorPort);
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
									BufferedReader reader = new BufferedReader(
											new InputStreamReader(incoming
													.getInputStream()));
									try {
										// TODO trim?
										StringBuffer buf = new StringBuffer();
										while (true) {
											String line = reader.readLine();
											if (line != null) {
												buf.append(line);
												break;
											}
										}

										InitMsg im = new InitMsg(nodeId);
										synchronized (NeuRonNode.this) {
											addMemberNode(nodeId);
											populateMemberList(im);
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
		}

		// if you are not a co-ordinator
		// a. join the overlay by contacting the co-ordinator
		// b. start a server thread to listen to routing updates
		// c. send routing updates to everyone else.
		else {
			try {
				Socket s;
				while (true) {
					// Connect to the co-ordinator
					try {
						s = new Socket(sCoordinatorIp, iCoordinatorPort);
						break;
					} catch (Exception ex) {
						log("couldn't connect to coord, retrying in 1 sec");
						Thread.sleep(1000);
					}
				}

				try {
					// talk to coordinator
					log("sending join");
					new DataOutputStream(s.getOutputStream())
							.writeBytes("join "
									+ InetAddress.getLocalHost()
											.getHostAddress() + " " + iNodeId
									+ "\n");
					InitMsg im = (InitMsg) new ObjectInputStream(s
							.getInputStream()).readObject();
					log("got from coord: " + im.toString());
					iNodeId = im.getId();
					handleMembershipChange(im);
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

			// start a thread, that listens on port (iCoordinatorPort +
			// iNodeId), to look-out for routing updates
			rust = new RoutingUpdateServerThread(iCoordinatorPort + iNodeId,
					iNodeId, this);
			rust.start();

			// start a thread, that listens on port ((iCoordinatorPort +
			// 1000) + iNodeId), to look-out for routing updates
			must = new MembershipUpdateServerThread((iCoordinatorPort + 1000)
					+ iNodeId, iNodeId, this);
			must.start();

			// start a thread, that loops and send routing updates
			// periodically
			rut = new RoutingUpdateThread(iNodeId, this);
			rut.start();
		}
	}

	public void addMemberNode(int node_id) {
		if (members != null) {
			synchronized (members) {
				boolean bFlag = false;
				for (Integer nid : members) {
					if (nid == node_id) {
						bFlag = true;
						break;
					}
				}
				if (bFlag == false) {
					members.add(new Integer(node_id));
					broadcastMembershipChange(node_id);
				}
			}
		}
	}

	public void removeMemberNode(int node_id) {
		if (members != null) {
			log(node_id + " is dead to me");
			synchronized (members) {
				members.remove(new Integer(node_id));
			}
		}
	}

	public void broadcastMembershipChange() {
		if (members != null) {
			synchronized (members) {

				for (Integer memberId : members) {
					// XXX: we are using the co-ords ip (but that's ok, because
					// this is run on a single machine)
					try {
						Socket s = new Socket(sCoordinatorIp,
								(iCoordinatorPort + 1000) + memberId);
						BufferedReader reader = new BufferedReader(
								new InputStreamReader(s.getInputStream()));
						ObjectOutputStream writer = new ObjectOutputStream(s
								.getOutputStream());
						MembershipMsg mm = new MembershipMsg(iNodeId, members);
						writer.writeObject(mm);
						reader.close();
						writer.close();
						s.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	public void broadcastMembershipChange(int exceptionNodeId) {
		if (members != null) {
			synchronized (members) {

				for (Integer memberId : members) {
					if (memberId != exceptionNodeId) {
						// XXX: we are using the co-ords ip (but that's ok,
						// because this is run on a single machine)
						try {
							log("sending to port: "
									+ ((iCoordinatorPort + 1000) + memberId));

							MembershipMsg mm = new MembershipMsg(iNodeId,
									members);
							byte[] buf = MembershipMsg.getBytes(mm);
							InetAddress sAddr = InetAddress
									.getByName(sCoordinatorIp);
							DatagramPacket packet = new DatagramPacket(buf,
									buf.length, sAddr,
									((iCoordinatorPort + 1000) + memberId));
							network.send(packet);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			}
		}
	}

	public void populateMemberList(InitMsg im) {
		if (im != null) {
			synchronized (members) {
				im.initMemberList(members);
			}
		}
	}

	public void readInMemberList(InitMsg im) {
		if (im != null) {
			synchronized (members) {
				im.getMemberList(members);
			}
		}
	}

	public synchronized void handleMembershipChange(MembershipMsg mm) {
		/*
		 * for (Iterator it = members.iterator (); it.hasNext (); ) {
		 * it.next(); it.remove(); // NOTE :: this is it.remove and not
		 * members.remove (which would result in a
		 * ConcurrentModificationException!) }
		 */
		members.clear();
		mm.getMemberList(members);
		nodeIndex = getOffsetInMemberList(iNodeId);
		printMembership();

		probeTable = new int[members.size()][members.size()];
		repopulateGrid();

		printGrid();
		printNeighborList();
	}

	private synchronized void handleMembershipChange(InitMsg im) {
		im.getMemberList(members);
		nodeIndex = getOffsetInMemberList(iNodeId);
		// printMembership();

		probeTable = new int[members.size()][members.size()];
		repopulateGrid();

		printGrid();
		printNeighborList();
	}

	// NOTE :: assumes that the state is locked already
	private void repopulateGrid() {
		numCols = (int) Math.ceil(Math.sqrt(members.size()));
		numRows = (int) Math.ceil((double) members.size() / (double) numCols);

		grid = new int[numRows][numCols];

		int m = 0;
		for (int i = 0; i < numRows; i++) {
			for (int j = 0; j < numCols; j++) {
				if (m >= members.size()) {
					m = 0;
				}
				grid[i][j] = members.get(m);
				m++;
			}
		}

		repopulateNeighborList();
		repopulateProbeTable();
	}

	// NOTE :: assumes that the state is locked already
	private void repopulateNeighborList() {
		if (neighbors != null) {
			/*
			 * for (Iterator it = neighbors.iterator (); it.hasNext (); ) {
			 * it.next(); it.remove(); // NOTE :: this is it.remove and not
			 * members.remove (which would result in a
			 * ConcurrentModificationException!) }
			 */
			neighbors.clear();
		}
		for (int i = 0; i < numRows; i++) {
			for (int j = 0; j < numCols; j++) {
				if (grid[i][j] == iNodeId) {
					// all the nodes in row i, and all the nodes in column j are
					// belong to us :)
					for (int x = 0; x < numCols; x++) {
						if (grid[i][x] != iNodeId) {
							addNeighborNode(grid[i][x]);
						}
					}
					for (int x = 0; x < numRows; x++) {
						if (grid[x][j] != iNodeId) {
							addNeighborNode(grid[x][j]);
						}
					}
				}
			}
		}
	}

	// NOTE :: assumes that the state is locked already
	private void repopulateProbeTable() {
		for (int i = 0; i < members.size(); i++) {
			if (i == nodeIndex) {
				probeTable[i][i] = 0;
			} else {
				probeTable[nodeIndex][i] = generator.nextInt();
			}
		}

		// for testing
		if (nodeIndex == 0) {
			for (int i = 1; i < members.size(); i++) {
				probeTable[nodeIndex][i] = 1;
			}
		} else {
			probeTable[nodeIndex][0] = 1;
		}
	}

	// NOTE :: assumes that the state is locked already
	private void addNeighborNode(int node_id) {
		synchronized (neighbors) {
			boolean bFlag = false;
			for (Integer nid : neighbors) {
				if (nid == node_id) {
					bFlag = true;
					break;
				}
			}
			if (bFlag == false) {
				neighbors.add(new Integer(node_id));
			}
		}
	}

	public int getCoordinatorPort() {
		return iCoordinatorPort;
	}

	public void printMembership() {
		String s = new String("Membership for Node " + iNodeId
				+ ". Membership = [");
		synchronized (members) {
			for (Integer memberId : members) {
				s += memberId + ", ";
			}
			s += "]";
		}
		log(s);
	}

	public void printNeighborList() {
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

	public void printGrid() {
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

	private static final class RoutingRec implements Serializable {
		public int dst;
		public int via; // the hop

		public RoutingRec(int dst, int via) {
			this.dst = dst;
			this.via = via;
		}
	}

	/**
	 * for each neighbor, find for him the min-cost hops to all other neighbors,
	 * and send this info to him (the intermediate node may be one of the
	 * endpoints, meaning a direct route is cheapest)
	 */
	public synchronized void sendAllNeighborsRoutingRecommendations() {
		for (int nid : neighbors) {
			ArrayList<RoutingRec> recs = new ArrayList<RoutingRec>();
			int min = Integer.MAX_VALUE, mini = -1;
			for (int dst : neighbors) {
				int srci = getOffsetInMemberList(nid);
				int dsti = getOffsetInMemberList(dst);
				for (int i = 0; i < probeTable[srci].length; i++) {
					int cur = probeTable[srci][i] + probeTable[dsti][i];
					if (cur < min) {
						min = cur;
						mini = i;
					}
				}
				recs.add(new RoutingRec(dst, mini));
			}
			sendObject(recs, nid);
		}
	}

	public void sendObject(Serializable o, int nid) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try {
			new ObjectOutputStream(buf).writeObject(o);
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
		sendBytes(buf.toByteArray(), nid);
	}

	public void sendBytes(byte[] buf, int nid) {
		try {
			// TODO are we sure we're sending to the right host here? (should be
			// a function of nid)
			InetAddress addr = InetAddress.getByName(sCoordinatorIp);
			int port = iCoordinatorPort + nid;
			DatagramPacket p = new DatagramPacket(buf, buf.length, addr, port);
			network.send(p);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	public synchronized void sendAllNeighborsAdjacencyTable() {
		RoutingMsg rm = new RoutingMsg(iNodeId, members, probeTable[nodeIndex]);

		try {
			byte[] buf = RoutingMsg.getBytes(rm);

			for (Integer neighborId : neighbors) {
				try {
					// System.out.println("Node " + iNodeId + ": sending to port
					// " + (iCoordinatorPort + neighborId));

					sendBytes(buf, neighborId);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public synchronized void updateNetworkState(RoutingMsg rm) {
		int offset = getOffsetInMemberList(rm.getOriginatorId());
		if (offset != -1) {
			rm.getProbeTable(probeTable[offset]);
		}
	}

	// NOTE :: assumes that the state is locked already
	private int getOffsetInMemberList(int nid) {
		for (int i = 0; i < members.size(); i++) {
			if (nid == members.get(i))
				return i;
		}
		return -1;
	}

	public void quit() {
		doQuit = true;
		if (must != null) {
			must.quit();
		}
		if (rust != null) {
			rust.quit();
		}
		if (rut != null) {
			rut.quit();
		}
	}
}

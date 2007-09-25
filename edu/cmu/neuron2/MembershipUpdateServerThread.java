package edu.cmu.neuron2;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;

import edu.cmu.neuron2.msg.BaseMsg;
import edu.cmu.neuron2.msg.MembershipMsg;

public class MembershipUpdateServerThread extends Thread {
	
	int iPort;
	int iNodeId;
	
	NeuRonNode parentHandle;
	
	boolean bQuit;

	Semaphore semDone;

	MembershipUpdateServerThread(int port, int node_id, NeuRonNode rn) {
		iPort = port;
		iNodeId = node_id;
		parentHandle = rn;

		bQuit = false;
		
		semDone = new Semaphore(0);
	}

	public void run() {
		try {
			
			DatagramSocket ds = new DatagramSocket(iPort);

			int i = 0;

			byte []b = new byte[65536];
			DatagramPacket dp = new DatagramPacket(b, b.length);

			System.out.println(iNodeId + " MembershipThread listening on port " + iPort);
			while (!bQuit) {
				ds.setSoTimeout(1000);
				try {
					ds.receive(dp);
					i++;
					//System.out.println(iNodeId + " RUST - incoming adjecency table!");
					//System.out.println(iNodeId + " RUST - incoming table!");
	
						// TODO :: check that the length is the same as b.length
						byte[] msg = dp.getData();
					    ByteArrayInputStream bis = new ByteArrayInputStream(msg);
					    DataInputStream dis = new DataInputStream(bis);
					    int type = dis.readInt();
						dis.close();
						bis.close();

						if (type == BaseMsg.MEMBERSHIP_MSG_TYPE) {
							MembershipMsg mm1  = MembershipMsg.getObject(msg);
							System.out.println("Got membership update: " + mm1.toString());

							parentHandle.handleMembershipChange(mm1);
						}
						else {
							System.out.println("UNKNOWN MSG type " + type + " in RoutingUpdateServerThread");
						}
					
				} catch (SocketTimeoutException ste) {
				}
			}
			ds.close();
			
		} catch(Exception ex) {
			throw new RuntimeException(ex);
		}
	    System.out.println(iNodeId + " MembershipUpdateServerThread quitting.");
		semDone.release();
    }

    public void quit() {
	    bQuit = true;
		semDone.acquireUninterruptibly();
    }
}

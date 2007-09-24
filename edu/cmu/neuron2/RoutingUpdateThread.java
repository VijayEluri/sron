package edu.cmu.neuron2;

import java.util.concurrent.Semaphore;

public class RoutingUpdateThread extends Thread {

	int iNodeId;
	IRonNode parentHandle;
	boolean bQuit;

	Semaphore semDone;

	RoutingUpdateThread(int node_id, IRonNode rn) {
		iNodeId = node_id;
		parentHandle = rn;

		bQuit = false;

		semDone = new Semaphore(0);
	}

	public void run() {

		while (!bQuit) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException ie) {
			}

			parentHandle.sendAllNeighborsAdjacencyTable();
		}
		System.out.println(iNodeId + " RoutingUpdateThread quitting.");
		semDone.release();
	}

	public void quit() {
		bQuit = true;
		semDone.acquireUninterruptibly();
	}
}

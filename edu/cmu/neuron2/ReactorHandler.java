package edu.cmu.neuron2;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface ReactorHandler {

    public void handle(Session session, InetSocketAddress src, ByteBuffer buf);

}

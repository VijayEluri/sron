package edu.cmu.neuron2;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RonTest {

    private static enum RunMode { SIM, DIST }

    public static void main(String[] args) throws Exception {
        new RonTest().run();
    }

    public void run() throws Exception {
        Properties props = new Properties();
        String config = System.getProperty("neuron.config");
        if (config != null) {
            props.load(new FileInputStream(config));
        }

        final List<NeuRonNode> nodes = new ArrayList<NeuRonNode>();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                for (NeuRonNode node : nodes) {
                    node.quit();
                }
            }
        });
        int numNodes = Integer.parseInt(props.getProperty("numNodes", "3"));
        int nodeId = Integer.parseInt(props.getProperty("nodeId", "0"));
        String coordinatorHost = props.getProperty("coordinatorHost",
                InetAddress.getLocalHost().getHostAddress());
        int basePort = Integer.parseInt(props.getProperty("basePort", "9000"));
        RunMode mode = RunMode.valueOf(props.getProperty("mode", "sim").toUpperCase());
        String simData = props.getProperty("simData", "");

        ExecutorService executor = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        switch (mode) {
        case SIM:
            for (int i = 0; i <= numNodes; i++) {
                NeuRonNode node = new NeuRonNode(i,
                        coordinatorHost,
                        basePort,
                        executor, scheduler, props);
                node.start();
                nodes.add(node);
            }
            sim(simData, nodes, scheduler);
            break;
        case DIST:
            NeuRonNode node = new NeuRonNode(nodeId,
                    coordinatorHost, basePort, executor, scheduler, props);
            node.start();
            nodes.add(node);
            break;
        }
    }
    
    private void sim(String datafile, final List<NeuRonNode> nodes,
            ScheduledExecutorService scheduler) {
        try {
            if (!datafile.equals("")) {
                BufferedReader reader = new BufferedReader(new FileReader(datafile));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    final int src = Integer.parseInt(parts[0]);
                    final int dst = Integer.parseInt(parts[1]);
                    double startTime = Double.parseDouble(parts[2]);
                    double stopTime = Double.parseDouble(parts[3]);
                    final ScheduledFuture<?> future = scheduler.schedule(new Runnable() {
                        public void run() {
                            nodes.get(dst).ignore(src);
                        }
                    }, (long) (startTime * 1000), TimeUnit.MILLISECONDS);
                    scheduler.schedule(new Runnable() {
                        public void run() {
                            if (!future.cancel(false)) {
                                nodes.get(dst).unignore(src);
                            }
                        }
                    }, (long) (stopTime * 1000), TimeUnit.MILLISECONDS);
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

}

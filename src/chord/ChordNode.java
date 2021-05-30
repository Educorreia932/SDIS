package chord;

import message.Message;
import message.PutChunkMsg;
import sender.MessageHandler;
import sender.Observer;
import sender.SockThread;
import state.State;
import utils.Pair;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Time;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

import static java.lang.Math.pow;

public class ChordNode implements ChordInterface, Observer {
    private final Integer id;              // The peer's unique identifier
    private final InetAddress address;     // The peer's network address;
    private final int port;
    private final ChordInterface[] fingerTable;
    private final MessageHandler messageHandler;
    private int nextFingerToFix;
    private ChordInterface predecessor;
    public Registry registry;
    private final SockThread sock;

    public static int m = 7; // Number of bits of the addressing space
    private static int SUCC_TIMEOUT = 3000;
    private static int MAX_TRIES = 3;

    public ChordNode(InetAddress address, int port, Registry registry) throws IOException {
        this.address = address;
        this.port = port;
        this.id = ChordNode.genId(address, port);
        this.sock = new SockThread(address, port, this);
        this.messageHandler = new MessageHandler(this.sock, this);

        this.registry = registry;
        this.nextFingerToFix = 0;
        this.fingerTable = new ChordInterface[m];

        // init node as if he was the only one in the network
        this.predecessor = null;
        this.setSuccessor(this);

        ChordInterface stub;
        try {
            stub = (ChordInterface) UnicastRemoteObject.exportObject(this, 0);
            registry.bind(this.id.toString(), stub);
            System.out.println("Registered node with id: " + this.id);
            System.out.println(this);
        } catch (Exception e) {
            System.err.println("Failed setting up the access point for use by chord node.");
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public int getId() {
        return id;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    /* CHORD */
    @Override
    public ChordInterface getPredecessor() {
        return predecessor;
    }

    @Override
    public ChordInterface getSuccessor() throws RemoteException {
        return this.fingerTable[0];
    }

    @Override
    public Map<Pair<String, Integer>, Integer> getStoredChunksIds() throws RemoteException {
        return State.st.getAllStoredChunksId();
    }

    private void setSuccessor(ChordInterface n) {
        this.fingerTable[0] = n;
        Map<Pair<String, Integer>, Integer> succStoredChunksIds;
        try {
            succStoredChunksIds = n.getStoredChunksIds();
        } catch (RemoteException ignored) {
            System.err.println("Couldn't get stored chunks of my new successor");
            return;
        }
        State.st.replaceSuccChunk(succStoredChunksIds);
    }

    private int getFingerStartId(int i) {
        return (this.id + (int) pow(2, i)) % (int) Math.pow(2, m);
    }

    /**
     * Make the node join a Chord ring, with n as its successor
     */
    public void join(ChordInterface nprime) throws RemoteException {
        this.predecessor = null;
        //this.setSuccessor(nprime.findSuccessor(this.getId()));

        for (int i = 0; i < m; ++i) {
            int fingerStartId = this.getFingerStartId(i);
            this.fingerTable[i] = nprime.findSuccessor(fingerStartId);
        }
    }

    /**
     * Called periodically.
     * The node asks the successor about its predecessor, verifies if its immediate successor is consistent,
     * and tells the successor about it
     */
    public void stabilize() throws RemoteException {
        // TODO try catch with list of succs
        // TODO stack trace no timer do stabilize para ver nos ctrl+c

        // update predecessor
        if (this.predecessor != null) {
            ChordInterface me$ = this.getSuccessor().getPredecessor();
            if (me$ != null) { // if pred knows about a succ
                if (ChordNode.inBetween(me$.getId(), this.id, this.getSuccessor().getId()))
                    this.setSuccessor(me$);
            }
        }
        this.getSuccessor().notify(this);
    }

    /**
     * Node n thinks it might be our predecessor.
     */
    @Override
    public void notify(ChordInterface nprime) throws RemoteException {
        int nprimeId = nprime.getId();

        if (this.predecessor == null ||
                ChordNode.inBetween(nprimeId, this.predecessor.getId(), this.id)) {
            this.predecessor = nprime;
        }
    }

    /**
     * Called periodically. refreshes finger table entries.
     */
    public void fixFingers() {
        if (nextFingerToFix >= m)
            nextFingerToFix = 0;

        int succId = this.getFingerStartId(nextFingerToFix);
        try {
            //System.out.println("I am " + this.id + " and I'm updating finger " + nextFingerToFix + ", id: " + succId);
            fingerTable[nextFingerToFix] = findSuccessor(succId);
            //System.out.println("They tell me it's: " + fingerTable[nextFingerToFix].getId());
        } catch (RemoteException e) {
            fingerTable[nextFingerToFix] = this;
            if (nextFingerToFix == 0) { // My successor died, call bakup protocol on the chunks i think he was storing
                this.backupSuccessorChunks();
            }
        }
        ++nextFingerToFix;
    }

    /**
     * Ask node n to find the successor of id
     */
    @Override
    public ChordInterface findSuccessor(int id) throws RemoteException {
        ChordInterface nprime = this.findPredecessor(id);
        return nprime.getSuccessor();
    }

    @Override
    public ChordInterface findPredecessor(int id) throws RemoteException {
        if (this.getId() == this.getSuccessor().getId())  // only node in network
            return this;

        ChordInterface ret = this;
        while (!(ChordNode.inBetween(id, ret.getId(), ret.getSuccessor().getId(), false, true))) {
            // System.out.println(id + " E (" + ret.getId() + ", " + ret.getSuccessor().getId() + ")");
            ret = ret.closestPrecedingFinger(id);
        }
        return ret;
    }

    /**
     * Search the local table for the highest predecessor of id
     */
    @Override
    public ChordInterface closestPrecedingFinger(int id) throws RemoteException {
        for (int i = m - 1; i >= 0; --i) {
            if (fingerTable[i] != null) {
                int succId = fingerTable[i].getId();
                //System.out.println("\t" + succId + " E (" + this.id + ", " + id + ")");
                if (ChordNode.inBetween(succId, this.id, id))
                    return fingerTable[i];
            }
        }
        return this;
    }

    /**
     * Called periodically. checks whether predecessor has failed.
     */
    public void checkPredecessor() {
        if (predecessor != null) {
            try {
                this.predecessor.getId();
            } catch (RemoteException e) {
                this.predecessor = null;
            }
        }
    }

    /**
     * Calculates SHA1 Algorithm and hashes string to an integer
     */
    public static int genId(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(s.getBytes());
            ByteBuffer wrapped = ByteBuffer.wrap(hash);
            int div = (int) Math.floor(pow(2, ChordNode.m));
            return Math.floorMod(wrapped.getInt(), div);
        } catch (NoSuchAlgorithmException ignored) {
        }
        return -1;
    }

    public static int genId(InetAddress address, int port) {
        return ChordNode.genId(address.getHostAddress() + ":" + port);
    }

    static boolean inBetween(int num, int lh, int rh, boolean closedLeft, boolean closedRight) {
        if (closedLeft && num == lh) return true;
        if (closedRight && num == rh) return true;

        if (num == lh || num == rh)
            return false;
        if (lh == rh)
            return true;
        if (num > lh && num < rh)
            return true;
        return lh > rh && (num < rh || num > lh);
    }

    static boolean inBetween(int num, int lf, int rh) {
        return ChordNode.inBetween(num, lf, rh, false, false);
    }

    private void backupSuccessorChunks() {
        System.out.println("\t\t\tMy succ died");
        for (var entry: State.st.getSuccChunksIds().entrySet()) {
            String fileId = entry.getKey().p1;
            Integer chunkNo = entry.getKey().p2, chunkId = entry.getValue();
            this.send(new PutChunkMsg(fileId, chunkNo, this.address, this.port, chunkId));
        }
    }

    /* TCP */
    public void start() {
        this.sock.start();
    }

    public void stop() {
        this.sock.interrupt();
    }

    public boolean messageIsForUs(Message message) {
        if (this.predecessor == null) // Assume message is for us if our predecessor bye
            return true;

        try {
            return message.destAddrKnown() || // the message was sent directly and without hops for us
                    ChordNode.inBetween(message.getDestId(), this.predecessor.getId(), this.id, false, true);
        } catch (RemoteException e) {
            return true; // Assume that message is for us if chord ring is broken temporarily
        }
    }

    private void sendToNode(Message message) {
        ChordInterface nextHopDest = null;
        try {
            nextHopDest = closestPrecedingFinger(message.getDestId());
        } catch (RemoteException e) {
            System.err.println("Could not find successor for message " + message);
            e.printStackTrace();
        }
        assert nextHopDest != null;
        try {
            if (nextHopDest == this)
                nextHopDest = getSuccessor();
            message.setDest(nextHopDest);
            message.addToPath(nextHopDest.getId());
        } catch (RemoteException e) { // TODO Max num of tries?
            System.err.println("Could connect to chosen next hop dest : TODO Max tries with timeout " + message);
            e.printStackTrace();
        }

        this.sock.send(message);
    }

    @Override
    public void handle(Message message) {
        System.out.print("\tReceived: " + message + " - ");
        // Message is for us
        if (this.messageIsForUs(message)) {
            System.out.println("Handling\n");
            messageHandler.handleMessage(message);
        }
        else { // message isn't for us
            System.out.println("Resending\n");
            this.sendToNode(message); // resend it through the chord ring
        }
    }

    public void send(Message message) {
        if (this.messageIsForUs(message)) {
            System.out.println("\tNot sending message (its for me): " + message + "\n");
            messageHandler.handleMessage(message);
        }
        else { // message isn't for us
            System.out.println("Sending: " + message + "\n");
            this.sendToNode(message); // resend it through the chord ring
        }
    }

    public void sendDirectly(Message message, InetAddress address, int port) {
        message.setDest(address, port);
        // We don't need the chord ring to hop to the dest so we set it to null
        message.setDestId(null);
        this.sock.send(message);
    }

    public void sendDirectly(Message message, ChordInterface node) throws RemoteException {
        this.sendDirectly(message, node.getAddress(), node.getPort());
    }

    public void sendToSucc(Message message) {
        ChordNode node = this;

        Timer timer = new Timer();
        TimerTask task = new java.util.TimerTask() {
            private int count = 0;

            @Override
            public void run() {
                try {
                    node.sendDirectly(message, node.getSuccessor());
                    timer.cancel();
                } catch (RemoteException e) {
                    System.out.println("FAILED ONCE");
                    ++count;
                    if (count == MAX_TRIES)
                        timer.cancel();
                }
            }
        };

        timer.scheduleAtFixedRate(
                task,
                100,
                100
        );
    }

    public void addChunkFuture(String fileId, int currChunk, CompletableFuture<byte[]> fut) {
        this.messageHandler.addChunkFuture(fileId, currChunk, fut);
    }

    @Override
    public String toString() {
        StringBuilder res = new StringBuilder("FingerTable:\n");
        for (int i = 0; i < m; ++i) {
            res.append("\t").append(i).append("(").append(this.getFingerStartId(i)).append("): ");
            try {
                res.append(this.fingerTable[i].getId()).append("\n");
            } catch (RemoteException | NullPointerException e) {
                res.append("Cant get to node\n");
            }
        }
        try {
            res.append("Succ: ").append(this.getSuccessor().getId()).append("\n");
        } catch (RemoteException e) {
            res.append("Succ: Can't get succ\n");
        }
        if (predecessor != null) {
            try {
                res.append("Pred: ").append(predecessor.getId()).append("\n");
            } catch (RemoteException e) {
                res.append("Pred: Can't get pred\n");
            }
        } else res.append("Pred: Can't get pred\n");

        return "Chord id: " + id + "\n" + res + "\n"
                + "Sock: " + this.sock;
    }
}
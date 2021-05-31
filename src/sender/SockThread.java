package sender;

import message.Message;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SockThread implements Runnable {
    private static final int MAX_CONNS = 5;
    private static final String password = "123456";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService threadPool =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ServerSocketChannel serverSocketChannel;
    private final InetAddress address;
    private final Integer port;
    private final Observer observer;

    private SSLContext sslc;
    private Selector selector;

    public SockThread(InetAddress address, Integer port, Observer chordNode) throws IOException {
        System.setProperty("jdk.tls.acknowledgeCloseNotify", "true");

        this.address = address;
        this.port = port;
        this.observer = chordNode;

        try {
            // password for the keys
            char[] passphrase = password.toCharArray();
            // initialize key and trust material
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream("../keys/client.keys"), passphrase);
            KeyStore ts = KeyStore.getInstance("JKS");
            ts.load(new FileInputStream("../keys/truststore"), passphrase);

            // KeyManager decides which key to use
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, passphrase);
            // TrustManager decides whether to allow connections
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ts);

            // get instance of SSLContext for TLS protocols
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            this.sslc = sslCtx;
        } catch (KeyStoreException | CertificateException | NoSuchAlgorithmException | IOException | UnrecoverableKeyException | KeyManagementException e) {
            e.printStackTrace();
        }

        this.selector = SelectorProvider.provider().openSelector();
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.configureBlocking(false);
        this.serverSocketChannel.socket().bind(new InetSocketAddress(address, port), MAX_CONNS);
        this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
    }

    public InetAddress getAddress() {
        return address;
    }

    public Integer getPort() {
        return port;
    }

    public void close() {
        this.threadPool.shutdown();

        try {
            this.serverSocketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void interrupt() {
        running.set(false);
    }

    public void start() {
        Thread worker = new Thread(this);
        worker.start();
    }

    private ByteBuffer handleOverflow(SSLEngine engine, ByteBuffer dst) {
        // Maybe need to enlarge the peer application data buffer if
        // it is too small, and be sure you've compacted/cleared the
        // buffer from any previous operations.
        int appSize = engine.getSession().getApplicationBufferSize();
        if (appSize > dst.capacity()) {
            ByteBuffer b = ByteBuffer.allocate(appSize + dst.position());
            dst.flip();
            b.put(dst);
            return b;
        } else {
            dst.compact();
            return dst;
        }
    }

    private ByteBuffer handleUnderflow(SSLEngine engine, ByteBuffer src, ByteBuffer dst) {
        // Not enough inbound data to process. Obtain more network data
        // and retry the operation. You may need to enlarge the peer
        // network packet buffer, and be sure you've compacted/cleared
        // the buffer from any previous operations.
        int netSize = engine.getSession().getPacketBufferSize();
        // Resize buffer if needed.
        if (netSize > dst.capacity()) {
            ByteBuffer b = ByteBuffer.allocate(netSize);
            src.flip();
            b.put(src);
            return b;
        } else {
            dst.compact();
        }
        return src;
    }

    private ByteBuffer handleWrapOverflow(SSLEngine engine, ByteBuffer dst) {
        ByteBuffer buf;
        int netSize = engine.getSession().getPacketBufferSize();
        if (dst.capacity() < netSize) {
            buf = ByteBuffer.allocate(netSize);
        } else {
            buf = ByteBuffer.allocate(dst.capacity() * 2);
        }
        return buf;
    }

    private void runDelegatedTasks(SSLEngine engine) {
        Runnable task;
        while ((task = engine.getDelegatedTask()) != null) {
            // this.threadPool.execute(task);
            task.run();
        }
    }

    private int doHandshake(SSLEngine engine, SocketChannel socketChannel,
                            ByteBuffer myNetData, ByteBuffer peerNetData, boolean isEnd) throws IOException {
        // See example 8-2 of the docs
        // https://docs.oracle.com/en/java/javase/11/security/java-secure-socket-extension-jsse-reference-guide.html#GUID-AC6700ED-ADC4-41EA-B111-2AEF2CBF7744

        // Create byte buffers to use for holding application data
        int appBufferSize = engine.getSession().getApplicationBufferSize() + 50;
        ByteBuffer myAppData = ByteBuffer.allocate(appBufferSize);
        ByteBuffer peerAppData = ByteBuffer.allocate(appBufferSize);

        if (isEnd) engine.closeOutbound();
        else engine.beginHandshake();

        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
                hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            SSLEngineResult res;
            switch (hs) {
                case NEED_UNWRAP:
                    if (socketChannel.read(peerNetData) < 0) {
                        // end of stream => no more io
                        engine.closeInbound();
                        engine.closeOutbound();
                        hs = engine.getHandshakeStatus();
                        break;
                    }

                    // process incoming handshake data
                    peerNetData.flip();
                    res = engine.unwrap(peerNetData, peerAppData);
                    peerNetData.compact();
                    hs = res.getHandshakeStatus();

                    // check status
                    switch (res.getStatus()) {
                        case OK:
                            // do nothing ?
                            break;
                        case BUFFER_OVERFLOW:
                            // the client maximum fragment size config does not work?
                            peerAppData = this.handleOverflow(engine, peerAppData);
                            break;
                        case BUFFER_UNDERFLOW:
                            // bad packet, or the client maximum fragment size config does not work?
                            // we can increase the size
                            peerNetData = this.handleUnderflow(engine, peerNetData, peerAppData);
                            // Obtain more inbound network data for src,
                            // then retry the operation.
                            break;
                        case CLOSED:
                            engine.closeOutbound();
                            return 0;
                        default:
                            throw new IllegalStateException("Unexpected value: " + res.getStatus());
                    }
                    break;
                case NEED_WRAP:
                    // ensure that any previous net data in myNetData has been sent to the peer
                    /*
                    while (myNetData.hasRemaining())
                        socketChannel.write(myNetData);
                     */
                    // clear the rest of the buffer
                    myNetData.clear();

                    // generate handshake data to send (if possible)
                    res = engine.wrap(myAppData, myNetData);
                    hs = res.getHandshakeStatus();
                    switch (res.getStatus()) {
                        case OK:
                            myNetData.flip();
                            while (myNetData.hasRemaining())
                                socketChannel.write(myNetData);
                            break;
                        case BUFFER_OVERFLOW:
                            // the client maximum fragment size config does not work?
                            // we can increase the size
                            myNetData = this.handleWrapOverflow(engine, myNetData);
                            break;
                        case BUFFER_UNDERFLOW:
                            throw new SSLException("WTF UNDERFLOW");
                        case CLOSED:
                            engine.closeOutbound();
                            return 0;
                        default:
                            throw new IllegalStateException("Unexpected value: " + res.getStatus());
                    }
                    break;
                case NEED_TASK:
                    runDelegatedTasks(engine);
                    hs = engine.getHandshakeStatus();
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + hs);
            }
        }

        return 0;
    }

    private int doHandshake(SSLEngine engine, SocketChannel socketChannel,
                            ByteBuffer myNetData, ByteBuffer peerNetData) throws IOException {
        return this.doHandshake(engine, socketChannel, myNetData, peerNetData, false);
    }

    private void closeSSLConnection(SSLEngine engine, SocketChannel socketChannel, ByteBuffer myNetData) throws IOException {
        engine.closeOutbound();
        while (myNetData.hasRemaining())
            socketChannel.write(myNetData);

        myNetData.clear();
        SSLEngineResult byebye = engine.wrap(ByteBuffer.allocate(0), myNetData);
        if (byebye.getStatus() != SSLEngineResult.Status.CLOSED)
            throw new IOException("Invalid state for closure.");

        myNetData.flip();
        while (myNetData.hasRemaining()) {
            try {
                socketChannel.write(myNetData);
            } catch (IOException ignored) {
                break;
            }
        }

        while (!engine.isOutboundDone()) { /* zzz */ }
        socketChannel.close();
    }

    private ByteBuffer[] createBuffers(SSLEngine engine) {
        SSLSession session = engine.getSession();
        // allocate extra space to prevent some overflows
        int appBufferMax = session.getApplicationBufferSize() + 50;
        int netBufferMax = session.getPacketBufferSize();

        ByteBuffer[] ret = new ByteBuffer[4];
        // my appData
        ret[0] = ByteBuffer.allocate(appBufferMax);
        // my netData
        ret[1] = ByteBuffer.allocate(netBufferMax);
        // peer appData
        ret[2] = ByteBuffer.allocate(appBufferMax);
        // peer netData
        ret[3] = ByteBuffer.allocate(netBufferMax);
        return ret;
    }

    private void acceptCon(SelectionKey key) {
        SocketChannel socketChannel;
        try {
            socketChannel = ((ServerSocketChannel) key.channel()).accept();
            socketChannel.configureBlocking(false);
        } catch (IOException e) {
            System.err.println("Timed out while waiting for answer (Sock thread) " + this);
            return;
        }

        // create SSLEngine
        SSLEngine engine = this.sslc.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        // create buffers
        ByteBuffer[] bufs = this.createBuffers(engine);

        try {
            if (this.doHandshake(engine, socketChannel, bufs[1], bufs[3]) != 0) {
                System.err.println("Handshake failed");
            } else {
                socketChannel.register(this.selector, SelectionKey.OP_READ,
                        new SSLEngineData(engine, bufs[0], bufs[1], bufs[2], bufs[3]));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        running.set(true);
        while (running.get()) {
            try {
                this.selector.select();
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }

            Iterator<SelectionKey> selectedKeys = this.selector.selectedKeys().iterator();
            while (selectedKeys.hasNext()) {
                SelectionKey key = selectedKeys.next();
                selectedKeys.remove();
                if (!key.isValid())
                    continue;

                if (key.isAcceptable()) {
                    this.acceptCon(key);
                } else if (key.isReadable()) {
                    try {
                        SocketChannel socketChannel = (SocketChannel) key.channel();
                        SSLEngineData d = (SSLEngineData) key.attachment();
                        ByteArrayOutputStream content = this.read(socketChannel, d);

                        // create message instance from the received bytes
                        ByteArrayInputStream bis = new ByteArrayInputStream(content.toByteArray());
                        ObjectInput in = new ObjectInputStream(bis);
                        Message msg = (Message) in.readObject();
                        bis.close();
                        // handle message
                        this.threadPool.execute(
                                () -> {
                                    this.observer.handle(msg);
                                });
                    } catch (IOException | ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private ByteArrayOutputStream read(SocketChannel socketChannel, SSLEngineData d) throws IOException, ClassNotFoundException {
        // receive loop - read TLS encoded data from peer
        int n = socketChannel.read(d.peerNetData);

        // end of stream state
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        if (n < 0) {
            System.err.println("Got end of stream from peer. Attempting to close connection.");
            try {
                d.engine.closeInbound();
            } catch (SSLException e) {
                System.err.println("Peer didn't follow the correct connection end procedure.");
            }

            this.closeSSLConnection(d.engine, socketChannel, d.myNetData);
            return content;
        } else if (n == 0) {
            return content;
        }

        d.peerNetData.flip();
        // process incoming data
        while (d.peerNetData.hasRemaining()) {
            SSLEngineResult res;
            try {
                res = d.engine.unwrap(d.peerNetData, d.peerAppData);
            } catch (SSLException e) {
                this.closeSSLConnection(d.engine, socketChannel, d.myNetData);
                return content;
            }

            switch (res.getStatus()) {
                case OK:
                    // flip it to create the message object bellow
                    d.peerAppData.flip();
                    try {
                        content.write(Arrays.copyOfRange(d.peerAppData.array(), 0, d.peerAppData.limit()));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    break;
                case BUFFER_OVERFLOW:
                    d.peerAppData = this.handleOverflow(d.engine, d.peerAppData);
                    break;
                case BUFFER_UNDERFLOW:
                    d.peerNetData = this.handleUnderflow(d.engine, d.peerNetData, d.peerAppData);
                    break;
                case CLOSED:
                    try {
                        this.closeSSLConnection(d.engine, socketChannel, d.myNetData);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return content;
            }
        }

        return content;
    }

    private void write(SocketChannel socketChannel, SSLEngineData d) throws IOException {
        // send loop
        while (d.myAppData.hasRemaining()) {
            SSLEngineResult res;
            res = d.engine.wrap(d.myAppData, d.myNetData);

            System.out.println(res);

            switch (res.getStatus()) {
                case OK:
                    d.myNetData.flip();
                    // send TLS encoded data to peer
                    while (d.myNetData.hasRemaining()) {
                        socketChannel.write(d.myNetData);
                    }
                    break;
                case BUFFER_OVERFLOW:
                    d.myNetData = this.handleWrapOverflow(d.engine, d.myNetData);
                    break;
                case BUFFER_UNDERFLOW:
                    throw new SSLException("WTF UNDERFLOW");
                case CLOSED:
                    this.closeSSLConnection(d.engine, socketChannel, d.peerNetData);
                    return;
            }
        }
    }

    public void send(Message message) {
        System.out.println("Sent: " + message + "\n");

        InetAddress address = message.getDestAddress();
        int port = message.getDestPort();
        // create socket channel
        SocketChannel socketChannel;
        try {
            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.connect(new InetSocketAddress(address, port));
            while (!socketChannel.finishConnect()) { /* busy-wait */ }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // create SSLEngine
        SSLEngine engine = this.sslc.createSSLEngine(address.getHostAddress(), port);
        engine.setUseClientMode(true);
        // create buffers
        ByteBuffer[] bufs = this.createBuffers(engine);
        try {
            if (this.doHandshake(engine, socketChannel, bufs[1], bufs[3]) != 0) {
                System.err.println("Handshake failed");
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Handshake failed");
            return;
        }

        // prepare message to send
        byte[] dataToSend;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ObjectOutputStream outputStream = new ObjectOutputStream(bos);
            outputStream.writeObject(message);
            outputStream.flush();
            dataToSend = bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // send message
        bufs[0].clear();
        bufs[0].put(dataToSend);
        bufs[0].flip();
        SSLEngineData d = new SSLEngineData(engine, bufs[0], bufs[1], bufs[2], bufs[3]);
        try {
            this.write(socketChannel, d);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // read
        try {
            ByteArrayOutputStream content = this.read(socketChannel, d);
            System.out.println(content.toString());
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        // close connection
        try {
            this.closeSSLConnection(engine, socketChannel, bufs[1]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String toString() {
        return address + ":" + port + "\n";
    }
}
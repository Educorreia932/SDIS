package sender;

import message.Message;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
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
            SSLContext sslCtx = SSLContext.getInstance("TLSv1.2");
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
                            ByteBuffer myNetData, ByteBuffer peerNetData) throws IOException {
        // See example 8-2 of the docs
        // https://docs.oracle.com/en/java/javase/11/security/java-secure-socket-extension-jsse-reference-guide.html#GUID-AC6700ED-ADC4-41EA-B111-2AEF2CBF7744

        // Create byte buffers to use for holding application data
        int appBufferSize = engine.getSession().getApplicationBufferSize() + 50;
        ByteBuffer myAppData = ByteBuffer.allocate(appBufferSize);
        ByteBuffer peerAppData = ByteBuffer.allocate(appBufferSize);

        engine.beginHandshake();

        SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
                hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            SSLEngineResult res;
            switch (hs) {
                case NEED_UNWRAP:
                    if (socketChannel.read(peerNetData) < 0) {
                        // end of stream => no more io
                        engine.closeOutbound();
                        try {
                            engine.closeInbound();
                        } catch (SSLException e) {
                            System.err.println("Peer didn't follow the correct connection end procedure.");
                        }
                        return 1;
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
        // create SSLEngine
        SSLEngine engine = this.sslc.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        // create buffers
        ByteBuffer[] bufs = this.createBuffers(engine);

        SocketChannel socketChannel;
        try {
            socketChannel = ((ServerSocketChannel) key.channel()).accept();
            socketChannel.configureBlocking(false);
            socketChannel.register(this.selector, SelectionKey.OP_READ,
                    new SSLEngineData(engine, bufs[0], bufs[1], bufs[2], bufs[3], true));
        } catch (IOException e) {
            System.err.println("Timed out while waiting for answer (Sock thread) " + this);
            return;
        }

        try {
            if (this.doHandshake(engine, socketChannel, bufs[1], bufs[3]) != 0) {
                System.err.println("Handshake failed (accept con)");
                socketChannel.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                socketChannel.close();
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
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

                        this.read(socketChannel, d);

                        // create message instance from the received bytes
                        if (d.content.size() > 0) {
                            ByteArrayInputStream bis = new ByteArrayInputStream(d.content.toByteArray());
                            d.content.reset();

                            ObjectInput in = new ObjectInputStream(bis);
                            Message msg = (Message) in.readObject();
                            bis.close();

                            // handle message
                            this.threadPool.execute(
                                    () -> {
                                        this.observer.handle(msg);
                                    });
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private boolean read(SocketChannel socketChannel, SSLEngineData d)
            throws IOException, ClassNotFoundException {
        d.peerNetData.clear();
        // receive loop - read TLS encoded data from peer
        int n = socketChannel.read(d.peerNetData);
        // end of stream
        if (n < 0) {
            System.err.println("Got end of stream from peer. Attempting to close connection.");
            try {
                d.engine.closeInbound();
            } catch (SSLException e) {
                System.err.println("Peer didn't follow the correct connection end procedure.");
            }
            this.closeSSLConnection(socketChannel, d);
            return true;
        }

        // process incoming data
        d.peerNetData.flip();
        while (d.peerNetData.hasRemaining()) {
            SSLEngineResult res;
            res = d.engine.unwrap(d.peerNetData, d.peerAppData);

            //System.out.println("READ: " + res);

            switch (res.getStatus()) {
                case OK:
                    d.peerAppData.flip();
                    d.content.write(Arrays.copyOfRange(d.peerAppData.array(), d.peerAppData.position(),
                            d.peerAppData.limit()));
                    d.peerAppData.clear();
                    break;
                case BUFFER_OVERFLOW:
                    d.peerAppData = this.handleOverflow(d.engine, d.peerAppData);
                    break;
                case BUFFER_UNDERFLOW:
                    d.peerNetData = this.handleUnderflow(d.engine, d.peerNetData, d.peerAppData);
                    break;
                case CLOSED:
                    this.closeSSLConnection(socketChannel, d);
                    return true;
            }
        }

        return false;
    }

    private void write(SocketChannel socketChannel, SSLEngineData d) throws IOException {
        // send loop
        while (d.myAppData.hasRemaining()) {
            SSLEngineResult res;
            res = d.engine.wrap(d.myAppData, d.myNetData);

            //System.out.println("WRITE " + res);

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
                    System.out.println("Premature closure");
                    this.closeSSLConnection(socketChannel, d);
                    return;
            }
        }
    }

    private void sendInner(Message message) {
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
                System.err.println("Handshake failed (init handshake)");
                return;
            }
            // socketChannel.configureBlocking(true);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Handshake failed (init handshake 2)");
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

        // TODO
        // IMP keep this here because there's a race condition involving the server selector
        try {
            Thread.sleep(65);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // send message
        SSLEngineData d = new SSLEngineData(engine, bufs[0], bufs[1], bufs[2], bufs[3], false);
        d.myAppData.clear().put(dataToSend).flip();
        try {
            this.write(socketChannel, d);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // close connection
        try {
            this.closeSSLConnectionClient(socketChannel, d);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void send(Message message) {
        this.threadPool.execute(
            () -> {
                this.sendInner(message);
        });
    }

    private void closeSSLConnectionServer(SocketChannel socketChannel, SSLEngineData d) throws IOException {
        /*
        System.out.println("Waiting for closure");
        xau:
        while (true) {
            d.peerNetData.clear();
            int n = socketChannel.read(d.peerNetData);
            if (n < 0) {
                d.engine.closeOutbound();
                socketChannel.close();
               return;
            } else if (n == 0) {
                continue;
            }

            d.peerNetData.flip();
            while (d.peerNetData.hasRemaining()) {
                SSLEngineResult res = d.engine.unwrap(d.peerNetData, d.peerAppData);
                System.out.println("Closure: " + res);
                if (res.getStatus() == SSLEngineResult.Status.CLOSED)
                    break xau;
            }
        }
        d.engine.closeInbound();
        System.out.println("Closed inbound");
         */

        d.engine.closeInbound();

        // System.out.println("Closing outbound");
        d.engine.closeOutbound();
        d.myAppData.clear().flip(); // empty buffer
        while (!d.engine.isOutboundDone()) {
            d.myNetData.clear();
            SSLEngineResult res = d.engine.wrap(d.myAppData, d.myNetData);

            // System.out.println("OUTBOUND " + res);

            d.myNetData.flip();
            while (d.myNetData.hasRemaining())
                socketChannel.write(d.myNetData);
        }
        // System.out.println("Closed outbound");

        // xau rossetta
        socketChannel.close();
        // System.out.println("XAU SOCKET");
    }

    private void closeSSLConnectionClient(SocketChannel socketChannel, SSLEngineData d) throws IOException {
        // System.out.println("Closing outbound");
        d.engine.closeOutbound();
        // send bye bye
        d.myAppData.clear().flip(); // empty buffer
        while (!d.engine.isOutboundDone()) {
            d.myNetData.clear();
            SSLEngineResult res = d.engine.wrap(d.myAppData, d.myNetData);

            //System.out.println("OUTBOUND " + res);

            d.myNetData.flip();
            while (d.myNetData.hasRemaining())
                socketChannel.write(d.myNetData);
        }
        // System.out.println("Closed outbound");

        // System.out.println("Waiting for closure");
        xau:
        while (true) {
            d.peerNetData.clear();
            int n = socketChannel.read(d.peerNetData);
            if (n < 0) {
                d.engine.closeOutbound();
                socketChannel.close();
                return;
            } else if (n == 0) {
                continue;
            }

            d.peerNetData.flip();
            while (d.peerNetData.hasRemaining()) {
                SSLEngineResult res = d.engine.unwrap(d.peerNetData, d.peerAppData);
                //System.out.println("Closure: " + res);
                if (res.getStatus() == SSLEngineResult.Status.CLOSED)
                    break xau;
            }
        }
        d.engine.closeInbound();
        // System.out.println("Closed inbound");

        // xau rossetta
        socketChannel.close();
        // System.out.println("XAU SOCKET");
    }

    private void closeSSLConnection(SocketChannel socketChannel, SSLEngineData d) throws IOException {
        if (d.isServer) this.closeSSLConnectionServer(socketChannel, d);
        else this.closeSSLConnectionClient(socketChannel, d);
    }

    @Override
    public String toString() {
        return address + ":" + port + "\n";
    }
}
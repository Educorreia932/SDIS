package subprotocols;

import channels.MDB_Channel;
import messages.PutChunkMessage;
import peer.storage.Chunk;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

public class Backup implements Runnable {
    private final File file;
    private final String file_id;
    private final String version;
    private final int replication_degree;
    private final int initiator_peer;
    private final MDB_Channel channel;

    public Backup(int peer_id, String version, File file, String file_id, int replication_degree, MDB_Channel channel) {
        this.initiator_peer = peer_id;
        this.version = version;
        this.file = file;
        this.file_id = file_id;
        this.replication_degree = replication_degree;
        this.channel = channel;
    }

    @Override
    public void run() {
        sendPutChunkMessage();
    }

    /**
     * Reads file and splits it into chunks to send PUTCHUNK messages
     */
    private void sendPutChunkMessage() {
        int chunk_no = 0;
        byte[] chunk = new byte[Chunk.MAX_CHUNK_SIZE];
        PutChunkMessage message = new PutChunkMessage(version, initiator_peer, file_id, replication_degree, chunk_no);

        try {
            FileInputStream inputStream = new FileInputStream(file.getPath());
            int read_bytes;

            // Read chunk from file
            while ((read_bytes = inputStream.read(chunk)) != -1) {
                message.setChunkNo(chunk_no);
                byte[] message_bytes = message.getBytes(chunk);
                message_bytes = Arrays.copyOf(message_bytes, read_bytes);

                // Send message to MDB multicast data channel
                channel.send(message_bytes);

                // System.out.println("> Peer " + initiator_peer + " sent " + message_bytes.length + " bytes");

                chunk_no++; // Increment chunk number
            }
        }

        catch (IOException e) {
            e.printStackTrace();
        }
    }
}

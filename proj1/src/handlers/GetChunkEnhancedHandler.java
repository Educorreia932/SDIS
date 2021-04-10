package handlers;

import channels.MDR_Channel;
import messages.ChunkMessage;
import messages.GetChunkEnhancedMsg;
import peer.Peer;
import peer.storage.Storage;
import utils.Pair;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class GetChunkEnhancedHandler extends MessageHandler {
    private final int chunk_no;
    private final int peer_id;
    private final String version;
    private final MDR_Channel restore_channel;
    private Socket socket;

    public GetChunkEnhancedHandler(GetChunkEnhancedMsg get_chunk_msg, Peer peer) {
        super(get_chunk_msg.getFile_id(), peer.storage);
        chunk_no = get_chunk_msg.getChunk_no();
        version = get_chunk_msg.getVersion();
        peer_id = peer.id;
        restore_channel = peer.getRestore_channel();

        try {
            socket = new Socket("localhost", get_chunk_msg.getPort());
        }

        catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        File chunk = storage.getFile(file_id, chunk_no);

        if (chunk == null)
            return; // Chunk is not stored

        ChunkMessage message = new ChunkMessage(version, peer_id, file_id, chunk_no);

        try {
            // Read chunk
            Path path = Paths.get(chunk.getPath());
            AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);

            ByteBuffer buffer = ByteBuffer.allocate(Storage.MAX_CHUNK_SIZE);

            Future<Integer> operation = fileChannel.read(buffer, 0); // Read chunk

            int read_bytes = operation.get();

            // Get message byte array
            byte[] message_bytes = message.getBytes(buffer.array(), read_bytes);

            // Clean hash map
            restore_channel.received_chunks.remove(Pair.create(file_id, chunk_no));

            int sleep_time = new Random().nextInt(400); // Sleep (0-400)ms

            ScheduledThreadPoolExecutor scheduledPool = new ScheduledThreadPoolExecutor(1);
            scheduledPool.schedule(() -> {
                // Abort if received chunk message
                if (restore_channel.received_chunks.remove(Pair.create(file_id, chunk_no)) != null)
                    return;

                // Send message
                try {
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    outputStream.write(message_bytes);
                    System.out.printf("< Peer %d Sent: %s\n", peer_id, message); // Log
                    outputStream.flush();
                }

                catch (IOException e) {
                    e.printStackTrace();
                }
            }, sleep_time, TimeUnit.MILLISECONDS);
        }

        catch (IOException | ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}

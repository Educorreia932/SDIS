package messages;

public class DeleteMessage extends Message {

    public DeleteMessage(String version, int sender_id, String file_id) {
        super(version, "DELETE", sender_id, file_id);
    }

    public DeleteMessage(String[] header_fields){
        super(header_fields);
    }
}

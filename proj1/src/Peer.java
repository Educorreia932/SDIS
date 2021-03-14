import Channels.*;

import java.rmi.AlreadyBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class Peer implements Client{
  private int id;
  private String version, access_point;
  private MC_Channel mc_channel;
  private MDB_Channel mdb_channel;
  private MDR_Channel mdr_channel;

  public static void main(String[] args) {
    if(args.length != 9){
      usage();
      System.exit(1);
    }

    Peer peer_obj = new Peer(args);
    try { //RMI
      Client client_stub = (Client) UnicastRemoteObject.exportObject(peer_obj,0);
      // Bind the remote object's stub in the registry
      Registry registry = LocateRegistry.getRegistry();
      registry.bind(peer_obj.access_point, client_stub);
    } catch (RemoteException | AlreadyBoundException e) {
      e.printStackTrace();
    }

    // TODO: FileSystem
  }

  public Peer(String[] args){
    this.version = args[0];
    try {
      this.id = Integer.parseInt(args[1]);
      this.access_point = args[2];
      this.mc_channel = new MC_Channel(args[3], Integer.parseInt(args[4]));
      this.mdb_channel = new MDB_Channel(args[5], Integer.parseInt(args[6]));
      this.mdr_channel = new MDR_Channel(args[7], Integer.parseInt(args[8]));

    }catch (NumberFormatException e){
      System.out.println("Exception: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void usage(){
    System.out.println("Usage: <protocol version> <peer ID> <service access point> <MC> <MDB> <MDR>");
  }

  @Override
  public void backupFile(String file_pathname, int replication_degree) {
    System.out.println("Not implemented yet");
  }

  @Override
  public void restoreFile(String file_pathname) {
    System.out.println("Not implemented yet");
  }

  @Override
  public void deleteFile(String file_pathname) {
    System.out.println("Not implemented yet");
  }

  @Override
  public void setStorageSpace(int max_space){
    System.out.println("Not implemented yet");
  }

  @Override
  public void getStateInformation() {
    System.out.println("Not implemented yet");
  }
}

import HDFS.hdfs;
import com.google.protobuf.ByteString;

import java.io.*;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;


public class Datanode implements Datanodedef {
    private static String namenode_ip = "127.0.0.1";

    private Datanode() {
    }

    public byte[] readBlock(byte[] inp) throws RemoteException {
        File file_dir = new File("Blocks");
        hdfs.ReadBlockResponse.Builder response = hdfs.ReadBlockResponse.newBuilder().setStatus(1);
        try {
            int block_size = 16777216; /* 16 KB */
            hdfs.ReadBlockRequest request = hdfs.ReadBlockRequest.parseFrom(inp);
            int block_num = request.getBlockNumber();
            File block = new File(file_dir, String.valueOf(block_num));
            FileInputStream input = new FileInputStream(block);
            byte[] data = new byte[block_size];

            while ((input.read(data)) != -1) {
                ByteString block_data = ByteString.copyFrom(data);
                response.addData(block_data);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return response.build().toByteArray();
    }

    public byte[] writeBlock(byte[] inp) throws RemoteException {
        File file_dir = new File("Blocks");
        try {
            hdfs.WriteBlockRequest request = hdfs.WriteBlockRequest.parseFrom(inp);
            int block_num = request.getBlockInfo().getBlockNumber();
            File block = new File(file_dir, String.valueOf(block_num));
            FileOutputStream out = new FileOutputStream(block);
            List<ByteString> data_list = request.getDataList();
            for (ByteString data : data_list) {
                byte[] res = data.toByteArray();
                int i = res.length;
                while (i-- > 0 && res[i] == 0) {
                    /* Removing Trailing Nulls */
                }
                i++;
                byte[] output = new byte[i + 1];
                System.arraycopy(res, 0, output, 0, i + 1);
                out.write(output);
            }
            out.close();
            File report = new File("block_report.txt");
            FileWriter writer = new FileWriter(report.getName(), true);
            BufferedWriter output = new BufferedWriter(writer);

            output.write(Integer.toString(block_num));
            output.newLine();
            output.close();
            /* Do the Replication */
            if (request.getReplicate()) {
                hdfs.DataNodeLocation location_data = request.getBlockInfo().getLocations(1);
                String ip = location_data.getIp();
                int port = location_data.getPort();
                Registry reg = LocateRegistry.getRegistry(ip, port);
                Datanodedef stub = (Datanodedef) reg.lookup("DataNode");
                hdfs.WriteBlockRequest.Builder replica = hdfs.WriteBlockRequest.newBuilder();
                replica.setBlockInfo(request.getBlockInfo());
                replica.setReplicate(false);
                data_list.forEach(replica::addData);
                stub.writeBlock(replica.build().toByteArray());
            }
            return hdfs.WriteBlockResponse.newBuilder().setStatus(1).build().toByteArray();

        } catch (IOException | NotBoundException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static void main(String[] args) {
        PrintStream err = new PrintStream(System.err);
        if (args.length < 1) {
            err.println("Need Data Node ID as an argument");
            System.exit(-1);
        }
        File file_dir = new File("Blocks");
        if (!file_dir.exists()) {
            file_dir.mkdirs();
        }
        int myid = Integer.valueOf(args[0]);
        Datanode obj = new Datanode();
        try {
            Registry reg = LocateRegistry.getRegistry();
            Datanodedef stub = (Datanodedef) UnicastRemoteObject.exportObject(obj, 0);
            reg.rebind("DataNode", stub);
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        HeartbeatHandler handler1 = new HeartbeatHandler(myid);
        handler1.start();
        BlockreportHandler handler2 = new BlockreportHandler(myid);
        handler2.start();
    }

    private static class HeartbeatHandler extends Thread {
        /* Handles the heart beat */
        private int id;

        HeartbeatHandler(int node_id) {
            id = node_id;
        }

        public void run() {
            try {
                while (true) {
                /* Send Periodically HeartBeat */
                    hdfs.HeartBeatRequest.Builder request = hdfs.HeartBeatRequest.newBuilder();
                    request.setId(id);
                    Registry reg = LocateRegistry.getRegistry(namenode_ip);
                    Namenodedef stub = (Namenodedef) reg.lookup("NameNode");
                    stub.heartBeat(request.build().toByteArray());
                    System.err.println("Sent HeartBeat from Node " + id);
                    Thread.sleep(10000); /* Sleep for 10 Seconds */
                }
            } catch (RemoteException | NotBoundException | InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static class BlockreportHandler extends Thread {
        /* Handles the heart beat */
        private int id;

        BlockreportHandler(int my_id) {
            id = my_id;
        }

        public void run() {
            while (true) {
                /* Send Periodically HeartBeat */
                File blkReport = new File("block_report.txt");
                if (blkReport.exists() && blkReport.length() != 0) {
                    try {
                        hdfs.BlockReportRequest.Builder request = hdfs.BlockReportRequest.newBuilder();
                        request.setId(id);
                        Registry reg = LocateRegistry.getRegistry(namenode_ip);
                        Namenodedef stub = (Namenodedef) reg.lookup("NameNode");
                        BufferedReader br = new BufferedReader(new FileReader(blkReport));
                        String blockNumber;
                        while ((blockNumber = br.readLine()) != null) {
                            request.addBlockNumbers(Integer.parseInt(blockNumber));
                        }
                        br.close();
                        stub.blockReport(request.build().toByteArray());
                        System.err.println("Sent Block report from Node " + id);
                    } catch (IOException | NotBoundException e) {
                        e.printStackTrace();
                    }
                } else {
                    System.err.println("No block report");
                }
                try {
                    Thread.sleep(10000); /* Sleep for 10 Seconds */
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
package client;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import model.FileDataResponseType;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.RequestType;
import model.ResponseType;
import model.ServerEndpoint;

public class dummyClient {

    private int requestTimeout = 1000;
    private final String FOLDER_PATH = "Downloaded Files";

    @SuppressWarnings("unused")
    private ServerEndpoint endpoint;

    public dummyClient(String ip, int port) {
        try {
            endpoint = new ServerEndpoint(ip, port);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public dummyClient() {
    }

    @SuppressWarnings("unused")
    private void sendInvalidRequest(String ip, int port) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        RequestType req = new RequestType(4, 0, 0, 0, null);
        byte[] sendData = req.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(sendPacket);
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        ResponseType response = new ResponseType(receivePacket.getData());
        loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();
    }

    private void getFileList(String ip, int port) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileListResponseType response = new FileListResponseType(receivePacket.getData());
        loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();
    }

    private long getFileSize(String ip, int port, int file_id) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, file_id, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileSizeResponseType response = new FileSizeResponseType(receivePacket.getData());
        loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();
        return response.getFileSize();
    }

    private byte[] getFileData(String ip, int port, int file_id, long start, long end) throws IOException {
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.setSoTimeout(this.requestTimeout);
        InetAddress IPAddress = InetAddress.getByName(ip);
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, start, end, null)
                .toByteArray();
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        while (true) {
            dsocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
            try {
                dsocket.receive(receivePacket); // This will block until a packet is received or timeout occurs
                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                loggerManager.getInstance(this.getClass()).debug(response.toString());
                dsocket.close();
                return response.getData();
            } catch (SocketTimeoutException e) {
                System.err.println("Receive operation timed out. No packet received.");
            }
        }

    }

    private void getFile(String ip, int port, int file_id) throws IOException {
        File directory = new File(FOLDER_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        String filePath = FOLDER_PATH + "/file_" + file_id;

        long fileSize = getFileSize(ip, port, file_id);
        long packetCount = (fileSize + ResponseType.MAX_DATA_SIZE - 1) / ResponseType.MAX_DATA_SIZE; // Ensure all data
                                                                                                     // is covered

        try (FileOutputStream fos = new FileOutputStream(filePath)) { // Open file output stream
            for (int i = 0; i < packetCount; i++) {
                long start = i * ResponseType.MAX_DATA_SIZE + 1;
                long end = Math.min((i + 1) * ResponseType.MAX_DATA_SIZE, fileSize);

                byte[] packet = getFileData(ip, port, file_id, start, end);
                fos.write(packet); // Write each chunk to the file

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("ip:port is mandatory");
        }
        String[] adr1 = args[0].split(":");
        String ip1 = adr1[0];
        int port1 = Integer.valueOf(adr1[1]);
        dummyClient client = new dummyClient();
        client.getFileList(ip1, port1);
        client.getFile(ip1, port1, 3);

    }
}

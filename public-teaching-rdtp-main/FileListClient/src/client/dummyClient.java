package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.LinkedList;
import java.util.Scanner;

import model.FileDataResponseType;
import model.FileDescriptor;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.RequestType;
import model.ResponseType;
import model.ServerEndpoint;

public class dummyClient {

    private static int requestTimeout = 1000;
    private static final String FOLDER_PATH = "Downloaded Files";
    private static String filePath;

    private LinkedList<Long> rtts = new LinkedList<Long>();
    private static long fileSize = -1;

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

    private String getFileList(String ip, int port) throws IOException {
        InetAddress IPAddress = InetAddress.getByName(ip);
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileListResponseType response = new FileListResponseType(receivePacket.getData());
        // loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();

        StringBuffer sb = new StringBuffer();

        for (FileDescriptor file : response.getFileDescriptors()) {
            sb.append("\n" + file.toString());
        }
        return sb.toString();

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
        // loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();
        return response.getFileSize();
    }

    private byte[] getFileData(String ip, int port, int file_id, long start, long end) throws IOException {
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.setSoTimeout(requestTimeout);
        InetAddress IPAddress = InetAddress.getByName(ip);
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id, start, end, null)
                .toByteArray();
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

        while (true) {
            long startTime = System.nanoTime();
            dsocket.send(new DatagramPacket(sendData, sendData.length, IPAddress, port));
            try {
                dsocket.receive(receivePacket); // This will block until a packet is received or timeout occurs
                long endTime = System.nanoTime();
                rtts.add(endTime - startTime);

                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                // loggerManager.getInstance(this.getClass()).debug(response.toString());
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
        filePath = FOLDER_PATH + "/file_" + file_id;

        long packetCount = (fileSize + ResponseType.MAX_DATA_SIZE - 1) / ResponseType.MAX_DATA_SIZE;
        long startTime = System.currentTimeMillis();
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
        long endTime = System.currentTimeMillis();

        System.out.println("File downloaded successfully in " + (endTime - startTime) + " milliseconds.");

    }

    private String computeFileDigest(String filePath, String algorithm) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] byteArray = new byte[1024];
            int bytesCount = 0;

            while ((bytesCount = fis.read(byteArray)) != -1) {
                digest.update(byteArray, 0, bytesCount);
            }
        }

        byte[] bytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void programLoop(Scanner scanner, String ip1, int port1, dummyClient client) throws IOException {
        String fileList = client.getFileList(ip1, port1);
        System.out.println("File List: " + fileList);
        int fileId = -1;
        while (fileId <= 0) {
            System.out.print("Enter the file ID you want to download: ");
            if (scanner.hasNextInt()) {
                fileId = scanner.nextInt();
                if (fileId <= 0) {
                    System.out.println("File ID must be a positive integer. Please try again.");
                }
            } else {
                System.out.println("Invalid input. Please enter a valid integer.");
                scanner.next();
            }
        }
        System.out.println("Getting file size with ID: " + fileId);
        fileSize = client.getFileSize(ip1, port1, fileId);
        System.out.println("File size: " + fileSize + " bytes. Downloading file...");
        client.getFile(ip1, port1, fileId);
        double averageRTT = calculateAverageRTT(client);
        System.out.println("Average RTT: " + averageRTT + " milliseconds");
        try {
            String md5Digest = computeFileDigest(filePath, "MD5");
            System.out.println("MD5 Digest: " + md5Digest);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private double calculateAverageRTT(dummyClient client) {
        long sum = 0;
        for (long rtt : client.rtts) {
            sum += rtt;
        }
        double averageRtt = sum / (double) client.rtts.size();
        averageRtt /= 1000000; // Convert nanoseconds to milliseconds
        return averageRtt;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("ip:port is mandatory");
        }
        String[] adr1 = args[0].split(":");
        String ip1 = adr1[0];
        int port1 = Integer.valueOf(adr1[1]);
        dummyClient client = new dummyClient();
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n***-----------------------------------***");
            System.out.println("Welcome to the File List Client.");
            System.out.println("Enter 'q' to quit or any other key to continue.");
            System.out.println("***-----------------------------------***\n");
            String input = scanner.next();
            if (input.equals("q")) {
                scanner.close();
                break;
            }
            client.programLoop(scanner, ip1, port1, client);
        }
        scanner.close();
    }
}

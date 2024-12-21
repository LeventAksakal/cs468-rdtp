package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;

import model.FileDataResponseType;
import model.FileDescriptor;
import model.FileListResponseType;
import model.FileSizeResponseType;
import model.NetworkMetrics;
import model.RequestType;
import model.ResponseType;
import model.ServerEndpoint;

public class dummyClient {

    private static int requestTimeout = 1000;
    private static final String FOLDER_PATH = "Downloaded Files";
    private static String filePath;

    private static long fileSize = -1;

    private static ServerEndpoint endpoint1;
    private static ServerEndpoint endpoint2;
    private static int receivedPacketsProgressBar = 0;
    private static int totalPacketsProgressBar = 0;
    private final Object totalReceivedPacketsLock = new Object();

    @SuppressWarnings("unused")
    private void sendInvalidRequest(InetAddress IPAddress, int port) throws IOException {
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

    private String getFileList(ServerEndpoint endpoint) throws IOException {
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(), endpoint.getPort()));
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

    private long getFileSize(ServerEndpoint endpoint, int file_id) throws IOException {
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_SIZE, file_id, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(), endpoint.getPort()));
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileSizeResponseType response = new FileSizeResponseType(receivePacket.getData());
        // loggerManager.getInstance(this.getClass()).debug(response.toString());
        dsocket.close();
        return response.getFileSize();
    }

    private void getFileData(ServerEndpoint endpoint, int file_id, long start, long end,
            BlockingQueue<FileDataResponseType> packetQueue) throws IOException, InterruptedException {
        DatagramSocket dsocket = new DatagramSocket();

        int startPacket = (int) (start / ResponseType.MAX_DATA_SIZE);
        int endPacket = (int) Math.ceil(((double) end / ResponseType.MAX_DATA_SIZE)) - 1;
        /// TODO: change the datastructure to be efficient
        List<Integer> jobPool = new ArrayList<>();
        for (int i = startPacket; i <= endPacket; i++) {
            jobPool.add(i);
        }

        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id,
                start, end, null).toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(),
                endpoint.getPort());
        dsocket.send(sendPacket);

        /// TODO: set timeout dynamically
        dsocket.setSoTimeout(requestTimeout);
        while (!jobPool.isEmpty()) {
            try {
                byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                dsocket.receive(receivePacket);

                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                int receivedPacketIndex = (int) response.getStart_byte() / ResponseType.MAX_DATA_SIZE;

                if (jobPool.contains(receivedPacketIndex)) {
                    /// TODO: maybe have two seperate recevied packets then add them to get
                    /// totalReceivedPackets
                    synchronized (totalReceivedPacketsLock) {
                        receivedPacketsProgressBar++;
                    }
                    packetQueue.put(response);
                    jobPool.remove(Integer.valueOf(receivedPacketIndex));
                }

            } catch (SocketTimeoutException e) {
                System.err.println("Receive operation timed out. Retransmitting packets.");
                for (int packetIndex : jobPool) {
                    long packetStartByte = packetIndex * ResponseType.MAX_DATA_SIZE;
                    long packetEndByte = (packetIndex == endPacket) ? end
                            : (packetIndex + 1) * ResponseType.MAX_DATA_SIZE;

                    byte[] out = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id,
                            packetStartByte, packetEndByte, null).toByteArray();
                    DatagramPacket outPacket = new DatagramPacket(out, out.length,
                            endpoint.getIpAddress(),
                            endpoint.getPort());
                    dsocket.send(outPacket);
                }
            }
        }

        dsocket.close();
    }

    /// TODO: move the socket creation to processPackets
    private void getFile(int file_id) throws IOException {
        File directory = new File(FOLDER_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        filePath = FOLDER_PATH + "/file_" + file_id;

        long packetCount = (fileSize + ResponseType.MAX_DATA_SIZE - 1) / ResponseType.MAX_DATA_SIZE;
        totalPacketsProgressBar = (int) packetCount;

        ConcurrentLinkedQueue<Long> jobPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < packetCount; i++) {
            jobPool.add((long) i);
        }

        BlockingQueue<FileDataResponseType> packetQueue = new PriorityBlockingQueue<>();
        long startTime = System.currentTimeMillis();
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            Thread writerThread = new Thread(() -> {
                try {
                    writePackets(fos, packetQueue, packetCount);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });

            Thread endpoint1Thread = new Thread(() -> {
                try {
                    processPackets(endpoint1, file_id, jobPool, packetQueue);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });

            Thread endpoint2Thread = new Thread(() -> {
                try {
                    processPackets(endpoint2, file_id, jobPool, packetQueue);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });

            Thread progressBar = new Thread(() -> {
                try {
                    updateProgressBar();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });

            writerThread.start();
            endpoint1Thread.start();
            endpoint2Thread.start();
            progressBar.start();

            progressBar.join();
            endpoint1Thread.join();
            endpoint2Thread.join();
            writerThread.join();

        } catch (Exception e) {
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();

        System.out.println("\nFile downloaded successfully in " + (endTime - startTime) + " milliseconds.");
    }

    private void processPackets(ServerEndpoint endpoint, int file_id, ConcurrentLinkedQueue<Long> jobPool,
            BlockingQueue<FileDataResponseType> packetQueue) throws IOException, InterruptedException {
        while (!jobPool.isEmpty()) {

            int packetsToRequest = 5;// calculatePacketsToRequest(endpoint.getMetrics());

            Deque<Long> packetIndices = new LinkedList<>();
            synchronized (jobPool) {
                for (int i = 0; i < packetsToRequest; i++) {
                    Long packetIndex = jobPool.poll();
                    if (packetIndex == null) {
                        break;
                    }
                    packetIndices.add(packetIndex);
                }
            }

            if (packetIndices.isEmpty()) {
                break;
            }

            long start = packetIndices.peekFirst() * ResponseType.MAX_DATA_SIZE + 1;
            long end = Math.min((packetIndices.peekLast() + 1) * ResponseType.MAX_DATA_SIZE, fileSize);

            getFileData(endpoint, file_id, start, end, packetQueue);
        }
    }

    private void updateProgressBar() throws IOException, InterruptedException {
        while (receivedPacketsProgressBar < totalPacketsProgressBar) {
            showProgressBar(receivedPacketsProgressBar, totalPacketsProgressBar, endpoint1, endpoint2);
            Thread.sleep(10);
        }
        showProgressBar(receivedPacketsProgressBar, totalPacketsProgressBar, endpoint1, endpoint2);
    }

    // TODO: implement the logic for calculating the number of packets to request
    @SuppressWarnings("unused")
    private int calculatePacketsToRequest(NetworkMetrics metrics) {
        double throughput = metrics.getAverageThroughput();
        double packetLossRate = metrics.getPacketLossRate();
        double jitter = metrics.getAverageJitter();
        double rtt = metrics.getAverageRtt();

        int basePackets = 10;
        int adjustedPackets = basePackets;

        if (throughput > 0) {
            adjustedPackets = (int) (throughput / ResponseType.MAX_DATA_SIZE);
        }

        if (packetLossRate > 0.1) {
            adjustedPackets = Math.max(1, adjustedPackets / 2);
        }

        if (jitter > 50) {
            adjustedPackets = Math.max(1, adjustedPackets / 2);
        }

        if (rtt > 100) {
            adjustedPackets = Math.max(1, adjustedPackets / 2);
        }

        return Math.max(1, adjustedPackets);
    }

    private void writePackets(FileOutputStream fos, BlockingQueue<FileDataResponseType> packetQueue, long packetCount)
            throws IOException, InterruptedException {
        long nextStartByte = 1;
        while (nextStartByte < fileSize) {
            FileDataResponseType packetData = packetQueue.take();
            if (packetData.getStart_byte() == nextStartByte) {
                fos.write(packetData.getData());
                nextStartByte = packetData.getEnd_byte() + 1;
            } else {
                packetQueue.put(packetData);
                Thread.sleep(10);
            }
        }
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

    private void showProgressBar(int current, int total, ServerEndpoint endpoint1, ServerEndpoint endpoint2) {
        int barLength = 50; // Length of the progress bar in characters
        int progress = (int) ((double) current / total * barLength);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < barLength; i++) {
            if (i < progress) {
                bar.append("=");
            } else {
                bar.append(" ");
            }
        }
        bar.append("] ").append((current * 100) / total).append("%");

        String metrics = String.format(
                "E1 RTT: %.2f ms, Jitter: %.2f ms, Loss: %.2f%% | E2 RTT: %.2f ms, Jitter: %.2f ms, Loss: %.2f%%",
                endpoint1.getMetrics().getAverageRtt(),
                endpoint1.getMetrics().getAverageJitter(),
                endpoint1.getMetrics().getPacketLossRate() * 100,
                endpoint2.getMetrics().getAverageRtt(),
                endpoint2.getMetrics().getAverageJitter(),
                endpoint2.getMetrics().getPacketLossRate() * 100);

        // Clear the previous line
        System.out.print("\033[1A\033[2K");
        // Print the progress bar and metrics on separate lines
        System.out.print("\r" + bar.toString() + "\n" + metrics);
    }

    private void programLoop(Scanner scanner, dummyClient client) throws IOException {
        String fileList = client.getFileList(endpoint1);
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
        fileSize = client.getFileSize(endpoint1, fileId);

        System.out.println("File size: " + fileSize + " bytes. Downloading file...");
        client.getFile(fileId);

        try {
            String md5Digest = computeFileDigest(filePath, "MD5");
            System.out.println("MD5 Digest: " + md5Digest);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java dummyClient <server1_ip:port> <server2_ip:port>");
        }
        String[] adr1 = args[0].split(":");
        String ip1 = adr1[0];
        int port1 = Integer.valueOf(adr1[1]);
        NetworkMetrics metrics1 = new NetworkMetrics(100, 1000);
        endpoint1 = new ServerEndpoint(ip1, port1, metrics1);

        String[] adr2 = args[0].split(":");
        String ip2 = adr2[0];
        int port2 = Integer.valueOf(adr2[1]);
        NetworkMetrics metrics2 = new NetworkMetrics(100, 1000);
        endpoint2 = new ServerEndpoint(ip2, port2, metrics2);

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
            client.programLoop(scanner, client);
        }
        scanner.close();
    }
}

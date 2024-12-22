package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
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

    private static final String FOLDER_PATH = "Downloaded Files";
    private static String filePath;
    private static long fileSize = -1;
    private static ServerEndpoint endpoint1;
    private static ServerEndpoint endpoint2;
    private static int receivedPacketsProgressBar = 0;
    private static int totalPacketsProgressBar = 0;
    private final Object totalReceivedPacketsLock = new Object();

    private String getFileList(ServerEndpoint endpoint) throws IOException {
        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_LIST, 0, 0, 0, null).toByteArray();
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.send(new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(), endpoint.getPort()));
        byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        dsocket.receive(receivePacket);
        FileListResponseType response = new FileListResponseType(receivePacket.getData());
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
        dsocket.close();
        return response.getFileSize();
    }

    private void getFileData(ServerEndpoint endpoint, int file_id, long start, long end, DatagramSocket dsocket,
            BlockingQueue<FileDataResponseType> packetQueue) throws IOException, InterruptedException {

        int startPacket = (int) (start / ResponseType.MAX_DATA_SIZE);
        int endPacket = (int) Math.ceil(((double) end / ResponseType.MAX_DATA_SIZE)) - 1;
        Map<Integer, Boolean> jobPool = new HashMap<>();
        for (int i = startPacket; i <= endPacket; i++) {
            jobPool.put(i, false);
        }

        byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id,
                start, end, null).toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(),
                endpoint.getPort());

        long startTime = System.currentTimeMillis();
        dsocket.send(sendPacket);

        while (jobPool.containsValue(false)) {
            try {
                dsocket.setSoTimeout(calculateRequestTimeOut(endpoint.getMetrics()));
                byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                dsocket.receive(receivePacket);
                long endTime = System.currentTimeMillis();
                FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                int receivedPacketIndex = (int) response.getStart_byte() / ResponseType.MAX_DATA_SIZE;

                if (jobPool.containsKey(receivedPacketIndex) && !jobPool.get(receivedPacketIndex)) {
                    synchronized (totalReceivedPacketsLock) {
                        receivedPacketsProgressBar++;
                        endpoint.metrics.updateThroughput(response.getData().length, System.currentTimeMillis());
                        endpoint.metrics.updateRtt(endTime - startTime);
                        endpoint.metrics.updatePacketLoss(false);
                    }
                    packetQueue.put(response);
                    jobPool.put(receivedPacketIndex, true);
                }

            } catch (SocketTimeoutException e) {
                for (Map.Entry<Integer, Boolean> entry : jobPool.entrySet()) {
                    if (!entry.getValue()) {
                        synchronized (totalReceivedPacketsLock) {
                            endpoint.metrics.updatePacketLoss(true);
                        }
                        int packetIndex = entry.getKey();
                        long packetStartByte = packetIndex * ResponseType.MAX_DATA_SIZE + 1;
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
                startTime = System.currentTimeMillis();
            }
        }
    }

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
        DatagramSocket dsocket = new DatagramSocket();
        while (!jobPool.isEmpty()) {

            int packetsToRequest = calculatePacketsToRequest(endpoint.getMetrics());

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

            getFileData(endpoint, file_id, start, end, dsocket, packetQueue);
        }
        dsocket.close();
    }

    private void updateProgressBar() throws IOException, InterruptedException {
        while (receivedPacketsProgressBar < totalPacketsProgressBar) {
            showProgressBar(receivedPacketsProgressBar, totalPacketsProgressBar, endpoint1, endpoint2);
            Thread.sleep(10);
        }
        showProgressBar(receivedPacketsProgressBar, totalPacketsProgressBar, endpoint1, endpoint2);
    }

    private int calculatePacketsToRequest(NetworkMetrics metrics) {
        double throughput = metrics.getAverageThroughput();
        double packetLossRate = metrics.getPacketLossRate();
        double jitter = metrics.getAverageJitter();

        final int BASE_PACKETS = 10; // Minimum 10KB
        final int MAX_PACKETS = 60; // Maximum 60KB
        final double THROUGHPUT_MULTIPLIER = 10;

        int packets = (int) (throughput * THROUGHPUT_MULTIPLIER);

        if (packetLossRate > 0.1) {
            packets *= (1 - packetLossRate);
        } else if (packetLossRate > 0.05) {
            packets *= 0.9;
        }

        if (jitter > 50) {
            packets *= 0.8;
        } else if (jitter > 20) {
            packets *= 0.9;
        }

        packets = Math.max(BASE_PACKETS, Math.min(packets, MAX_PACKETS));

        return packets;
    }

    private int calculateRequestTimeOut(NetworkMetrics metrics) {

        final int MIN_TIMEOUT = 50; // Minimum timeout in ms
        final int MAX_TIMEOUT = 1000; // Maximum timeout in ms
        final double JITTER_WEIGHT = 0.5;
        final double PACKET_LOSS_WEIGHT = 0.3;
        final double RTT_WEIGHT = 1.2;

        double rttFactor = metrics.getAverageRtt() * RTT_WEIGHT;
        double jitterFactor = metrics.getAverageJitter() * JITTER_WEIGHT;
        double packetLossFactor = metrics.getPacketLossRate() * PACKET_LOSS_WEIGHT;

        double timeout = rttFactor + jitterFactor + packetLossFactor;

        timeout = Math.max(MIN_TIMEOUT, Math.min(MAX_TIMEOUT, timeout));

        return (int) timeout;
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
        int barLength = 50;
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

        String metrics1;
        String metrics2;
        synchronized (totalReceivedPacketsLock) {
            metrics1 = String.format(
                    "E1 | RTT: %.2f ms, Jitter: %.2f ms, Loss: %.2f%%, Throughput: %.2f KB/s",
                    endpoint1.getMetrics().getAverageRtt(),
                    endpoint1.getMetrics().getAverageJitter(),
                    endpoint1.getMetrics().getPacketLossRate() * 100,
                    endpoint1.getMetrics().getAverageThroughput() / 1000);
            metrics2 = String.format(
                    "E2 | RTT: %.2f ms, Jitter: %.2f ms, Loss: %.2f%%, Throughput: %.2f KB/s",
                    endpoint2.getMetrics().getAverageRtt(),
                    endpoint2.getMetrics().getAverageJitter(),
                    endpoint2.getMetrics().getPacketLossRate() * 100,
                    endpoint2.getMetrics().getAverageThroughput() / 1000);
        }

        System.out.print("\033[1A\033[2K");
        System.out.print("\033[1A\033[2K");

        System.out.print("\r" + bar.toString() + "\n" + metrics1 + "\n" + metrics2);
    }

    private void programLoop(Scanner scanner, dummyClient client) throws IOException {
        fileSize = -1;
        receivedPacketsProgressBar = 0;
        totalPacketsProgressBar = 0;

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
            System.out.println("\n[============File List Client============]");
            System.out.println("Enter 'q' to quit or any other key to continue.");
            System.out.println("[========================================]\n");
            String input = scanner.next();
            System.out.print("\033[H\033[2J");
            System.out.flush();
            if (input.equals("q")) {
                scanner.close();
                break;
            }
            client.programLoop(scanner, client);
        }
        scanner.close();
    }
}

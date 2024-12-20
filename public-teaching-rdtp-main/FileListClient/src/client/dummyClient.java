package client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @SuppressWarnings("unused")
    private static ServerEndpoint endpoint2;

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

    /// TODO: getFileData() should send one request for one packet. It should send
    /// burst of requests.
    /// But it should recieve each packet asynchronously. It should re-order the
    /// packets and return the byte[]
    /// Set timeout for the socket dynamically with the RTT 1001, 100001, 2000,
    /// 3000, 203001-20400 204001-204539
    private byte[] getFileData(ServerEndpoint endpoint, int file_id, long start, long end)
            throws IOException, InterruptedException {
        DatagramSocket dsocket = new DatagramSocket();
        dsocket.setSoTimeout(requestTimeout);

        /// TODO: check if packetIDs are calculated correctly
        /// 1-1000 => 1, 2001-3000 =>3, 203001-20400=> 204, 204001-204539 => 205
        int startPacket = (int) (start / ResponseType.MAX_DATA_SIZE);
        int endPacket = (int) (end / ResponseType.MAX_DATA_SIZE);
        int totalPackets = endPacket - startPacket + 1;

        ExecutorService executor = Executors.newFixedThreadPool(totalPackets);
        ConcurrentHashMap<Integer, byte[]> packetMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(totalPackets);

        /// TODO: Instead of this version; use jobPools to assign packets to threads.
        /// Each thread will work until the jobPool is empty
        for (int i = startPacket; i <= endPacket; i++) {
            final int packetIndex = i;
            executor.submit(() -> {
                try {
                    /// TODO: sendData should be check for the endByte. If fileSize<(packetIndex +
                    /// 1) * ResponseType.MAX_DATA_SIZE? fileSize:(packetIndex + 1)
                    /// *ResponseType.MAX_DATA_SIZE
                    byte[] sendData = new RequestType(RequestType.REQUEST_TYPES.GET_FILE_DATA, file_id,
                            packetIndex * ResponseType.MAX_DATA_SIZE, (packetIndex + 1) * ResponseType.MAX_DATA_SIZE,
                            null).toByteArray();
                    byte[] receiveData = new byte[ResponseType.MAX_RESPONSE_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);

                    while (true) {
                        long startTime = System.nanoTime();
                        dsocket.send(new DatagramPacket(sendData, sendData.length, endpoint.getIpAddress(),
                                endpoint.getPort()));
                        try {
                            dsocket.receive(receivePacket);
                            long endTime = System.nanoTime();
                            endpoint.metrics.updateRtt((endTime - startTime));

                            FileDataResponseType response = new FileDataResponseType(receivePacket.getData());
                            int receivedPacketIndex = (int) response.getEnd_byte() / ResponseType.MAX_DATA_SIZE;

                            if (receivedPacketIndex == packetIndex) {
                                // Correct packet received
                                packetMap.put(packetIndex, response.getData());
                                break;
                            } else if (packetMap.containsKey(receivedPacketIndex)) {
                                // Duplicate packet received
                                continue;
                            } else {
                                // Inform the correct thread
                                synchronized (packetMap) {
                                    if (!packetMap.containsKey(receivedPacketIndex)) {
                                        packetMap.put(receivedPacketIndex, response.getData());
                                        packetMap.notifyAll();
                                    }
                                }
                            }
                        } catch (SocketTimeoutException e) {
                            System.err.println("Receive operation timed out. No packet received.");
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        dsocket.close();

        // Reorder packets and combine into a single byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (int i = startPacket; i <= endPacket; i++) {
            outputStream.write(packetMap.get(i));
        }

        return outputStream.toByteArray();
    }
    /// TODO: Get file is the main controller that will select which endpoint to use
    /// and will controll the flow based on endpoint.metrics. It should work like
    /// this:
    /// Have a pool of packets to be downloaded
    /// While(packets not downloaded) assign packets to endpoints based on their
    /// metrics
    /// getFileData() will handle to downloading part and return the downloaded list
    /// of byte[]
    /// getFileData() will return the packets in order so getFile() should write the
    /// recieved packets to
    /// FileOutputStream to avoid buffering the whole downloaded file which is
    /// impossible for large files
    /// Also getFile() should print the throughput and the average RTT of each
    /// endpoint and download progression

    private void getFile(int file_id) throws IOException {
        File directory = new File(FOLDER_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        filePath = FOLDER_PATH + "/file_" + file_id;

        long packetCount = (fileSize + ResponseType.MAX_DATA_SIZE - 1) / ResponseType.MAX_DATA_SIZE;

        // Create a job pool to store the remaining packets that need to be requested
        ConcurrentLinkedQueue<Long> jobPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < packetCount; i++) {
            jobPool.add((long) i);
        }

        long startTime = System.currentTimeMillis();

        /// TODO: endpoint1 and endpoint2 should be properly synchronized before writing
        /// to the file output stream
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            Thread endpoint1Thread = new Thread(() -> {
                try {
                    processPackets(endpoint1, file_id, jobPool, fos);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }

            });

            Thread endpoint2Thread = new Thread(() -> {
                try {
                    processPackets(endpoint2, file_id, jobPool, fos);
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }

            });

            endpoint1Thread.start();
            endpoint2Thread.start();

            endpoint1Thread.join();
            endpoint2Thread.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();

        System.out.println("File downloaded successfully in " + (endTime - startTime) + " milliseconds.");
    }

    /// TODO: Process packets should apply flow control using endpoint.metrics
    private void processPackets(ServerEndpoint endpoint, int file_id, ConcurrentLinkedQueue<Long> jobPool,
            FileOutputStream fos)
            throws IOException, InterruptedException {
        while (!jobPool.isEmpty()) {
            Long packetIndex = jobPool.poll();
            if (packetIndex == null) {
                break;
            }

            long start = packetIndex * ResponseType.MAX_DATA_SIZE + 1;
            long end = Math.min((packetIndex + 1) * ResponseType.MAX_DATA_SIZE, fileSize);

            byte[] packet = getFileData(endpoint, file_id, start, end);
            synchronized (fos) {
                fos.write(packet); // Write each chunk to the file
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

## CS468 Project-2 Repository

**_Authors: Levent Aksakal & Onat Ulutaş_**

[Project requirements](https://docs.google.com/document/d/12iCEmuFoGPEDZ6Yvq0bOBhtMM-qJj3J6rhJSo5Y_rA8/edit?tab=t.0)

### 1) Setup & Run

- Clone this repository and build the docker image under "./FileListServer" with the following commands:
  - `git clone https://github.com/LeventAksakal/cs468-rdtp.git`
  - `cd .\public-teaching-rdtp-main\FileListServer\`
  - `docker build . -t file_server`
- Start the container and run the application with the following commands:
  - `docker run -d -w --rm /app/tc -p 5000:5000/udp -p 5001:5001/udp --name file_server --cap-add=NET_ADMIN file_server /bin/bash ./tc_policy.sh`
  * `docker exec -d -w /app -it file_server java -classpath ./FileListServer-1.0.0.jar:./lib/* server.FileListServer 5000`
  * `docker exec -d -w /app -it file_server java -classpath ./FileListServer-1.0.0.jar:./lib/* server.FileListServer 5001`
- Go to "./FileListClient" and run the the client with these commands:
  - `cd ..\FileListClient\`
  - `.\run.bat 127.0.0.1:5001 127.0.0.1:5000`

### 2) How does it work?

- Client assumes server is always running. We could've implemented a connection start mechanism but it wasn't required.
- Client runs 4 threads in paralel, two worker threads that retrieves packets, one thread for writing the packets to a file, one thread for updating the progress bar.
- Here is the general structure of the code:
  - There is a main program loop which displays the available files, prompts the user for an ID then proceeds to download the file. After completion program loop repeats.
  - `getFile()` method calculates the number of packets to be downloaded, creates a job pool for the two worker threads and initializes all four threads.
  - Each worker thread runs the `processPackets()` method. A server endpoint consists of an IP address, a port number and a `NetworkMetrics` that summarizes the observed performance of that endpoint. Each worker thread determines how many packets to request based on their network metrics with `calculatePacketsToRequest()`. Then calls `private void getFileData()` method to retrieve the packets. Timeout for each packet request is adjusted dynamically with `calculateRequestTimeOut()`. Worker threads run until there is no packet left in the job pool.
  * Writer thread runs `writePackets()` that handles the re-ordering of the packets and writes them to a file as soon as possible since buffering a whole file isn't feasible.
  * Progress Bar thread runs `updateProgressBar()` which updates the progress bar for each recevied packet.

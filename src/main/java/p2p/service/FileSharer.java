package p2p.service;

import p2p.utils.UploadUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple FileSharer:
 * - offerFile(String path) => returns an allocated port (registered)
 * - startFileServer(int port) => binds a ServerSocket on that port and streams the file bytes to the first client that connects
 *
 * Notes:
 * - This implementation serves the file as raw bytes. The HTTP DownloadHandler in controller should stream the socket input to the HTTP response.
 * - This class intentionally keeps behavior simple and blocking for startFileServer (it blocks in accept()), so call it asynchronously.
 */
public class FileSharer {

    private final Map<Integer, String> availableFiles;

    public FileSharer() {
        // synchronize map to be thread-safe
        this.availableFiles = Collections.synchronizedMap(new HashMap<>());
    }

    /**
     * Register a file to be shared. Returns a randomly selected dynamic port (49152-65535).
     * The caller is responsible for starting the server to actually serve the file (startFileServer).
     *
     * @param filepath absolute or relative path to file to serve
     * @return allocated port
     * @throws IOException if unable to allocate or if file doesn't exist
     */
    public int offerFile(String filepath) throws IOException {
        File f = new File(filepath);
        if (!f.exists() || !f.isFile()) {
            throw new FileNotFoundException("File not found: " + filepath);
        }

        // try allocate a dynamic port; simple retry loop
        int tries = 0;
        while (tries < 20) {
            int port = UploadUtils.generateCode(); // returns a dynamic port number
            if (port < 1024 || port > 65535) {
                tries++;
                continue;
            }
            // ensure port not already registered
            synchronized (availableFiles) {
                if (!availableFiles.containsKey(port)) {
                    // Reserve it
                    availableFiles.put(port, f.getAbsolutePath());
                    System.out.println("FileSharer: registered file " + f.getAbsolutePath() + " on port " + port);
                    return port;
                }
            }
            tries++;
        }
        throw new IOException("Unable to allocate a free port for file sharing after retries");
    }

    /**
     * Start a server socket listening on the given port and serve the file registered for that port.
     * This method will block until a client connects and the file is fully sent, or until an error occurs.
     *
     * Call this in a background thread (it blocks on accept()).
     *
     * @param port port allocated by offerFile
     * @throws IOException if network error or no file registered for port
     */
    public void startFileServer(int port) throws IOException {
        String filepath;
        synchronized (availableFiles) {
            filepath = availableFiles.get(port);
        }
        if (filepath == null) {
            throw new FileNotFoundException("No file registered for port " + port);
        }

        File fileToSend = new File(filepath);
        if (!fileToSend.exists() || !fileToSend.isFile()) {
            throw new FileNotFoundException("Registered file missing: " + filepath);
        }

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket();
            // bind to loopback to avoid exposing outside unintentionally; if you need external access, bind to 0.0.0.0
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
            System.out.println("FileSharer: listening on 127.0.0.1:" + port + " for file " + fileToSend.getName());

            try (Socket client = serverSocket.accept()) {
                System.out.println("FileSharer: client connected from " + client.getRemoteSocketAddress() + " - sending file " + fileToSend.getName());
                // Stream file bytes to client
                try (BufferedInputStream fis = new BufferedInputStream(new FileInputStream(fileToSend));
                     BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream())) {

                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                    System.out.println("FileSharer: file '" + fileToSend.getName() + "' sent to " + client.getRemoteSocketAddress());
                } catch (IOException e) {
                    System.err.println("FileSharer: error sending file: " + e.getMessage());
                    throw e;
                } finally {
                    // After serving once, remove registration so port can be reused later
                    synchronized (availableFiles) {
                        availableFiles.remove(port);
                    }
                }
            }

        } finally {
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignore) {}
            }
        }
    }

    /**
     * Return the registered file path for a previously offered port.
     * Returns null if no file is registered for the port.
     */
    public String getRegisteredFilePath(int port) {
        synchronized (availableFiles) {
            return availableFiles.get(port);
        }
    }

    /**
     * Utility: optionally let callers check if a port is registered.
     */
    public boolean isRegistered(int port) {
        synchronized (availableFiles) {
            return availableFiles.containsKey(port);
        }
    }
}

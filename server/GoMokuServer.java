import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class GoMokuServer {
    static final int PORT       = 8888;
    static final int BOARD_SIZE = 10;
    private static final List<WSClient> lobby = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : PORT;
        ServerSocket ss = new ServerSocket(port);
        ss.setReuseAddress(true);
        System.out.println("=== GoMoku Server started on port " + port + " ===");

        while (true) {
            Socket sock = ss.accept();
            sock.setTcpNoDelay(true);
            new Thread(() -> handleClient(sock)).start();
        }
    }

    static void handleClient(Socket sock) {
    try {
        BufferedInputStream bis = new BufferedInputStream(sock.getInputStream());
        byte[] peek = new byte[4];
        bis.mark(4);
        int read = bis.read(peek, 0, 4);
        bis.reset();
        String start = new String(peek, 0, read, "UTF-8");
        if (start.startsWith("GET") || start.startsWith("POST") || start.startsWith("HEAD")) {
            handleHTTP(sock, bis);
        }
    } catch (Exception e) {
        System.err.println("handleClient error: " + e.getMessage());
        try { sock.close(); } catch (IOException ignored) {}
    }
}

    static void handleHTTP(Socket sock, InputStream is) throws Exception {
        // Read full HTTP request
        StringBuilder sb = new StringBuilder();
        int b, prev1=-1, prev2=-1, prev3=-1;
        while (true) {
            b = is.read();
            if (b == -1) break;
            sb.append((char) b);
            if (prev3=='\r' && prev2=='\n' && prev1=='\r' && b=='\n') break;
            prev3=prev2; prev2=prev1; prev1=b;
        }

        String request = sb.toString();
        String firstLine = request.split("\r\n")[0];
        String path = firstLine.split(" ")[1];

        // WebSocket upgrade request
        if (request.contains("Upgrade: websocket") || request.contains("Upgrade: WebSocket")) {
            handleWebSocket(sock, is, request);
            return;
        }

        // Serve files
        OutputStream os = sock.getOutputStream();
        if (path.equals("/") || path.equals("/index.html") || path.equals("/GoMoku.html")) {
            serveFile(os, "GoMoku.html", "text/html");
        } else if (path.equals("/assets/background.gif")) {
            serveFile(os, "assets/background.gif", "image/gif");
        } else if (path.equals("/assets/blackStone.gif")) {
            serveFile(os, "assets/blackStone.gif", "image/gif");
        } else if (path.equals("/assets/whiteStone.gif")) {
            serveFile(os, "assets/whiteStone.gif", "image/gif");
        } else {
            String body = "404 Not Found";
            os.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes());
            os.flush();
            sock.close();
        }
    }

    static void serveFile(OutputStream os, String filename, String mime) throws Exception {
        File f = new File(filename);
        if (!f.exists()) {
            String body = "File not found: " + filename;
            os.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes("UTF-8"));
            os.flush();
            return;
        }
        byte[] data = Files.readAllBytes(f.toPath());
        String header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: " + mime + "\r\n" +
            "Content-Length: " + data.length + "\r\n" +
            "Connection: close\r\n\r\n";
        os.write(header.getBytes("UTF-8"));
        os.write(data);
        os.flush();
    }

    static synchronized void handleWebSocket(Socket sock, InputStream is, String headers) {
        WSClient c;
        try {
            c = new WSClient(sock, is);
            c.completeHandshake(headers);
            System.out.println("[SERVER] WS connected: " + sock.getRemoteSocketAddress());
            String firstMsg = c.recv();
            if (firstMsg != null && firstMsg.startsWith("NAME ")) {
                c.setName(firstMsg.substring(5).trim());
            }
            System.out.println("[SERVER] Player: " + c.getName());
        } catch (Exception e) {
            System.out.println("[SERVER] WS setup failed: " + e.getMessage());
            try { sock.close(); } catch (IOException ignored) {}
            return;
        }

        lobby.add(c);
        System.out.println("[SERVER] Lobby: " + lobby.size());

        if (lobby.size() >= 2) {
            WSClient p1 = lobby.remove(0);
            WSClient p2 = lobby.remove(0);
            new Thread(new GameSession(p1, p2)).start();
        } else {
            c.send("WAIT");
        }
    }
}

class WSClient {
    final Socket sock;
    final InputStream is;
    final OutputStream os;
    String name = "Anonymous";

    WSClient(Socket s, InputStream i) throws IOException {
        sock = s;
        is   = i;
        os   = s.getOutputStream();
    }

    void completeHandshake(String headers) throws Exception {
        String key = null;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.split(":", 2)[1].trim();
            }
        }
        if (key == null) throw new IOException("No WS key");
        byte[] sha1 = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8"));
        String accept = Base64.getEncoder().encodeToString(sha1);
        String resp =
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        os.write(resp.getBytes("UTF-8"));
        os.flush();
    }

    synchronized void send(String msg) {
        try {
            byte[] payload = msg.getBytes("UTF-8");
            int n = payload.length;
            byte[] header = n <= 125
                ? new byte[]{(byte)0x81, (byte)n}
                : new byte[]{(byte)0x81, (byte)126, (byte)((n>>8)&0xFF), (byte)(n&0xFF)};
            os.write(header);
            os.write(payload);
            os.flush();
        } catch (IOException e) {
            System.out.println("[WS] Send failed: " + e.getMessage());
        }
    }

    String recv() throws IOException {
        while (true) {
            int b0 = is.read(); if (b0==-1) return null;
            int b1 = is.read(); if (b1==-1) return null;
            int opcode = b0 & 0x0F;
            if (opcode==8) return null;
            if (opcode==9) { os.write(new byte[]{(byte)0x8A,0x00}); os.flush(); continue; }
            boolean masked = (b1&0x80)!=0;
            int len = b1&0x7F;
            if (len==126) { int h=is.read(),l=is.read(); len=((h&0xFF)<<8)|(l&0xFF); }
            byte[] maskKey = new byte[4];
            if (masked) for (int i=0;i<4;i++) maskKey[i]=(byte)is.read();
            byte[] data = new byte[len];
            int total=0;
            while (total<len) { int got=is.read(data,total,len-total); if(got==-1) return null; total+=got; }
            if (masked) for (int i=0;i<len;i++) data[i]^=maskKey[i&3];
            String result = new String(data,"UTF-8");
            System.out.println("[WS] From " + name + ": " + result);
            return result;
        }
    }

    void setName(String n) { this.name=n; }
    String getName() { return name; }
    void close() { try { sock.close(); } catch (IOException ignored) {} }
}

class GameSession implements Runnable {
    final WSClient black, white;
    final int[][] board = new int[GoMokuServer.BOARD_SIZE][GoMokuServer.BOARD_SIZE];

    GameSession(WSClient b, WSClient w) { black=b; white=w; }

    public void run() {
        System.out.println("[GAME] Started: " + black.getName() + " vs " + white.getName());
        try {
            black.send("START BLACK " + white.getName());
            white.send("START WHITE " + black.getName());

            WSClient cur=black, opp=white;
            int curColor=1;

            while (true) {
                String msg;
                try { msg=cur.recv(); }
                catch (IOException e) { opp.send("OPPONENT_QUIT"); break; }
                if (msg==null) { opp.send("OPPONENT_QUIT"); break; }
                if (!msg.startsWith("MOVE ")) continue;

                String[] p=msg.trim().split("\\s+");
                if (p.length<3) { cur.send("ERROR bad format"); continue; }
                int r,c;
                try { r=Integer.parseInt(p[1]); c=Integer.parseInt(p[2]); }
                catch (NumberFormatException e) { cur.send("ERROR not a number"); continue; }
                if (r<0||r>=GoMokuServer.BOARD_SIZE||c<0||c>=GoMokuServer.BOARD_SIZE) { cur.send("ERROR out of bounds"); continue; }
                if (board[r][c]!=0) { cur.send("ERROR occupied"); continue; }

                board[r][c]=curColor;
                broadcast("MOVE "+r+" "+c+" "+curColor);
                if (checkWin(r,c,curColor)) { broadcast("WIN "+cur.getName()); break; }
                if (boardFull()) { broadcast("DRAW"); break; }

                WSClient t=cur; cur=opp; opp=t;
                curColor=(curColor==1)?2:1;
            }
        } catch (Exception e) { e.printStackTrace(); }
        finally { black.close(); white.close(); System.out.println("[GAME] Ended"); }
    }

    void broadcast(String msg) { black.send(msg); white.send(msg); }

    boolean checkWin(int r,int c,int color) {
        int[][] dirs={{0,1},{1,0},{1,1},{1,-1}};
        for (int[] d:dirs)
            if (1+count(r,c,d[0],d[1],color)+count(r,c,-d[0],-d[1],color)>=5) return true;
        return false;
    }

    int count(int r,int c,int dr,int dc,int col) {
        int n=0; r+=dr; c+=dc;
        while (r>=0&&r<GoMokuServer.BOARD_SIZE&&c>=0&&c<GoMokuServer.BOARD_SIZE&&board[r][c]==col){n++;r+=dr;c+=dc;}
        return n;
    }

    boolean boardFull() {
        for (int[] row:board) for (int v:row) if(v==0) return false;
        return true;
    }
}

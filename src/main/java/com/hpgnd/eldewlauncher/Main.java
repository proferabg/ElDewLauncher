package com.hpgnd.eldewlauncher;

import com.google.gson.Gson;
import kong.unirest.core.Unirest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Main {
    private static final Pattern chatRegex = Pattern.compile("(\\[)((?:[0]?[1-9]|[1][012])[-:\\/.](?:(?:[0-2]?\\d{1})|(?:[3][01]{1}))[-:\\/.](?:(?:\\d{1}\\d{1})))(?![\\d])( )((?:(?:[0-1][0-9])|(?:[2][0-3])|(?:[0-9])):(?:[0-5][0-9])(?::[0-5][0-9])?(?:\\s?(?:am|AM|pm|PM))?)(\\])( )(<)((?:.*))(\\/)((?:.*))(\\/)((?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?))(?![\\d])(>)( )((?:.*))");
    private static Map<String, String> gameCfg = new HashMap<>();
    private static Rcon rcon;
    private static Process process;
    private static ServerData lastServerData;
    private static Gson gson;
    private static String gamePassword = "";
    private static boolean rconAuth = false;

    public static void main(String[] args) {
        gson = new Gson();
        try {
            if(args.length == 2 && args[0].contains("http") && args[0].contains(".zip")){
                processRemoteConfig(args[0], args[1]);
            }
            parseConfig();
            startGame();
            if(process != null) {
                startInput();
                startRcon();
                startStats();
                process.waitFor();
            }
        } catch (InterruptedException e){
            e.printStackTrace();
        }
    }

    private static void processRemoteConfig(String url, String playlist) {
        System.out.println("Using remote configs from '" + url + "' and playlist '" + playlist + "'");
        File dataDir = new File("data"),
                serverDir = new File(dataDir, "server"),
                gameVarDir = new File(dataDir, "game_variants"),
                mapVarDir = new File(dataDir, "map_variants"),
                playlistZip = new File("playlist.zip");
        if(!dataDir.exists()) dataDir.mkdirs();
        if(!serverDir.exists()) serverDir.mkdirs();
        if(!gameVarDir.exists()) serverDir.mkdirs();
        if(!mapVarDir.exists()) serverDir.mkdirs();
        if(downloadFile(url, playlistZip)){
            try (FileInputStream fis = new FileInputStream(playlistZip); ZipInputStream zis = new ZipInputStream(fis)){
                ZipEntry ze;
                while((ze = zis.getNextEntry()) != null){

                    String fileName = ze.getName();
                    if(fileName.contains(playlist + "/data/server/voting.json")){
                        File votingJson = new File(serverDir, "voting.json");
                        if(!writeZipFile(zis, votingJson)){
                            throw new Exception("Failed to unzip voting.json");
                        }
                        System.out.println("Unzipping to " + votingJson.getAbsolutePath());
                    }

                    if(fileName.contains(playlist + "/data/server/mods.json")){
                        File modsJson = new File(serverDir, "mods.json");
                        if(!writeZipFile(zis, modsJson)){
                            throw new Exception("Failed to unzip mods.json");
                        }
                        System.out.println("Unzipping to " + modsJson.getAbsolutePath());
                    }

                    if(fileName.contains("data/game_variants/") && !ze.isDirectory()){
                        String localPath = fileName.split("data/game_variants/")[1];
                        File file = new File(gameVarDir, localPath);
                        file.mkdirs();
                        if(!writeZipFile(zis, file)){
                            throw new Exception("Failed to unzip '" + fileName + "' to '" + localPath + "'");
                        }
                        System.out.println("Unzipping to " + file.getAbsolutePath());
                    }

                    if(fileName.contains("data/map_variants/") && !ze.isDirectory()){
                        String localPath = fileName.split("data/map_variants/")[1];
                        File file = new File(mapVarDir, localPath);
                        file.mkdirs();
                        if(!writeZipFile(zis, file)){
                            throw new Exception("Failed to unzip '" + fileName + "' to '" + localPath + "'");
                        }
                        System.out.println("Unzipping to " + file.getAbsolutePath());
                    }
                }
                zis.closeEntry();
            } catch (Exception e) {
                e.printStackTrace();
            }
            playlistZip.delete();
        }
    }

    private static boolean writeZipFile(ZipInputStream zis, File file) {
        if(file.exists()) file.delete();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            return true;
        } catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }
    private static void parseConfig(){
        try (BufferedReader reader = new BufferedReader(new FileReader("data/dewrito_prefs.cfg"))){
            String line;
            while ((line = reader.readLine()) != null) {
                if(line.trim().isEmpty()) continue;
                String[] parts = line.split(" ");
                if(parts.length > 2) continue;
                gameCfg.put(parts[0], parts[1].replace("\"", ""));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if(gameCfg.containsKey("Server.Password"))
            gamePassword = gameCfg.get("Server.Password");
    }

    private static void startGame(){
        try {
            ProcessBuilder processBuilder;
            if(System.getProperty("os.name").toLowerCase().contains("windows")){
                processBuilder = new ProcessBuilder("eldorado.exe", "-launcher", "-dedicated", "-window", "-height", "200", "-width", "200", "-minimized");
            } else {
                processBuilder = new ProcessBuilder("wine", "eldorado.exe", "-launcher", "-dedicated", "-window", "-height", "200", "-width", "200", "-minimized");
            }
            processBuilder.redirectErrorStream(true);
            System.out.println("Starting server...");
            process = processBuilder.start();
            System.out.println("Server started!");

            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if(line.startsWith("ALSA lib") || line.contains("RtlLeaveCriticalSection") || line.contains("context_choose_pixel_format")) continue;
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            readerThread.start();

            Thread checkerThread = new Thread(() -> {
                while(true){
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                    if(!process.isAlive()){
                        System.out.println("[ElDewLauncher] Detected game process has exited. Closing Launcher...");
                        System.exit(0);
                    }
                }
            });
            checkerThread.start();
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    private static void startInput() {
        Thread inputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if(line.equals("kill-server")){
                        System.out.println("[ElDewLauncher] Killing server...");
                        process.destroy();
                        System.exit(0);
                    } else if(rcon != null && rcon.isOpen())
                        rcon.send(line);
                    else {
                        System.out.println("[RCON] Failed to send command. Rcon is not connected!");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        inputThread.start();
    }

    private static void startRcon(){
        Thread keepAlive = new Thread(() -> {
            try { Thread.sleep(120000); } catch (InterruptedException ignored) { }
            if(rcon != null && rcon.isOpen())
                rcon.send("Version");
        });
        Thread authThread = new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) { }
            if(rcon != null && rcon.isOpen() && !rconAuth)
                rcon.close(-1, "Authentication unsuccessful: \"accept\" not received within 10 seconds.");
        });
        try {
            // connnect
            rcon = new Rcon(new URI("ws://localhost:" + gameCfg.get("Game.RconPort")), (serverHandshake) -> {
                System.out.println("[RCON] Connected to websocket. Authenticating...");
                rcon.send(gameCfg.get("Server.RconPassword"));
                rcon.send("Server.SendChatToRconClients 1");
                keepAlive.start();
            }, (msg) -> {
                if(msg.startsWith("0.7.1")) return;

                else if (msg.startsWith("accept")) {
                    System.out.println("[RCON] Successfully Connected.");
                    rconAuth = true;
                }
                else if (!msg.isEmpty()) {
                    if (isChat(msg)) {
                        ChatMessage cm = parseChat(msg);
                        System.out.println("[" + cm.date + " " + cm.time + "] " + cm.name + ": " + cm.message);
                    } else {
                        System.out.println(msg);
                    }
                }
            }, (closeReason) -> {
                System.out.println("[RCON] Lost Connection to Game Server: " + closeReason);
                System.out.println("[RCON] Reconnecting in 3 seconds.");
                keepAlive.interrupt();
                rconAuth = false;
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                startRcon();
            }, (error) -> {
                rcon.close();
            });
            rcon.connect();
            authThread.start();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private static void startStats(){
        Thread statsThread = new Thread(() -> {
            while(true){
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                String stats = get("http://localhost:" + gameCfg.get("Server.Port") + "/");
                if(!stats.isEmpty()) {
                    ServerData serverData = gson.fromJson(stats, ServerData.class);
                    if(serverData != null)
                        processStats(serverData);
                }
            }
        });
        statsThread.start();
    }

    private static void processStats(ServerData serverData){
        if(lastServerData != null){
            if(!lastServerData.status.equals(serverData.status)){
                if (serverData.status.equals("InGame"))
                    System.out.println(getFormattedTime() + "Game Started - " + serverData.variant + ":" + serverData.variantType + " - " + serverData.map);
                else if (serverData.status.equals("InLobby"))
                    System.out.println(getFormattedTime() + "Match Ended");
            }


            for(Player player : serverData.players){
                if(!playerExists(player.uid, lastServerData.players)){
                    // player joined
                    if(!player.uid.equals("0000000000000000"))
                        System.out.println(getFormattedTime() + player.name + " - " + player.serviceTag + " [" + player.uid + "] joined.");
                }
            }

            for(Player player : lastServerData.players){
                if(!playerExists(player.uid, serverData.players)){
                    if(!player.uid.equals("0000000000000000"))
                        System.out.println(getFormattedTime() + player.name + " - " + player.serviceTag + " [" + player.uid + "] left.");
                }
            }
        }
        lastServerData = serverData;
    }

    private static boolean playerExists(String uid, List<Player> players){
        for(Player player : players){
            if(player.uid.equalsIgnoreCase(uid)) return true;
        }
        return false;
    }

    private static String get(String url) {
        try {
            return Unirest.get(url).header("Authorization", "Basic " + Base64.getEncoder().encodeToString(("dorito:" + gamePassword).getBytes())).asString().getBody();
        } catch (Exception e){
            return "";
        }
//        StringBuilder result = new StringBuilder();
//        try {
//            HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
//            http.setRequestMethod("GET");
//            if(!gamePassword.isEmpty())
//                http.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(("dorito:" + gamePassword).getBytes()));
//            try (BufferedReader reader = new BufferedReader(new InputStreamReader(http.getInputStream()))) {
//                for (String line; (line = reader.readLine()) != null; ) {
//                    result.append(line);
//                }
//            }
//        } catch (IOException ignored) { }
//        return result.toString();
    }

    private static boolean downloadFile(String url, File file){
        if(file.exists()) file.delete();
        System.out.println("Downloading file '" + url + "'");
        return Unirest.get(url)
                .asFile(file.getAbsolutePath())
                .ifFailure((failure) -> {
                    System.out.println("Failed to download '" + url + "'. Response code '" + failure.getStatus() + ": " + failure.getStatusText() + "'");
                })
                .isSuccess();
//        try (BufferedInputStream in = new BufferedInputStream(new URL(url).openStream()); FileOutputStream fileOutputStream = new FileOutputStream(file)) {
//            byte dataBuffer[] = new byte[1024];
//            int bytesRead;
//            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
//                fileOutputStream.write(dataBuffer, 0, bytesRead);
//            }
//            return true;
//        } catch (Exception e){
//            e.printStackTrace();
//            return false;
//        }
    }

    private static boolean isChat(String line) {
        ChatMessage cm = parseChat(line);
        return (cm != null && cm.name != null && cm.ip != null && cm.message != null && cm.uid != null && !cm.name.isEmpty() && !cm.ip.isEmpty() && !cm.message.isEmpty() && !cm.uid.isEmpty());
    }
    public static ChatMessage parseChat(String chat){
        Matcher matcher = chatRegex.matcher(chat);
        if (matcher.find()){
            ChatMessage msg = new ChatMessage();
            msg.date = matcher.group(2);
            msg.time = matcher.group(4);
            msg.name = matcher.group(8);
            msg.uid = matcher.group(10);
            msg.ip = matcher.group(12);
            msg.message = matcher.group(15);
            return msg;
        }
        return null;
    }

    private static String getFormattedTime(){
        SimpleDateFormat sdf = new SimpleDateFormat("[MM/dd/yy HH:mm:ss] ");
        sdf.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
        return sdf.format(new Date());
    }

    public static class ChatMessage {
        public String date;
        public String time;
        public String name;
        public String uid;
        public String ip;
        public String message;
    }

    public static class ServerData {
        public String map;
        public String variant;
        public String variantType;
        public String status;
        public List<Player> players;

    }

    public static class Player {
        public String name;
        public String serviceTag;
        public String uid;
    }

}
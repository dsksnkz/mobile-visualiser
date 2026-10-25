package dev.halo.visualiser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;

/** Paired TLS for Wi-Fi; the WebView and its microphone retain a loopback origin.
 *  One link per process on loopback port 8769, so it can run beside Halo (8766). */
final class PhoneLink implements Closeable {
    static final int PORT=8769;
    interface Listener { void changed(boolean online, String transport); }
    private final SharedPreferences prefs;
    private final List<Listener> listeners=new java.util.concurrent.CopyOnWriteArrayList<>();
    private static PhoneLink shared;
    private static int users;
    private volatile String host, token, fingerprint;
    private volatile int port;
    private volatile SSLSocketFactory tls;
    private volatile boolean running=true;
    private volatile String route="";
    private volatile String wifiError="Not paired";
    private volatile long lastDiscovery;
    final String localToken=UUID.randomUUID().toString();
    private final ServerSocket local;
    private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newCachedThreadPool();

    static synchronized PhoneLink acquire(Context context, Listener listener) throws IOException {
        if(shared==null)shared=new PhoneLink(context.getApplicationContext());
        users++;shared.listeners.add(listener);
        if(!shared.route.isEmpty())listener.changed(true,shared.route);
        return shared;
    }
    static synchronized void release(Listener listener) {
        if(shared==null||!shared.listeners.remove(listener))return;
        if(--users<=0){try{shared.close();}catch(IOException ignored){}shared=null;users=0;}
    }
    private PhoneLink(Context context) throws IOException {
        prefs=context.getSharedPreferences("halo-link",Context.MODE_PRIVATE);
        host=prefs.getString("host","");token=prefs.getString("token","");fingerprint=prefs.getString("fingerprint","");port=prefs.getInt("port",8767);
        configureTLS();
        local=new ServerSocket(PORT,32,InetAddress.getByName("127.0.0.1"));
        workers.execute(() -> {while(running)try{Socket socket=local.accept();workers.execute(() -> proxy(socket));}catch(IOException ignored){}});
        workers.execute(this::watch);
    }
    static String hex(byte[] bytes) {StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    synchronized void pair(JSONObject config) throws Exception {
        String nextHost=config.getString("host"),nextPin=config.getString("fingerprint"),nextToken=config.getString("token");
        int nextPort=config.getInt("port");
        if(!nextHost.matches("[0-9.]{7,15}")||!nextPin.matches("[a-f0-9]{64}")||!nextToken.matches("[A-Za-z0-9_-]{32,128}")||nextPort!=8767)throw new IOException("Invalid pairing");
        host=nextHost;fingerprint=nextPin;token=nextToken;port=nextPort;configureTLS();
        prefs.edit().putString("host",host).putString("fingerprint",fingerprint).putString("token",token).putInt("port",port).apply();
        lastDiscovery=0;route="";
        workers.execute(this::check);
    }
    private void configureTLS() {
        if(fingerprint.isEmpty())return;
        final String pin=fingerprint;
        try {
            X509TrustManager trust=new X509TrustManager(){
                public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
                public void checkClientTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException {throw new java.security.cert.CertificateException("Not a client CA");}
                public void checkServerTrusted(X509Certificate[] chain,String auth) throws java.security.cert.CertificateException {
                    try {if(chain.length==0)throw new Exception();chain[0].checkValidity();
                        if(!MessageDigest.isEqual(hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).getBytes("UTF-8"),pin.getBytes("UTF-8")))throw new Exception();
                    }catch(Exception e){throw new java.security.cert.CertificateException("Unpaired laptop");}
                }
            };
            SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},new SecureRandom());tls=context.getSocketFactory();
        }catch(Exception ignored){tls=null;}
    }
    private HttpURLConnection open(String target,String path,int timeout) throws IOException {
        if(!path.startsWith("/")||path.startsWith("//")||path.indexOf('\r')>=0||path.indexOf('\n')>=0)throw new IOException("Invalid path");
        boolean wifi="wifi".equals(target);
        if(wifi&&(tls==null||host.isEmpty()))throw new IOException("Not paired");
        URL url=new URL(wifi?"https://"+host+":"+port+path:"http://127.0.0.1:8765"+path);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(timeout);connection.setReadTimeout(660000);
        connection.setUseCaches(false);connection.setRequestProperty("X-Halo-Phone","1");
        if(wifi){HttpsURLConnection secure=(HttpsURLConnection)connection;secure.setSSLSocketFactory(tls);
            // Identity is verified by the exact paired certificate, not public DNS.
            secure.setHostnameVerifier((name,session) -> true);secure.setRequestProperty("Authorization","Bearer "+token);}
        return connection;
    }
    private boolean probe(String target) {
        HttpURLConnection connection=null;
        try {connection=open(target,"/api/link","wifi".equals(target)?1000:350);connection.setReadTimeout(1500);
            if(connection.getResponseCode()!=200){if("wifi".equals(target))wifiError="HTTP "+connection.getResponseCode();return false;}
            try(InputStream in=connection.getInputStream()){while(in.read()!=-1){}}
            if("wifi".equals(target))wifiError="";
            return true;
        }catch(Exception error){if("wifi".equals(target))wifiError=error.getClass().getSimpleName()+": "+error.getMessage();return false;}finally{if(connection!=null)connection.disconnect();}
    }
    private synchronized void check() {
        if(!running)return;
        String next="";
        if(!token.isEmpty()) {
            if(probe("wifi"))next="wifi";
            else if(System.currentTimeMillis()-lastDiscovery>8000){discover();if(probe("wifi"))next="wifi";}
        }
        if(next.isEmpty()&&probe("usb"))next="usb";
        if(!next.equals(route)){route=next;for(Listener listener:listeners)listener.changed(!next.isEmpty(),next);}
    }
    private void watch(){while(running){check();try{Thread.sleep(3000);}catch(InterruptedException ignored){break;}}}
    private void discover() {
        lastDiscovery=System.currentTimeMillis();
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setBroadcast(true);socket.setSoTimeout(500);
            String nonce=UUID.randomUUID().toString().replace("-","");
            byte[] request=new JSONObject().put("type","halo-discover-1").put("nonce",nonce).toString().getBytes("UTF-8");
            Set<InetAddress> addresses=new HashSet<>();addresses.add(InetAddress.getByName("255.255.255.255"));
            for(NetworkInterface net:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(InterfaceAddress address:net.getInterfaceAddresses())if(address.getBroadcast()!=null)addresses.add(address.getBroadcast());
            for(InetAddress address:addresses)socket.send(new DatagramPacket(request,request.length,address,8768));
            long deadline=System.currentTimeMillis()+650;
            while(System.currentTimeMillis()<deadline){
                DatagramPacket packet=new DatagramPacket(new byte[2048],2048);socket.receive(packet);
                JSONObject response=new JSONObject(new String(packet.getData(),0,packet.getLength(),"UTF-8"));
                if(!nonce.equals(response.optString("nonce"))||!fingerprint.equals(response.optString("fingerprint"))||response.optInt("port")!=port)continue;
                Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(token.getBytes("UTF-8"),"HmacSHA256"));
                String signature=hex(mac.doFinal((nonce+"|"+port+"|"+fingerprint).getBytes("UTF-8")));
                if(!MessageDigest.isEqual(signature.getBytes("UTF-8"),response.optString("signature").getBytes("UTF-8")))continue;
                host=packet.getAddress().getHostAddress();prefs.edit().putString("host",host).apply();return;
            }
        }catch(Exception ignored){}
    }
    private String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')bytes.write(c);if(bytes.size()>8192)throw new IOException("Header too large");}
        return bytes.toString("UTF-8");
    }
    private void proxy(Socket socket) {
        HttpURLConnection upstream=null;
        try(Socket client=socket){
            client.setSoTimeout(15000);InputStream in=new BufferedInputStream(client.getInputStream());OutputStream out=client.getOutputStream();
            String[] request=line(in).split(" ",3);if(request.length!=3)return;
            Map<String,String> headers=new HashMap<>();String header;int total=0;
            while(!(header=line(in)).isEmpty()){total+=header.length();if(total>32768)throw new IOException();int colon=header.indexOf(':');if(colon>0)headers.put(header.substring(0,colon).toLowerCase(Locale.ROOT),header.substring(colon+1).trim());}
            if("GET".equals(request[0])&&"/native/link".equals(request[1])){
                // Local diagnostics only; never includes keys or laptop content.
                byte[] info=new JSONObject().put("paired",!token.isEmpty()).put("transport",route).put("wifi_error",wifiError).toString().getBytes("UTF-8");
                out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: "+info.length+"\r\n\r\n").getBytes("UTF-8"));out.write(info);return;
            }
            String cookie=headers.getOrDefault("cookie","");boolean authorized=false;
            for(String value:cookie.split(";"))if(value.trim().equals("HaloLocal="+localToken))authorized=true;
            String origin=headers.get("origin");
            if(!authorized||!("127.0.0.1:"+PORT).equals(headers.get("host"))||(origin!=null&&!("http://127.0.0.1:"+PORT).equals(origin))){error(out,403);return;}
            if(!Arrays.asList("GET","HEAD","POST").contains(request[0])||headers.containsKey("transfer-encoding")){error(out,400);return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<0||length>8000000){error(out,413);return;}
            byte[] body=new byte[length];int offset=0,read;while(offset<length&&(read=in.read(body,offset,length-offset))!=-1)offset+=read;if(offset!=length)throw new IOException();
            String selected=route;if(selected.isEmpty()){error(out,503);return;}
            upstream=open(selected,request[1],1200);upstream.setRequestMethod(request[0]);
            for(String key:Arrays.asList("content-type","origin","x-wake-session","x-wake-reset","accept","if-modified-since"))if(headers.containsKey(key))upstream.setRequestProperty(key,headers.get(key));
            if("POST".equals(request[0])){upstream.setDoOutput(true);upstream.setFixedLengthStreamingMode(length);try(OutputStream upload=upstream.getOutputStream()){upload.write(body);}}
            int code=upstream.getResponseCode();StringBuilder response=new StringBuilder("HTTP/1.1 "+code+" "+upstream.getResponseMessage()+"\r\nConnection: close\r\n");
            for(String key:Arrays.asList("Content-Type","Content-Length","Cache-Control","Last-Modified")){String value=upstream.getHeaderField(key);if(value!=null)response.append(key+": "+value+"\r\n");}
            response.append("X-Halo-Transport: "+selected+"\r\nX-Content-Type-Options: nosniff\r\n\r\n");out.write(response.toString().getBytes("UTF-8"));out.flush();
            if(!"HEAD".equals(request[0])){InputStream download=code>=400?upstream.getErrorStream():upstream.getInputStream();if(download!=null)try(InputStream data=download){byte[] buffer=new byte[16384];while((read=data.read(buffer))!=-1){out.write(buffer,0,read);out.flush();}}}
        }catch(Exception ignored){}finally{if(upstream!=null)upstream.disconnect();}
    }
    private void error(OutputStream out,int code) throws IOException {byte[] body="Halo connection unavailable. Reconnecting…".getBytes("UTF-8");out.write(("HTTP/1.1 "+code+" Unavailable\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes("UTF-8"));out.write(body);}
    public void close() throws IOException {running=false;local.close();workers.shutdownNow();}
}

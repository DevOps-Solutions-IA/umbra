package app.umbra.transport;

import app.umbra.crypto.Engine;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** LAB ONLY. Requires exclusive TLS fixture ownership; never invoke from production. */
public final class RelayIdleReuseProbe {
    public interface Control {
        /** Host associates the sole new owned server connection with this warmup. */
        void warmed() throws Exception;
        /** Must observe that SAME connection's real idle-close event, within eight seconds. */
        void awaitOwnedIdleClose() throws Exception;
    }
    public static void reproduce(Engine engine,String base,Control control) throws Exception {
        SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        GateFactory gate=new GateFactory(original);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        ExecutorService observer=Executors.newSingleThreadExecutor();
        LegacyPooledRelayClient relay=null;
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(gate);
            relay=new LegacyPooledRelayClient(base,()->engine.connectivity().isNetworkSessionAllowed(),engine.admission());
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            String credential=engine.admission().requireAdmission().wire();
            relay.publicRealm(); // Real pinned authority endpoint + explicit network lease; drains response.
            if(gate.created.get()!=1)throw new AssertionError("Probe warmup did not own exactly one TLS socket");
            control.warmed();
            gate.armed.set(true);
            LegacyPooledRelayClient owned=relay;
            Future<Throwable> result=worker.submit(()->{
                try {owned.publishAdmissionCredential(credential);return null;}
                catch(Throwable failure){return failure;}
            });
            if(!gate.blocked.await(3,TimeUnit.SECONDS))throw new AssertionError("Probe did not reach post-healthcheck write gate");
            // Host must acknowledge the actual default five-second server idle closure.
            Future<?> observation=observer.submit(()->{control.awaitOwnedIdleClose();return null;});
            try {observation.get(8,TimeUnit.SECONDS);}finally{observation.cancel(true);}
            gate.release.countDown();
            Throwable failure=result.get(5,TimeUnit.SECONDS);
            if(gate.created.get()!=1 || gate.intercepted.get()!=1)
                throw new AssertionError("Probe did not reuse only its warm TLS socket");
            if(!(failure instanceof IOException) || failure.getMessage()==null ||
                    !failure.getMessage().startsWith("unexpected end of stream"))
                throw new AssertionError("Expected Android response EOF was not reproduced");
            if(engine.connectivity().isNetworkSessionAllowed())
                throw new AssertionError("Expected EOF did not revoke the network session");
        } finally {
            gate.release.countDown();
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            if(relay!=null)relay.close();
            worker.shutdownNow();observer.shutdownNow();
            if(!worker.awaitTermination(5,TimeUnit.SECONDS) || !observer.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Probe worker did not close");
        }
    }
    /** Invoke only after a new explicit laboratory connect following the negative control. */
    public static void verifyFreshTransport(Engine engine,String base) throws Exception {
        SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();
        GateFactory observer=new GateFactory(original);
        RelayClient relay=null;
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(observer);
            relay=new RelayClient(base,()->engine.connectivity().isNetworkSessionAllowed(),engine.admission());
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            String credential=engine.admission().requireAdmission().wire();
            relay.publicRealm();
            if(observer.created.get()!=1)throw new AssertionError("Fixed transport warmup socket mismatch");
            relay.publishAdmissionCredential(credential);
            if(observer.created.get()!=2 || observer.intercepted.get()!=0)
                throw new AssertionError("Fixed transport did not use exactly two fresh TLS sockets");
            if(!engine.connectivity().isNetworkSessionAllowed())
                throw new AssertionError("Fixed transport lost network authorization");
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            if(relay!=null)relay.close();
        }
    }
    private static final class GateFactory extends SSLSocketFactory {
        final SSLSocketFactory delegate;
        final AtomicInteger created=new AtomicInteger(),intercepted=new AtomicInteger();
        final AtomicBoolean armed=new AtomicBoolean();
        final CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        GateFactory(SSLSocketFactory delegate){this.delegate=delegate;}
        Socket wrap(Socket socket){created.incrementAndGet();return new GateSocket((SSLSocket)socket,this);}
        void beforeWrite() throws IOException {
            if(!armed.compareAndSet(true,false))return;
            intercepted.incrementAndGet();blocked.countDown();
            try {if(!release.await(9,TimeUnit.SECONDS))throw new IOException("Synthetic write gate expired");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Synthetic write gate interrupted");}
        }
        public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        public Socket createSocket(Socket s,String h,int p,boolean c)throws IOException{return wrap(delegate.createSocket(s,h,p,c));}
        public Socket createSocket(String h,int p)throws IOException{return wrap(delegate.createSocket(h,p));}
        public Socket createSocket(String h,int p,InetAddress a,int l)throws IOException{return wrap(delegate.createSocket(h,p,a,l));}
        public Socket createSocket(InetAddress h,int p)throws IOException{return wrap(delegate.createSocket(h,p));}
        public Socket createSocket(InetAddress h,int p,InetAddress a,int l)throws IOException{return wrap(delegate.createSocket(h,p,a,l));}
    }
    /** Delegates trust, handshake, sessions, endpoint settings and cipher suites unchanged. */
    private static final class GateSocket extends SSLSocket {
        final SSLSocket socket;final GateFactory gate;
        GateSocket(SSLSocket socket,GateFactory gate){this.socket=socket;this.gate=gate;}
        public InputStream getInputStream()throws IOException{return socket.getInputStream();}
        public OutputStream getOutputStream()throws IOException {
            OutputStream target=socket.getOutputStream();
            return new OutputStream(){
                public void write(int b)throws IOException{gate.beforeWrite();target.write(b);}
                public void write(byte[] b,int o,int n)throws IOException{gate.beforeWrite();target.write(b,o,n);}
                public void flush()throws IOException{target.flush();}
                public void close()throws IOException{target.close();}
            };
        }
        public void close()throws IOException{socket.close();}
        public boolean isClosed(){return socket.isClosed();}
        public boolean isConnected(){return socket.isConnected();}
        public boolean isBound(){return socket.isBound();}
        public boolean isInputShutdown(){return socket.isInputShutdown();}
        public boolean isOutputShutdown(){return socket.isOutputShutdown();}
        public void shutdownInput()throws IOException{socket.shutdownInput();}
        public void shutdownOutput()throws IOException{socket.shutdownOutput();}
        public void setSoTimeout(int t)throws SocketException{socket.setSoTimeout(t);}
        public int getSoTimeout()throws SocketException{return socket.getSoTimeout();}
        public void setTcpNoDelay(boolean v)throws SocketException{socket.setTcpNoDelay(v);}
        public boolean getTcpNoDelay()throws SocketException{return socket.getTcpNoDelay();}
        public InetAddress getInetAddress(){return socket.getInetAddress();}
        public InetAddress getLocalAddress(){return socket.getLocalAddress();}
        public int getPort(){return socket.getPort();}
        public int getLocalPort(){return socket.getLocalPort();}
        public SocketAddress getRemoteSocketAddress(){return socket.getRemoteSocketAddress();}
        public SocketAddress getLocalSocketAddress(){return socket.getLocalSocketAddress();}
        public String[] getSupportedCipherSuites(){return socket.getSupportedCipherSuites();}
        public String[] getEnabledCipherSuites(){return socket.getEnabledCipherSuites();}
        public void setEnabledCipherSuites(String[] s){socket.setEnabledCipherSuites(s);}
        public String[] getSupportedProtocols(){return socket.getSupportedProtocols();}
        public String[] getEnabledProtocols(){return socket.getEnabledProtocols();}
        public void setEnabledProtocols(String[] p){socket.setEnabledProtocols(p);}
        public SSLSession getSession(){return socket.getSession();}
        public SSLSession getHandshakeSession(){return socket.getHandshakeSession();}
        public SSLParameters getSSLParameters(){return socket.getSSLParameters();}
        public void setSSLParameters(SSLParameters p){socket.setSSLParameters(p);}
        public void addHandshakeCompletedListener(HandshakeCompletedListener l){socket.addHandshakeCompletedListener(l);}
        public void removeHandshakeCompletedListener(HandshakeCompletedListener l){socket.removeHandshakeCompletedListener(l);}
        public void startHandshake()throws IOException{socket.startHandshake();}
        public void setUseClientMode(boolean m){socket.setUseClientMode(m);}
        public boolean getUseClientMode(){return socket.getUseClientMode();}
        public void setNeedClientAuth(boolean n){socket.setNeedClientAuth(n);}
        public boolean getNeedClientAuth(){return socket.getNeedClientAuth();}
        public void setWantClientAuth(boolean w){socket.setWantClientAuth(w);}
        public boolean getWantClientAuth(){return socket.getWantClientAuth();}
        public void setEnableSessionCreation(boolean e){socket.setEnableSessionCreation(e);}
        public boolean getEnableSessionCreation(){return socket.getEnableSessionCreation();}
    }
    private RelayIdleReuseProbe(){}
}

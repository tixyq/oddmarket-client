package com.oddmarket;
// Unused certificate pinning.

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public final class PinnedTrust {

    private static volatile SSLSocketFactory cachedFactory;

    private PinnedTrust() {}

    public static synchronized SSLSocketFactory forOddmarket(Context context) {
        if (cachedFactory != null) return cachedFactory;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream in = context.getApplicationContext().getResources()
                    .openRawResource(R.raw.oddmarket_pinned_ca);
            Certificate ca;
            try {
                ca = cf.generateCertificate(in);
            } finally {
                in.close();
            }

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("oddmarket-ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            cachedFactory = new Tls12SocketFactory(sslContext.getSocketFactory());
            return cachedFactory;
        } catch (Exception e) {
            FileLogger.w(Utils.TAG, "Failed to build pinned trust for oddmarket.ct.ws", e);
            return null;
        }
    }

    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.2", "TLSv1.1", "TLSv1"};
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket patch(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket sslSocket = (SSLSocket) socket;
                List<String> supported = Arrays.asList(sslSocket.getSupportedProtocols());
                List<String> enable = new ArrayList<String>();
                for (String protocol : PREFERRED_PROTOCOLS) {
                    if (supported.contains(protocol)) {
                        enable.add(protocol);
                    }
                }
                if (!enable.isEmpty()) {
                    sslSocket.setEnabledProtocols(enable.toArray(new String[enable.size()]));
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }
    }
}

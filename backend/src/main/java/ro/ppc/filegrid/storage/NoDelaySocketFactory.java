package ro.ppc.filegrid.storage;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

final class NoDelaySocketFactory extends SocketFactory {

    @Override
    public Socket createSocket() throws IOException {
        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localHost, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(new InetSocketAddress(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(address, port));
        return socket;
    }

    private Socket connect(InetSocketAddress address) throws IOException {
        Socket socket = createSocket();
        socket.connect(address);
        return socket;
    }
}
